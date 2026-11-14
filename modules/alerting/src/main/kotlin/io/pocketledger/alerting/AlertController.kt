package io.pocketledger.alerting

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.AlertDismissed
import io.pocketledger.events.Context
import io.pocketledger.platform.eventstore.EventContext
import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.platform.web.CurrentUser
import io.pocketledger.platform.web.Cursors
import io.pocketledger.platform.web.Page
import io.pocketledger.platform.web.clampLimit
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.SyncItem
import io.pocketledger.shared.ports.SyncSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

data class AlertDto(
    val id: UUID,
    val type: String,
    val status: String,
    val details: Map<String, String?>,
    val createdAt: OffsetDateTime,
    val readAt: OffsetDateTime?,
)

@Service
class AlertService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val cipher: FieldCipher,
    private val eventStore: EventStore,
) : SyncSource {
    override val section = "alerts"

    fun list(userId: UUID, status: String?, cursor: String?, limit: Int): Page<AlertDto> {
        val params = mutableMapOf<String, Any?>("u" to userId, "l" to limit + 1)
        val where = mutableListOf("user_id = :u")
        status?.let {
            where += "status = :s"
            params["s"] = it.uppercase()
        }
        Cursors.decode(cursor)?.let { (createdAt, id) ->
            where += "(created_at, id) < (:c, :id)"
            params["c"] = OffsetDateTime.parse(createdAt)
            params["id"] = UUID.fromString(id)
        }
        val rows =
            jdbc
                .sql("$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY created_at DESC, id DESC LIMIT :l")
                .params(params)
                .query { rs, _ -> map(rs, userId) }
                .list()
        val items = rows.take(limit)
        return Page(items, if (rows.size > limit) items.last().let { Cursors.encode(it.createdAt, it.id) } else null)
    }

    fun markRead(userId: UUID, alertId: UUID): AlertDto {
        jdbc
            .sql("UPDATE alert SET status = 'READ', read_at = now() WHERE id = :id AND user_id = :u AND status = 'NEW'")
            .param("id", alertId)
            .param("u", userId)
            .update()
        return get(userId, alertId)
    }

    @Transactional
    fun dismiss(userId: UUID, alertId: UUID): AlertDto {
        val current = get(userId, alertId)
        if (current.status != "DISMISSED") {
            val stored =
                eventStore.append(
                    "Alert", Context.ALERTS, alertId, eventStore.currentVersion(alertId), userId,
                    listOf(AlertDismissed(alertId)), EventContext.current(),
                )
            jdbc
                .sql("UPDATE alert SET status = 'DISMISSED', position = :p WHERE id = :id AND user_id = :u")
                .param("p", stored.last().globalPosition)
                .param("id", alertId)
                .param("u", userId)
                .update()
        }
        return get(userId, alertId)
    }

    fun get(userId: UUID, alertId: UUID): AlertDto =
        jdbc
            .sql("$SELECT WHERE id = :id AND user_id = :u")
            .param("id", alertId)
            .param("u", userId)
            .query { rs, _ -> map(rs, userId) }
            .optional()
            .orElseThrow { NotFoundException("Alerte", alertId) }

    override fun changesSince(userId: UUID, position: Long, limit: Int): List<SyncItem> =
        jdbc
            .sql(
                "SELECT id, type, status, details, created_at, read_at, position FROM alert " +
                    "WHERE user_id = :u AND position > :p ORDER BY position LIMIT :l",
            )
            .param("u", userId)
            .param("p", position)
            .param("l", limit)
            .query { rs, _ ->
                val dto = map(rs, userId)
                SyncItem(dto.id.toString(), rs.getLong("position"), dto.status == "DISMISSED", dto)
            }.list()

    /** Les champs suffixés "Enc" sont chiffrés : déchiffrés à la lecture, exposés sans le suffixe. */
    private fun map(rs: ResultSet, userId: UUID): AlertDto {
        val raw: Map<String, String> = mapper.readValue(rs.getString("details"), object : TypeReference<Map<String, String>>() {})
        val details =
            raw.entries.associate { (k, v) ->
                if (k.endsWith("Enc")) k.removeSuffix("Enc") to cipher.decrypt(userId, v) else k to v
            }
        return AlertDto(
            rs.getObject("id", UUID::class.java),
            rs.getString("type"),
            rs.getString("status"),
            details,
            rs.getObject("created_at", OffsetDateTime::class.java),
            rs.getObject("read_at", OffsetDateTime::class.java),
        )
    }

    private companion object {
        const val SELECT = "SELECT id, type, status, details, created_at, read_at FROM alert"
    }
}

@RestController
@RequestMapping("/api/v1/alerts")
class AlertController(private val alerts: AlertService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): Page<AlertDto> = alerts.list(CurrentUser.id(), status, cursor, clampLimit(limit))

    @PostMapping("/{id}/read")
    fun read(
        @PathVariable id: UUID,
    ): AlertDto = alerts.markRead(CurrentUser.id(), id)

    @PostMapping("/{id}/dismiss")
    fun dismiss(
        @PathVariable id: UUID,
    ): AlertDto = alerts.dismiss(CurrentUser.id(), id)
}
