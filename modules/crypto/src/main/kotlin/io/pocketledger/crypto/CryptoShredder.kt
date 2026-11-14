package io.pocketledger.crypto

import io.pocketledger.events.Context
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.EventContext
import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.platform.eventstore.SnapshotStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Droit à l'effacement (FR-81) : la DEK est détruite, les snapshots supprimés, et l'événement
 * UserKeyShredded demande à chaque projection de purger les lignes de l'utilisateur.
 * Les événements restent dans l'event store (intégrité) mais leurs champs sensibles sont illisibles.
 */
@Service
class CryptoShredder(
    private val keys: UserKeyService,
    private val eventStore: EventStore,
    private val snapshots: SnapshotStore,
    private val clock: Clock,
    private val jdbc: org.springframework.jdbc.core.simple.JdbcClient,
) {
    @Transactional
    fun shred(userId: UUID) {
        keys.destroy(userId)
        jdbc
            .sql("INSERT INTO shredded_user (user_id, shredded_at) VALUES (:u, now()) ON CONFLICT DO NOTHING")
            .param("u", userId)
            .update()
        snapshots.deleteForUser(userId)
        eventStore.append(
            aggregateType = "UserKey",
            context = Context.IDENTITY,
            aggregateId = userId,
            expectedVersion = eventStore.currentVersion(userId),
            userId = userId,
            events = listOf(UserKeyShredded(clock.instant().toString())),
            metadata = EventContext.current(),
        )
    }
}
