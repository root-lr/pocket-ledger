package io.pocketledger.identity

import io.pocketledger.crypto.AesGcm
import io.pocketledger.crypto.GlobalBlindIndex
import io.pocketledger.crypto.UserKeyService
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.UnauthorizedException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

data class RegisterCommand(val email: String, val password: String, val referenceCurrency: String?, val timezone: String?)

data class LoginCommand(val email: String, val password: String, val deviceId: UUID?, val deviceName: String?, val platform: String?)

@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val keys: UserKeyService,
    private val cipher: FieldCipher,
    private val emailIndex: GlobalBlindIndex,
    private val tokens: TokenService,
    private val devices: DeviceService,
    private val preferences: PreferencesService,
    private val audit: SecurityAudit,
    private val emailSender: EmailSender,
    private val clock: Clock,
    @Value("\${pocketledger.crypto.blind-index-key}") private val linkKeyBase64: String,
    @Value("\${pocketledger.public-url:http://localhost:8080}") private val publicUrl: String,
    @Value("\${pocketledger.admin-emails:}") adminEmails: String,
) {
    private val passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val admins = adminEmails.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    private val linkKey = Base64.getDecoder().decode(linkKeyBase64.trim())

    @Transactional
    fun register(command: RegisterCommand): UUID {
        val email = command.email.trim().lowercase()
        if (!EMAIL.matches(email)) throw ValidationException("invalid-email", "Adresse email invalide")
        if (command.password.length < 10) throw ValidationException("weak-password", "Mot de passe de 10 caractères minimum")
        val index = emailIndex.of(email)
        val exists =
            jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM users WHERE email_index = :i)")
                .param("i", index)
                .query(Boolean::class.javaObjectType)
                .single()
        if (exists) throw ConflictException("email-taken", "Un compte existe déjà pour cet email")

        val userId = UUID.randomUUID()
        keys.createKey(userId)
        jdbc
            .sql(
                "INSERT INTO users (id, email_enc, email_index, password_hash, email_verified, created_at) " +
                    "VALUES (:id, :e, :i, :p, false, now())",
            ).param("id", userId)
            .param("e", cipher.encrypt(userId, email))
            .param("i", index)
            .param("p", passwords.encode(command.password))
            .update()
        preferences.createDefaults(userId, command.referenceCurrency, command.timezone)
        audit.record(userId, "REGISTERED")
        emailSender.send(email, "Vérifiez votre adresse", "$publicUrl/api/v1/auth/verify-email?token=${verificationToken(userId)}")
        return userId
    }

    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun login(command: LoginCommand): TokenPair {
        val email = command.email.trim().lowercase()
        val user =
            jdbc
                .sql(
                    "SELECT id, password_hash, failed_attempts, locked_until FROM users " +
                        "WHERE email_index = :i AND deleted_at IS NULL FOR UPDATE",
                ).param("i", emailIndex.of(email))
                .query { rs, _ ->
                    UserRow(
                        rs.getObject("id", UUID::class.java),
                        rs.getString("password_hash"),
                        rs.getInt("failed_attempts"),
                        rs.getObject("locked_until", OffsetDateTime::class.java),
                    )
                }.optional()
                .orElse(null)
        if (user == null) {
            passwords.matches(command.password, DUMMY_HASH) // temps constant : ne révèle pas l'existence du compte
            throw UnauthorizedException("invalid-credentials", "Identifiants invalides")
        }
        val now = OffsetDateTime.now(clock)
        if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
            throw UnauthorizedException("account-locked", "Compte temporairement verrouillé, réessayez plus tard")
        }
        if (!passwords.matches(command.password, user.passwordHash)) {
            val failures = user.failedAttempts + 1
            // Verrouillage progressif après 5 échecs : 1, 2, 4… minutes, plafonné à 1 h.
            val lockedUntil =
                if (failures >= MAX_FAILURES) {
                    now.plus(Duration.ofMinutes(minOf(60L, 1L shl (failures - MAX_FAILURES).coerceAtMost(6))))
                } else {
                    null
                }
            jdbc
                .sql("UPDATE users SET failed_attempts = :f, locked_until = :l WHERE id = :id")
                .param("f", failures)
                .param("l", lockedUntil)
                .param("id", user.id)
                .update()
            audit.record(user.id, "LOGIN_FAILED", mapOf("failures" to failures))
            throw UnauthorizedException("invalid-credentials", "Identifiants invalides")
        }
        jdbc.sql("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = :id").param("id", user.id).update()
        val deviceId = devices.upsert(user.id, command.deviceId, command.deviceName ?: "Appareil", command.platform ?: "unknown")
        audit.record(user.id, "LOGIN", mapOf("deviceId" to deviceId))
        val roles = if (email in admins) listOf("USER", "ADMIN") else listOf("USER")
        return tokens.issue(user.id, deviceId, roles)
    }

    fun verifyEmail(token: String) {
        val userId = parseVerificationToken(token) ?: throw ValidationException("invalid-token", "Lien de vérification invalide ou expiré")
        jdbc.sql("UPDATE users SET email_verified = true WHERE id = :id").param("id", userId).update()
        audit.record(userId, "EMAIL_VERIFIED")
    }

    fun checkPassword(userId: UUID, password: String): Boolean {
        val hash =
            jdbc
                .sql("SELECT password_hash FROM users WHERE id = :id AND deleted_at IS NULL")
                .param("id", userId)
                .query(String::class.java)
                .optional()
                .orElse(null) ?: return false
        return passwords.matches(password, hash)
    }

    /** Lien signé : userId.expiration.hmac (valable 48 h). */
    private fun verificationToken(userId: UUID): String {
        val expires = clock.instant().plus(Duration.ofHours(48)).epochSecond
        val payload = "$userId.$expires"
        return "$payload.${sign(payload)}"
    }

    private fun parseVerificationToken(token: String): UUID? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val payload = "${parts[0]}.${parts[1]}"
        if (sign(payload) != parts[2]) return null
        if ((parts[1].toLongOrNull() ?: 0) < clock.instant().epochSecond) return null
        return runCatching { UUID.fromString(parts[0]) }.getOrNull()
    }

    private fun sign(payload: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(AesGcm.hmac(linkKey, ("verify:$payload").toByteArray()))

    private data class UserRow(val id: UUID, val passwordHash: String, val failedAttempts: Int, val lockedUntil: OffsetDateTime?)

    private companion object {
        const val MAX_FAILURES = 5
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val DUMMY_HASH: String = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("dummy-password")
    }
}
