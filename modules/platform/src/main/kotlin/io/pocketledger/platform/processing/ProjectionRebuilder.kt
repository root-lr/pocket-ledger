package io.pocketledger.platform.processing

import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Reconstruit une projection depuis l'event store (pas depuis Kafka, dont la rétention est limitée).
 */
@Component
class ProjectionRebuilder(
    private val processors: List<EventProcessor>,
    private val eventStore: EventStore,
    private val runner: ProcessorRunner,
    private val jdbc: JdbcClient,
    private val control: ObjectProvider<ProcessorControl>,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun names(): List<String> = processors.filter { it.rebuildable }.map { it.name }

    fun rebuild(name: String): Long {
        val processor = find(name)
        val ctl = control.ifAvailable
        ctl?.pause(name)
        try {
            tx.executeWithoutResult {
                processor.reset()
                jdbc.sql("DELETE FROM processor_checkpoint WHERE processor = :p").param("p", name).update()
            }
            val count = replay(processor, null)
            log.info("Projection {} reconstruite : {} événements rejoués", name, count)
            return count
        } finally {
            ctl?.resume(name)
        }
    }

    /** Reconstruction ciblée pour un utilisateur, appelée depuis le consommateur lui-même (pas de pause). */
    fun rebuildForUser(name: String, userId: UUID): Long {
        val processor = find(name)
        tx.executeWithoutResult {
            processor.resetUser(userId)
            jdbc
                .sql(
                    """
                    DELETE FROM processor_checkpoint WHERE processor = :p
                    AND aggregate_id IN (SELECT DISTINCT aggregate_id FROM event_store WHERE user_id = :u)
                    """.trimIndent(),
                ).param("p", name)
                .param("u", userId)
                .update()
        }
        return replay(processor, userId)
    }

    private fun replay(processor: EventProcessor, userId: UUID?): Long {
        var position = 0L
        var count = 0L
        while (true) {
            val page = eventStore.readFrom(position, PAGE_SIZE, userId)
            if (page.isEmpty()) return count
            page.forEach { event ->
                runner.handle(processor, event)
                count++
            }
            position = page.last().globalPosition
        }
    }

    private fun find(name: String): EventProcessor {
        val processor = processors.firstOrNull { it.name == name } ?: throw NotFoundException("Projection", name)
        if (!processor.rebuildable) {
            throw ValidationException("not-rebuildable", "$name émet des événements et ne peut pas être rejoué")
        }
        return processor
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
