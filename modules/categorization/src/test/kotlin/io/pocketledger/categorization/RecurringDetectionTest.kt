package io.pocketledger.categorization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RecurringDetectionTest {
    private fun occ(date: String, amount: Long) = Occurrence(LocalDate.parse(date), amount)

    @Test
    fun `trois prélèvements mensuels proches sont un abonnement`() {
        RecurringDetection.isRecurring(
            listOf(occ("2026-08-03", -1399), occ("2026-09-02", -1399), occ("2026-10-02", -1450)),
        ) shouldBe true
    }

    @Test
    fun `moins de trois occurrences ne suffit pas`() {
        RecurringDetection.isRecurring(listOf(occ("2026-09-02", -1399), occ("2026-10-02", -1399))) shouldBe false
    }

    @Test
    fun `intervalle hors 28-31 jours refusé`() {
        RecurringDetection.isRecurring(
            listOf(occ("2026-08-01", -1000), occ("2026-08-15", -1000), occ("2026-09-15", -1000)),
        ) shouldBe false
    }

    @Test
    fun `montants trop dispersés refusés`() {
        RecurringDetection.isRecurring(
            listOf(occ("2026-08-03", -1000), occ("2026-09-02", -1500), occ("2026-10-02", -1000)),
        ) shouldBe false
    }

    @Test
    fun `hausse de plus de 5 pourcent`() {
        RecurringDetection.isIncrease(-1399, -1499) shouldBe true
        RecurringDetection.isIncrease(-1399, -1450) shouldBe false
    }
}
