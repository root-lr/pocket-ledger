package io.pocketledger.platform.eventstore

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.Context
import io.pocketledger.events.DomainEvent
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Agrégat event-sourcé : l'état est une valeur immuable reconstruite par [evolve].
 * Les commandes valident les invariants puis appellent [raise].
 */
abstract class EventSourcedAggregate<S : Any>(val id: UUID, initialState: S) {
    var state: S = initialState
        private set
    var version: Int = 0
        private set
    private val pending = mutableListOf<DomainEvent>()

    val pendingEvents: List<DomainEvent> get() = pending.toList()
    val isNew: Boolean get() = version == 0 && pending.isEmpty()

    protected abstract fun evolve(state: S, event: DomainEvent): S

    protected fun raise(event: DomainEvent) {
        state = evolve(state, event)
        pending += event
    }

    fun replay(event: DomainEvent, eventVersion: Int) {
        state = evolve(state, event)
        version = eventVersion
    }

    fun restore(snapshot: S, snapshotVersion: Int) {
        state = snapshot
        version = snapshotVersion
    }

    fun markCommitted() {
        version += pending.size
        pending.clear()
    }
}

class AggregateRepository<A : EventSourcedAggregate<S>, S : Any>(
    private val eventStore: EventStore,
    private val snapshots: SnapshotStore,
    private val cipher: FieldCipher,
    private val mapper: ObjectMapper,
    private val aggregateType: String,
    private val context: Context,
    private val stateClass: Class<S>,
    private val snapshotFormatVersion: Int,
    private val snapshotEvery: Int,
    private val factory: (UUID) -> A,
) {
    fun find(id: UUID): A? {
        val aggregate = factory(id)
        snapshots.load(id)?.let { snapshot ->
            // Snapshot = cache jetable : format inconnu ou clé détruite → on rejoue les événements.
            if (snapshot.formatVersion == snapshotFormatVersion) {
                cipher.decryptBytes(snapshot.userId, snapshot.state)?.let { bytes ->
                    aggregate.restore(mapper.readValue(bytes, stateClass), snapshot.version)
                }
            }
        }
        eventStore.load(id, aggregate.version).forEach { aggregate.replay(it.event, it.version) }
        return if (aggregate.version == 0) null else aggregate
    }

    fun get(id: UUID): A = find(id) ?: throw NotFoundException(aggregateType, id)

    fun save(aggregate: A, userId: UUID, metadata: EventMetadata = EventContext.current()): List<StoredEvent> {
        val events = aggregate.pendingEvents
        if (events.isEmpty()) return emptyList()
        val before = aggregate.version
        val stored = eventStore.append(aggregateType, context, aggregate.id, before, userId, events, metadata)
        aggregate.markCommitted()
        if (aggregate.version / snapshotEvery > before / snapshotEvery) {
            val bytes = cipher.encryptBytes(userId, mapper.writeValueAsBytes(aggregate.state))
            snapshots.save(Snapshot(aggregate.id, userId, aggregate.version, snapshotFormatVersion, bytes))
        }
        return stored
    }
}

/** Fabrique les dépôts d'agrégats avec l'infrastructure injectée. */
@Component
class AggregateRepositories(
    private val eventStore: EventStore,
    private val snapshots: SnapshotStore,
    private val cipher: FieldCipher,
    private val mapper: ObjectMapper,
) {
    fun <A : EventSourcedAggregate<S>, S : Any> create(
        aggregateType: String,
        context: Context,
        stateClass: Class<S>,
        snapshotFormatVersion: Int = 1,
        snapshotEvery: Int = 100,
        factory: (UUID) -> A,
    ): AggregateRepository<A, S> =
        AggregateRepository(
            eventStore, snapshots, cipher, mapper, aggregateType, context,
            stateClass, snapshotFormatVersion, snapshotEvery, factory,
        )
}

class Snapshot(val aggregateId: UUID, val userId: UUID, val version: Int, val formatVersion: Int, val state: ByteArray)

@Component
class SnapshotStore(private val jdbc: JdbcClient) {
    fun load(aggregateId: UUID): Snapshot? =
        jdbc
            .sql("SELECT aggregate_id, user_id, version, snapshot_version, state FROM snapshots WHERE aggregate_id = :id")
            .param("id", aggregateId)
            .query { rs, _ ->
                Snapshot(
                    rs.getObject("aggregate_id", UUID::class.java),
                    rs.getObject("user_id", UUID::class.java),
                    rs.getInt("version"),
                    rs.getInt("snapshot_version"),
                    rs.getBytes("state"),
                )
            }.optional()
            .orElse(null)

    fun save(snapshot: Snapshot) {
        jdbc
            .sql(
                """
                INSERT INTO snapshots (aggregate_id, user_id, version, snapshot_version, state, taken_at)
                VALUES (:id, :userId, :version, :format, :state, :takenAt)
                ON CONFLICT (aggregate_id) DO UPDATE
                SET version = EXCLUDED.version, snapshot_version = EXCLUDED.snapshot_version,
                    state = EXCLUDED.state, taken_at = EXCLUDED.taken_at
                """.trimIndent(),
            ).param("id", snapshot.aggregateId)
            .param("userId", snapshot.userId)
            .param("version", snapshot.version)
            .param("format", snapshot.formatVersion)
            .param("state", snapshot.state)
            .param("takenAt", OffsetDateTime.now())
            .update()
    }

    fun deleteForUser(userId: UUID) {
        jdbc.sql("DELETE FROM snapshots WHERE user_id = :userId").param("userId", userId).update()
    }
}
