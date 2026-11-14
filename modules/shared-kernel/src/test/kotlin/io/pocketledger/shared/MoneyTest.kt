package io.pocketledger.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    private val eur = CurrencyCode("EUR")
    private val xof = CurrencyCode("XOF")
    private val kwd = CurrencyCode("KWD")

    @Test
    fun `convertit un décimal selon les décimales de la devise`() {
        Money.ofDecimal(BigDecimal("12.34"), eur, 2) shouldBe Money(1234, eur)
        Money.ofDecimal(BigDecimal("1500"), xof, 0) shouldBe Money(1500, xof)
        Money.ofDecimal(BigDecimal("1.250"), kwd, 3) shouldBe Money(1250, kwd)
        Money.ofDecimal(BigDecimal("-3.5"), eur, 2) shouldBe Money(-350, eur)
    }

    @Test
    fun `refuse une précision supérieure à celle de la devise`() {
        shouldThrow<ValidationException> { Money.ofDecimal(BigDecimal("12.345"), eur, 2) }
        shouldThrow<ValidationException> { Money.ofDecimal(BigDecimal("10.5"), xof, 0) }
    }

    @Test
    fun `refuse d'additionner deux devises`() {
        shouldThrow<CurrencyMismatchException> { Money(1, eur) + Money(1, xof) }
    }

    @Test
    fun `l'aller-retour décimal est exact`() = runBlocking<Unit> {
        checkAll(Arb.long(-1_000_000_000L, 1_000_000_000L)) { minor ->
            val m = Money(minor, eur)
            Money.ofDecimal(m.toDecimal(2), eur, 2) shouldBe m
        }
    }

    @Test
    fun `refuse un code devise mal formé`() {
        shouldThrow<IllegalArgumentException> { CurrencyCode("eur") }
        shouldThrow<IllegalArgumentException> { CurrencyCode("EURO") }
    }
}
