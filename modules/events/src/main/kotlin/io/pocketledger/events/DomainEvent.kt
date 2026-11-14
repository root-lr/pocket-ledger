package io.pocketledger.events

import kotlin.reflect.KClass

/** Marqueur des événements de domaine. Nommés au passé, sérialisés en JSON, versionnés. */
sealed interface DomainEvent

/** Contextes = topics Kafka. La valeur est écrite dans `outbox.aggregate_type` et route le message (Debezium). */
enum class Context(val topicSegment: String) {
    LEDGER("ledger"),
    BUDGETING("budgeting"),
    CATEGORIZATION("categorization"),
    ALERTS("alerts"),
    IDENTITY("identity"),
    ;

    val topic: String get() = "pocketledger.$topicSegment.events.v1"
}

/** Registre nom ↔ classe : le nom (simpleName) est le `event_type` stocké. */
object EventTypes {
    val all: Map<String, KClass<out DomainEvent>> =
        DomainEvent::class.sealedSubclasses.associateBy { it.simpleName!! }

    fun classOf(eventType: String): KClass<out DomainEvent> =
        all[eventType] ?: throw IllegalArgumentException("Type d'événement inconnu : $eventType")

    fun nameOf(event: DomainEvent): String = event::class.simpleName!!

    /** Version courante du schéma de chaque type ; incrémentée avec un upcaster quand le format change. */
    val schemaVersions: Map<String, Int> = all.keys.associateWith { 1 }
}
