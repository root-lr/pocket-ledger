package io.pocketledger.identity

import io.pocketledger.shared.UnauthorizedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val tokenType: String = "Bearer")

/**
 * Access token JWT (15 min) + refresh token opaque rotatif (30 jours) lié à un appareil.
 * Réutilisation d'un refresh token déjà consommé → révocation de toute la famille (vol probable).
 */
@Service
class TokenService(
    private val jdbc: JdbcClient,
    private val encoder: JwtEncoder,
    private val audit: SecurityAudit,
    private val clock: Clock,
    @Value("\${pocketledger.jwt.issuer:https://api.pocketledger.dev}") private val issuer: String,
    @Value("\${pocketledger.jwt.key-id:pocketledger-1}") private val keyId: String,
) {
    private val random = SecureRandom()
    private val accessTtl = Duration.ofMinutes(15)
    private val refreshTtl = Duration.ofDays(30)

    fun issue(userId: UUID, deviceId: UUID, roles: List<String>, familyId: UUID = UUID.randomUUID()): TokenPair {
        val now = clock.instant()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .claim("roles", roles)
                .claim("device", deviceId.toString())
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).build()
        val access = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        val refresh = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        jdbc
            .sql(
                """
                INSERT INTO refresh_tokens (id, user_id, device_id, family_id, token_hash, roles, expires_at, created_at)
                VALUES (:id, :u, :d, :f, :h, :r, :exp, now())
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("u", userId)
            .param("d", deviceId)
            .param("f", familyId)
            .param("h", hash(refresh))
            .param("r", roles.joinToString(","))
            .param("exp", OffsetDateTime.ofInstant(now.plus(refreshTtl), ZoneOffset.UTC))
            .update()
        return TokenPair(access, refresh, accessTtl.seconds)
    }

    // La révocation de la famille doit être validée même si l'appel échoue (réutilisation détectée).
    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun rotate(refreshToken: String): TokenPair {
        val row =
            jdbc
                .sql(
                    """
                    SELECT t.id, t.user_id, t.device_id, t.family_id, t.roles, t.expires_at, t.used_at, t.revoked_at, d.revoked_at AS device_revoked
                    FROM refresh_tokens t JOIN devices d ON d.id = t.device_id
                    WHERE t.token_hash = :h FOR UPDATE OF t
                    """.trimIndent(),
                ).param("h", hash(refreshToken))
                .query { rs, _ ->
                    RefreshRow(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("user_id", UUID::class.java),
                        rs.getObject("device_id", UUID::class.java),
                        rs.getObject("family_id", UUID::class.java),
                        rs.getString("roles").split(",").filter { it.isNotBlank() },
                        rs.getObject("expires_at", OffsetDateTime::class.java),
                        rs.getObject("used_at", OffsetDateTime::class.java) != null,
                        rs.getObject("revoked_at", OffsetDateTime::class.java) != null ||
                            rs.getObject("device_revoked", OffsetDateTime::class.java) != null,
                    )
                }.optional()
                .orElseThrow { UnauthorizedException("invalid-refresh-token", "Refresh token invalide") }

        if (row.used) {
            revokeFamily(row.familyId)
            audit.record(row.userId, "REFRESH_TOKEN_REUSE", mapOf("deviceId" to row.deviceId))
            throw UnauthorizedException("refresh-token-reused", "Refresh token déjà utilisé : session révoquée")
        }
        if (row.revoked || row.expiresAt.toInstant().isBefore(clock.instant())) {
            throw UnauthorizedException("invalid-refresh-token", "Refresh token expiré ou révoqué")
        }
        jdbc.sql("UPDATE refresh_tokens SET used_at = now() WHERE id = :id").param("id", row.id).update()
        jdbc.sql("UPDATE devices SET last_seen_at = now() WHERE id = :id").param("id", row.deviceId).update()
        return issue(row.userId, row.deviceId, row.roles, row.familyId)
    }

    fun revokeByToken(refreshToken: String) {
        jdbc
            .sql(
                "UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL AND family_id = " +
                    "(SELECT family_id FROM refresh_tokens WHERE token_hash = :h)",
            ).param("h", hash(refreshToken))
            .update()
    }

    fun revokeFamily(familyId: UUID) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL").param("f", familyId).update()
    }

    fun revokeDevice(deviceId: UUID) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE device_id = :d AND revoked_at IS NULL").param("d", deviceId).update()
    }

    fun revokeAll(userId: UUID) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update()
    }

    private fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private data class RefreshRow(
        val id: UUID,
        val userId: UUID,
        val deviceId: UUID,
        val familyId: UUID,
        val roles: List<String>,
        val expiresAt: OffsetDateTime,
        val used: Boolean,
        val revoked: Boolean,
    )
}
