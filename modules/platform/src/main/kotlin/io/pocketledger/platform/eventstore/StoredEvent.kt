package io.pocketledger.platform.eventstore

import io.pocketledger.events.DomainEvent
import java.time.Instant
import java.util.UUID

/** Métadonnées techniques propagées avec chaque événement (audit, traçabilité). */
data class EventMetadata(
    val correlationId: String? = null,
    val causationId: String? = null,
    val idempotencyKey: String? = null,
    val deviceId: String? = null,
)

/** Événement tel que persisté dans l'event store, puis publié par Debezium. */
data class StoredEvent(
    val globalPosition: Long,
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val aggregateType: String,
    val aggregateId: UUID,
    val version: Int,
    val userId: UUID,
    val occurredAt: Instant,
    val metadata: EventMetadata,
    val event: DomainEvent,
)
