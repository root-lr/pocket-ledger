package io.pocketledger.platform.eventstore

import io.micrometer.core.instrument.MeterRegistry
import io.pocketledger.shared.ConcurrencyException
import io.pocketledger.shared.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Exécute une commande dans un point de sauvegarde (transaction imbriquée) et la rejoue
 * jusqu'à 3 fois en cas de conflit de version. Au-delà : 409 Conflict.
 */
@Component
class CommandExecutor(transactionManager: PlatformTransactionManager, meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val conflicts = meterRegistry.counter("pocketledger.commands.version_conflicts")
    private val template =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }

    fun <T> execute(block: () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                @Suppress("UNCHECKED_CAST")
                return template.execute { block() } as T
            } catch (e: ConcurrencyException) {
                conflicts.increment()
                if (attempt >= MAX_ATTEMPTS) {
                    throw ConflictException("concurrent-modification", "Modification concurrente, réessayez")
                }
                log.debug("Conflit de version sur {}, tentative {}", e.aggregateId, attempt)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
