package io.pocketledger.platform.processing

import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.pocketledger.platform.eventstore.EventContext
import io.pocketledger.platform.eventstore.EventSerializer
import io.pocketledger.platform.eventstore.StoredEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Applique un événement à un processeur, au plus une fois par (processeur, agrégat, version) :
 * le checkpoint et le read model sont mis à jour dans la même transaction (consommateur idempotent).
 */
@Component
class ProcessorRunner(
    private val jdbc: JdbcClient,
    private val serializer: EventSerializer,
    transactionManager: PlatformTransactionManager,
    private val meterRegistry: MeterRegistry,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val shredded =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .build<UUID, Boolean>()

    fun handleRecord(processor: EventProcessor, json: String) {
        handle(processor, serializer.parseEnvelope(json))
    }

    fun handle(processor: EventProcessor, event: StoredEvent) {
        if (!processor.handles(event.eventType)) return
        // Un événement arrivé après le crypto-shredding ne doit pas recréer de lignes pour cet utilisateur.
        if (event.eventType != "UserKeyShredded" && isShredded(event.userId)) return
        tx.executeWithoutResult {
            val last = lastVersion(processor.name, event.aggregateId)
            if (event.version <= last) {
                meterRegistry.counter("pocketledger.processing.duplicates", "processor", processor.name).increment()
                return@executeWithoutResult
            }
            EventContext.causedBy(event) { processor.process(event) }
            jdbc
                .sql(
                    """
                    INSERT INTO processor_checkpoint (processor, aggregate_id, last_version, last_position, updated_at)
                    VALUES (:processor, :aggregateId, :version, :position, now())
                    ON CONFLICT (processor, aggregate_id) DO UPDATE
                    SET last_version = EXCLUDED.last_version, last_position = EXCLUDED.last_position, updated_at = now()
                    """.trimIndent(),
                ).param("processor", processor.name)
                .param("aggregateId", event.aggregateId)
                .param("version", event.version)
                .param("position", event.globalPosition)
                .update()
        }
        Timer
            .builder("pocketledger.processing.lag")
            .description("Délai entre l'écriture de l'événement et son traitement")
            .tag("processor", processor.name)
            .register(meterRegistry)
            .record(Duration.between(event.occurredAt, Instant.now()))
    }

    private fun isShredded(userId: UUID): Boolean {
        if (shredded.getIfPresent(userId) == true) return true
        val result =
            jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM shredded_user WHERE user_id = :u)")
                .param("u", userId)
                .query(Boolean::class.javaObjectType)
                .single()
        if (result) shredded.put(userId, true)
        return result
    }

    fun lastVersion(processorName: String, aggregateId: UUID): Int =
        jdbc
            .sql("SELECT last_version FROM processor_checkpoint WHERE processor = :p AND aggregate_id = :a")
            .param("p", processorName)
            .param("a", aggregateId)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(0)

    /** Plus haute position globale traitée pour un utilisateur : sert à la lecture après écriture (?minVersion). */
    fun processedPosition(processorName: String, aggregateId: UUID): Long =
        jdbc
            .sql("SELECT last_position FROM processor_checkpoint WHERE processor = :p AND aggregate_id = :a")
            .param("p", processorName)
            .param("a", aggregateId)
            .query(Long::class.javaObjectType)
            .optional()
            .orElse(0L)
}
