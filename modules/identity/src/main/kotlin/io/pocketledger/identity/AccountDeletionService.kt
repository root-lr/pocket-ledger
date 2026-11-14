package io.pocketledger.identity

import io.pocketledger.crypto.CryptoShredder
import io.pocketledger.shared.UnauthorizedException
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.UserDataExporter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Suppression de compte par crypto-shredding (FR-81, §7.3 du PRD). */
@Service
class AccountDeletionService(
    private val jdbc: JdbcClient,
    private val auth: AuthService,
    private val shredder: CryptoShredder,
    private val tokens: TokenService,
    private val preferences: PreferencesService,
    private val audit: SecurityAudit,
) {
    @Transactional
    fun delete(userId: UUID, password: String) {
        if (!auth.checkPassword(userId, password)) throw UnauthorizedException("invalid-credentials", "Mot de passe incorrect")
        shredder.shred(userId)
        tokens.revokeAll(userId)
        jdbc
            .sql(
                """
                UPDATE users SET email_enc = NULL, email_index = 'deleted:' || id::text, password_hash = '!',
                       deleted_at = now() WHERE id = :u
                """.trimIndent(),
            ).param("u", userId)
            .update()
        jdbc.sql("UPDATE devices SET revoked_at = now(), push_token = NULL, name = 'supprimé' WHERE user_id = :u").param("u", userId).update()
        preferences.evict(userId)
        audit.record(userId, "ACCOUNT_DELETED")
    }
}

@Component
class IdentityExporter(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    private val preferences: PreferencesService,
    private val devices: DeviceService,
) : UserDataExporter {
    override val section = "profile"

    override fun export(userId: UUID): Any {
        val (emailEnc, createdAt) =
            jdbc
                .sql("SELECT email_enc, created_at FROM users WHERE id = :u")
                .param("u", userId)
                .query { rs, _ -> rs.getString("email_enc") to rs.getObject("created_at", OffsetDateTime::class.java) }
                .single()
        return mapOf(
            "userId" to userId,
            "email" to emailEnc?.let { cipher.decrypt(userId, it) },
            "createdAt" to createdAt,
            "preferences" to preferences.get(userId),
            "devices" to devices.list(userId),
        )
    }
}
