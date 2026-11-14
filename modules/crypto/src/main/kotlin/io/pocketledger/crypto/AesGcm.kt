package io.pocketledger.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM : sortie = IV (12 octets) || texte chiffré + tag (16 octets). */
object AesGcm {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, payload: ByteArray, aad: ByteArray? = null): ByteArray {
        require(payload.size > IV_BYTES) { "Payload chiffré trop court" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size))
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
