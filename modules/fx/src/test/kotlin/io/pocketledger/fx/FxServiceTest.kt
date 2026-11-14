package io.pocketledger.fx

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ports.CurrencyCatalog
import io.pocketledger.shared.ports.CurrencyInfo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FxServiceTest {
    private val date = LocalDate.of(2026, 11, 5)
    private val digits = mapOf("EUR" to 2, "USD" to 2, "XOF" to 0, "JPY" to 0, "KWD" to 3, "NGN" to 2)

    // Objet écrit à la main : MockK gère mal les paramètres de type value class (CurrencyCode).
    private val catalog =
        object : CurrencyCatalog {
            override fun isSupported(code: CurrencyCode) = code.value in digits

            override fun minorDigits(code: CurrencyCode) = digits.getValue(code.value)

            override fun all() = digits.map { (code, d) -> CurrencyInfo(code, d, code) }
        }
    private val rates =
        mockk<FxRateRepository> {
            every { find("USD", "EUR", any()) } returns BigDecimal("0.92")
            every { find("USD", "JPY", any()) } returns BigDecimal("150.25")
            every { find("USD", "KWD", any()) } returns BigDecimal("0.307")
            every { find("USD", "NGN", any()) } returns null
        }
    private val fx = FxService(rates, catalog, "USD")

    @Test
    fun `même devise - montant inchangé`() {
        fx.convert(1234, CurrencyCode("EUR"), CurrencyCode("EUR"), date) shouldBe 1234
    }

    @Test
    fun `parité fixe XOF-EUR sans fournisseur`() {
        // 655 957 XOF = 1 000,00 EUR
        fx.convert(655_957, CurrencyCode("XOF"), CurrencyCode("EUR"), date) shouldBe 100_000
        fx.convert(100_000, CurrencyCode("EUR"), CurrencyCode("XOF"), date) shouldBe 655_957
    }

    @Test
    fun `conversion croisée via le pivot avec arrondi aux décimales cibles`() {
        // 10,00 EUR → USD = 10 / 0.92 = 10.869… → 10,87
        fx.convert(1000, CurrencyCode("EUR"), CurrencyCode("USD"), date) shouldBe 1087
        // 10,00 USD → JPY = 1502.5 → 1502 (HALF_EVEN)
        fx.convert(1000, CurrencyCode("USD"), CurrencyCode("JPY"), date) shouldBe 1502
        // 10,00 USD → KWD = 3.070 → 3070 millièmes
        fx.convert(1000, CurrencyCode("USD"), CurrencyCode("KWD"), date) shouldBe 3070
    }

    @Test
    fun `XOF vers USD passe par l'EUR`() {
        // 655 957 XOF = 1000 EUR = 1086,96 USD
        fx.convert(655_957, CurrencyCode("XOF"), CurrencyCode("USD"), date) shouldBe 108_696
    }

    @Test
    fun `taux manquant - null plutôt qu'un montant faux`() {
        fx.convert(1000, CurrencyCode("USD"), CurrencyCode("NGN"), date) shouldBe null
    }
}
