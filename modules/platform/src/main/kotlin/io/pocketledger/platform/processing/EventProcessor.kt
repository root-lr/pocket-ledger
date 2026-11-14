package io.pocketledger.platform.processing

import io.pocketledger.events.Context
import io.pocketledger.platform.eventstore.StoredEvent
import java.util.UUID

/**
 * Consommateur d'événements : projection (read model) ou process manager (émet de nouveaux événements).
 * Chaque processeur a son propre consumer group Kafka et son point de reprise par agrégat.
 */
interface EventProcessor {
    /** Nom unique : consumer group Kafka et clé des checkpoints. */
    val name: String

    /** Contextes (topics) consommés. */
    val contexts: Set<Context>

    /** Un process manager émet des événements : il n'est pas rejoué lors d'un rebuild. */
    val rebuildable: Boolean get() = true

    fun handles(eventType: String): Boolean

    /** Appelé dans une transaction ; doit être idempotent vis-à-vis de l'état de la transaction concernée. */
    fun process(event: StoredEvent)

    /** Vide les tables du read model avant reconstruction complète. */
    fun reset() {}

    /** Vide les lignes d'un utilisateur avant reconstruction ciblée (FR-97). */
    fun resetUser(userId: UUID) {}
}

/** Pause / reprise des conteneurs Kafka pendant une reconstruction (implémenté par l'application). */
interface ProcessorControl {
    fun pause(processorName: String)

    fun resume(processorName: String)
}
