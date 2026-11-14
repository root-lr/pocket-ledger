package io.pocketledger.identity

import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ports.PushTokenDirectory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class DeviceView(
    val id: UUID,
    val name: String,
    val platform: String,
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime?,
    val revoked: Boolean,
)

@Service
class DeviceService(
    private val jdbc: JdbcClient,
    private val audit: SecurityAudit,
    private val tokens: org.springframework.beans.factory.ObjectProvider<TokenService>,
) : PushTokenDirectory {
    fun upsert(userId: UUID, deviceId: UUID?, name: String, platform: String): UUID {
        val id = deviceId ?: UUID.randomUUID()
        val updated =
            jdbc
                .sql(
                    """
                    INSERT INTO devices (id, user_id, name, platform, created_at, last_seen_at)
                    VALUES (:id, :u, :n, :p, now(), now())
                    ON CONFLICT (id) DO UPDATE SET last_seen_at = now(), revoked_at = NULL, name = EXCLUDED.name
                    WHERE devices.user_id = EXCLUDED.user_id
                    """.trimIndent(),
                ).param("id", id)
                .param("u", userId)
                .param("n", name.take(100))
                .param("p", platform.take(20))
                .update()
        // Un deviceId appartenant à un autre utilisateur n'est jamais réutilisé.
        return if (updated == 0) upsert(userId, null, name, platform) else id
    }

    fun list(userId: UUID): List<DeviceView> =
        jdbc
            .sql("SELECT id, name, platform, created_at, last_seen_at, revoked_at FROM devices WHERE user_id = :u ORDER BY created_at")
            .param("u", userId)
            .query { rs, _ ->
                DeviceView(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("name"),
                    rs.getString("platform"),
                    rs.getObject("created_at", OffsetDateTime::class.java),
                    rs.getObject("last_seen_at", OffsetDateTime::class.java),
                    rs.getObject("revoked_at", OffsetDateTime::class.java) != null,
                )
            }.list()

    @Transactional
    fun revoke(userId: UUID, deviceId: UUID) {
        val updated =
            jdbc
                .sql("UPDATE devices SET revoked_at = now(), push_token = NULL WHERE id = :d AND user_id = :u")
                .param("d", deviceId)
                .param("u", userId)
                .update()
        if (updated == 0) throw NotFoundException("Appareil", deviceId)
        tokens.getObject().revokeDevice(deviceId)
        audit.record(userId, "DEVICE_REVOKED", mapOf("deviceId" to deviceId))
    }

    fun setPushToken(userId: UUID, deviceId: UUID, pushToken: String?) {
        val updated =
            jdbc
                .sql("UPDATE devices SET push_token = :t WHERE id = :d AND user_id = :u AND revoked_at IS NULL")
                .param("t", pushToken)
                .param("d", deviceId)
                .param("u", userId)
                .update()
        if (updated == 0) throw NotFoundException("Appareil", deviceId)
    }

    override fun tokens(userId: UUID): List<String> =
        jdbc
            .sql("SELECT push_token FROM devices WHERE user_id = :u AND revoked_at IS NULL AND push_token IS NOT NULL")
            .param("u", userId)
            .query(String::class.java)
            .list()
}
