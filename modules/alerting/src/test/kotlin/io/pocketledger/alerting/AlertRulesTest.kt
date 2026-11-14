package io.pocketledger.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AlertRulesTest {
    private val usual = listOf(-2000L, -2200, -1800, -2100, -1900, -2000, -2050, -1950, -2150, -1850)

    @Test
    fun `dépense très au-dessus de l'habitude`() {
        AlertRules.isUnusual(-9000, usual) shouldBe true
    }

    @Test
    fun `dépense dans la norme`() {
        AlertRules.isUnusual(-2300, usual) shouldBe false
    }

    @Test
    fun `pas d'alerte avec moins de 10 points d'historique`() {
        AlertRules.isUnusual(-90_000, usual.take(9)) shouldBe false
    }

    @Test
    fun `solde bas seulement au franchissement`() {
        AlertRules.crossesBelow(15_000, 9_000, 10_000) shouldBe true
        AlertRules.crossesBelow(9_000, 8_000, 10_000) shouldBe false
        AlertRules.crossesBelow(15_000, 9_000, null) shouldBe false
    }
}
