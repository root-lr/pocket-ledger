package io.pocketledger.platform.eventstore

import io.pocketledger.events.Context
import io.pocketledger.events.DomainEvent
import io.pocketledger.events.EventTypes
import io.pocketledger.shared.ConcurrencyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface EventStore {
    /**
     * Ajoute des événements avec contrôle de concurrence optimiste, et les écrit dans l'outbox
     * dans la même transaction. Lève [ConcurrencyException] si la version attendue est dépassée.
     */
    fun append(
        aggregateType: String,
        context: Context,
        aggregateId: UUID,
        expectedVersion: Int,
        userId: UUID,
        events: List<DomainEvent>,
        metadata: EventMetadata,
    ): List<StoredEvent>

    fun load(aggregateId: UUID, afterVersion: Int = 0): List<StoredEvent>

    fun readFrom(position: Long, limit: Int, userId: UUID? = null): List<StoredEvent>

    fun currentVersion(aggregateId: UUID): Int
}

@Repository
class JdbcEventStore(
    private val jdbc: JdbcClient,
    private val serializer: EventSerializer,
    private val clock: Clock,
) : EventStore {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(
        aggregateType: String,
        context: Context,
        aggregateId: UUID,
        expectedVersion: Int,
        userId: UUID,
        events: List<DomainEvent>,
        metadata: EventMetadata,
    ): List<StoredEvent> {
        val occurredAt = clock.instant()
        return events.mapIndexed { index, event ->
            val version = expectedVersion + index + 1
            val eventId = UUID.randomUUID()
            val eventType = EventTypes.nameOf(event)
            val schemaVersion = EventTypes.schemaVersions.getValue(eventType)
            val position =
                jdbc
                    .sql(
                        """
                        INSERT INTO event_store (event_id, aggregate_type, aggregate_id, version, event_type, schema_version,
                                                 user_id, payload, metadata, occurred_at)
                        VALUES (:eventId, :aggregateType, :aggregateId, :version, :eventType, :schemaVersion,
                                :userId, CAST(:payload AS jsonb), CAST(:metadata AS jsonb), :occurredAt)
                        ON CONFLICT (aggregate_id, version) DO NOTHING
                        RETURNING global_position
                        """.trimIndent(),
                    ).param("eventId", eventId)
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", aggregateId)
                    .param("version", version)
                    .param("eventType", eventType)
                    .param("schemaVersion", schemaVersion)
                    .param("userId", userId)
                    .param("payload", serializer.payloadJson(event))
                    .param("metadata", serializer.metadataJson(metadata))
                    .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                    .query(Long::class.javaObjectType)
                    .optional()
                    .orElseThrow { ConcurrencyException(aggregateId, expectedVersion) }

            val stored =
                StoredEvent(
                    position, eventId, eventType, schemaVersion, aggregateType, aggregateId,
                    version, userId, occurredAt, metadata, event,
                )
            writeOutbox(context, stored)
            stored
        }
    }

    /**
     * Debezium lit l'insertion dans le WAL ; la ligne est supprimée aussitôt, la table reste vide.
     * L'Outbox Event Router ignore les suppressions.
     */
    private fun writeOutbox(context: Context, stored: StoredEvent) {
        jdbc
            .sql(
                """
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:id, :context, :aggregateId, :eventType, CAST(:payload AS jsonb))
                """.trimIndent(),
            ).param("id", stored.eventId)
            .param("context", context.topicSegment)
            .param("aggregateId", stored.aggregateId.toString())
            .param("eventType", stored.eventType)
            .param("payload", serializer.envelope(stored))
            .update()
        jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", stored.eventId).update()
    }

    override fun load(aggregateId: UUID, afterVersion: Int): List<StoredEvent> =
        jdbc
            .sql("$SELECT WHERE aggregate_id = :id AND version > :after ORDER BY version")
            .param("id", aggregateId)
            .param("after", afterVersion)
            .query { rs, _ -> map(rs) }
            .list()

    override fun readFrom(position: Long, limit: Int, userId: UUID?): List<StoredEvent> {
        val userFilter = if (userId != null) "AND user_id = :userId" else ""
        val spec =
            jdbc
                .sql("$SELECT WHERE global_position > :position $userFilter ORDER BY global_position LIMIT :limit")
                .param("position", position)
                .param("limit", limit)
        if (userId != null) spec.param("userId", userId)
        return spec.query { rs, _ -> map(rs) }.list()
    }

    override fun currentVersion(aggregateId: UUID): Int =
        jdbc
            .sql("SELECT COALESCE(MAX(version), 0) FROM event_store WHERE aggregate_id = :id")
            .param("id", aggregateId)
            .query(Int::class.javaObjectType)
            .single()

    private fun map(rs: ResultSet): StoredEvent {
        val eventType = rs.getString("event_type")
        val schemaVersion = rs.getInt("schema_version")
        return StoredEvent(
            globalPosition = rs.getLong("global_position"),
            eventId = rs.getObject("event_id", UUID::class.java),
            eventType = eventType,
            schemaVersion = schemaVersion,
            aggregateType = rs.getString("aggregate_type"),
            aggregateId = rs.getObject("aggregate_id", UUID::class.java),
            version = rs.getInt("version"),
            userId = rs.getObject("user_id", UUID::class.java),
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            metadata = serializer.readMetadata(rs.getString("metadata")),
            event = serializer.readPayload(eventType, schemaVersion, rs.getString("payload")),
        )
    }

    private companion object {
        const val SELECT =
            "SELECT global_position, event_id, aggregate_type, aggregate_id, version, event_type, schema_version, " +
                "user_id, payload, metadata, occurred_at FROM event_store"
    }
}
