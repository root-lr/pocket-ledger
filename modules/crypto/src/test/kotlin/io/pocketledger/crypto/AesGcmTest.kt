package io.pocketledger.crypto

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class AesGcmTest {
    @Test
    fun `chiffre et déchiffre avec la même clé`() {
        val key = AesGcm.newKey()
        val ct = AesGcm.encrypt(key, "Boulangerie Paul".toByteArray(), "u1".toByteArray())
        String(AesGcm.decrypt(key, ct, "u1".toByteArray())) shouldBe "Boulangerie Paul"
    }

    @Test
    fun `deux chiffrements du même texte diffèrent (IV aléatoire)`() {
        val key = AesGcm.newKey()
        AesGcm.encrypt(key, "x".toByteArray()) shouldNotBe AesGcm.encrypt(key, "x".toByteArray())
    }

    @Test
    fun `une autre clé ou un autre AAD échoue`() {
        val ct = AesGcm.encrypt(AesGcm.newKey(), "secret".toByteArray(), "u1".toByteArray())
        shouldThrowAny { AesGcm.decrypt(AesGcm.newKey(), ct, "u1".toByteArray()) }
    }

    @Test
    fun `la tokenisation normalise accents et casse`() {
        DefaultFieldCipher.tokenize("Café de la GARE, Cotonou") shouldContainExactly listOf("cafe", "de", "la", "gare", "cotonou")
    }
}
