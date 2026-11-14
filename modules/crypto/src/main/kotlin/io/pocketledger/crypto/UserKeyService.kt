package io.pocketledger.crypto

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.UUID

class UserKey(val keyId: UUID, val dek: ByteArray)

/**
 * Envelope encryption : une DEK par utilisateur, stockée chiffrée par la KEK maître.
 * Sur la VM de démo, la KEK vient d'un Docker secret ; AWS KMS pourra la remplacer
 * en implémentant le même wrap/unwrap.
 */
@Service
class UserKeyService(
    private val jdbc: JdbcClient,
    @Value("\${pocketledger.crypto.master-key}") masterKeyBase64: String,
) {
    private val kek: ByteArray = Base64.getDecoder().decode(masterKeyBase64.trim())

    // DEK déchiffrées en mémoire 5 minutes, jamais dans Redis.
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(50_000)
            .build<UUID, Optional<UserKey>>()

    init {
        require(kek.size == 32) { "pocketledger.crypto.master-key doit contenir 32 octets encodés en base64" }
    }

    fun createKey(userId: UUID): UserKey {
        key(userId)?.let { return it }
        val dek = AesGcm.newKey()
        val keyId = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO user_keys (user_id, key_id, wrapped_dek, created_at) VALUES (:u, :k, :w, now()) " +
                    "ON CONFLICT (user_id) DO NOTHING",
            ).param("u", userId)
            .param("k", keyId)
            .param("w", AesGcm.encrypt(kek, dek, aad = userId.toString().toByteArray()))
            .update()
        cache.invalidate(userId)
        return key(userId) ?: error("Clé utilisateur non créée pour $userId")
    }

    /** null si l'utilisateur n'a pas (ou plus) de clé. */
    fun key(userId: UUID): UserKey? =
        cache
            .get(userId) {
                Optional.ofNullable(
                    jdbc
                        .sql("SELECT key_id, wrapped_dek FROM user_keys WHERE user_id = :u")
                        .param("u", userId)
                        .query { rs, _ ->
                            UserKey(
                                rs.getObject("key_id", UUID::class.java),
                                AesGcm.decrypt(kek, rs.getBytes("wrapped_dek"), aad = userId.toString().toByteArray()),
                            )
                        }.optional()
                        .orElse(null),
                )
            }!!
            .orElse(null)

    /** Crypto-shredding : la DEK disparaît, tout ce qu'elle chiffrait devient illisible. */
    fun destroy(userId: UUID) {
        jdbc.sql("DELETE FROM user_keys WHERE user_id = :u").param("u", userId).update()
        cache.invalidate(userId)
    }
}
