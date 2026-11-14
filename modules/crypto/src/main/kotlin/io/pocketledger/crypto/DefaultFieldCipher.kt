package io.pocketledger.crypto

import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.Text
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.UUID

/**
 * Chiffrement de champs : "enc:v1:<keyId>:<base64url(iv || ct)>".
 * Le préfixe versionné et l'identifiant de clé permettent la rotation.
 */
@Component
class DefaultFieldCipher(private val keys: UserKeyService) : FieldCipher {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun encrypt(userId: UUID, plaintext: String): String {
        val key = requireKey(userId)
        val ct = AesGcm.encrypt(key.dek, plaintext.toByteArray(), aad = userId.toString().toByteArray())
        return "$PREFIX${key.keyId}:${encoder.encodeToString(ct)}"
    }

    override fun decrypt(userId: UUID, ciphertext: String): String? {
        if (!ciphertext.startsWith(PREFIX)) return ciphertext
        val parts = ciphertext.removePrefix(PREFIX).split(":", limit = 2)
        if (parts.size != 2) return null
        val key = keys.key(userId) ?: return null
        if (key.keyId.toString() != parts[0]) return null
        return runCatching {
            String(AesGcm.decrypt(key.dek, decoder.decode(parts[1]), aad = userId.toString().toByteArray()))
        }.getOrNull()
    }

    override fun encryptBytes(userId: UUID, plaintext: ByteArray): ByteArray =
        AesGcm.encrypt(requireKey(userId).dek, plaintext, aad = userId.toString().toByteArray())

    override fun decryptBytes(userId: UUID, ciphertext: ByteArray): ByteArray? {
        val key = keys.key(userId) ?: return null
        return runCatching { AesGcm.decrypt(key.dek, ciphertext, aad = userId.toString().toByteArray()) }.getOrNull()
    }

    override fun blindIndex(userId: UUID, value: String): String {
        val indexKey = AesGcm.hmac(requireKey(userId).dek, "blind-index".toByteArray())
        return encoder.encodeToString(AesGcm.hmac(indexKey, normalize(value).toByteArray()).copyOf(16))
    }

    override fun blindTokens(userId: UUID, text: String): List<String> =
        tokenize(text).map { blindIndex(userId, it) }

    private fun requireKey(userId: UUID): UserKey =
        keys.key(userId) ?: throw ConflictException("user-key-missing", "Clé de chiffrement absente pour cet utilisateur")

    companion object {
        private const val PREFIX = "enc:v1:"

        fun normalize(value: String): String = Text.normalize(value)

        /** Mots normalisés (≥ 2 caractères, 20 max) : base de la recherche approximative par index aveugles. */
        fun tokenize(text: String): List<String> =
            normalize(text)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 2 }
                .distinct()
                .take(20)
    }
}

/** Index aveugle global (email au login), avec une clé distincte des DEK utilisateur. */
@Component
class GlobalBlindIndex(
    @Value("\${pocketledger.crypto.blind-index-key}") keyBase64: String,
) {
    private val key = Base64.getDecoder().decode(keyBase64.trim())

    fun of(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            AesGcm.hmac(key, DefaultFieldCipher.normalize(value).toByteArray()),
        )
}
