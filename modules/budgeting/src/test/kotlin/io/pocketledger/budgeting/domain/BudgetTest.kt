package io.pocketledger.budgeting.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.pocketledger.events.BudgetThresholdCrossed
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.ValidationException
import org.junit.jupiter.api.Test
import java.time.YearMonth
import java.util.UUID

class BudgetTest {
    private val sep = YearMonth.of(2026, 9)
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    private fun budget(limit: Long = 40_000, rollover: Boolean = false): Budget =
        Budget(UUID.randomUUID()).apply {
            create("food", limit, "EUR", rollover, sep)
            markCommitted()
        }

    @Test
    fun `changer le plafond ne réécrit pas les mois passés`() {
        val b = budget()
        b.changeLimit(50_000, nov)
        b.limitFor(sep) shouldBe 40_000
        b.limitFor(oct) shouldBe 40_000
        b.limitFor(nov) shouldBe 50_000
        b.limitFor(YearMonth.of(2026, 8)) shouldBe null
    }

    @Test
    fun `un seuil n'est signalé qu'une fois par mois`() {
        val b = budget()
        b.crossThreshold(oct, 80, 33_000, 40_000)
        b.crossThreshold(oct, 80, 35_000, 40_000)
        b.crossThreshold(nov, 80, 33_000, 40_000)
        b.pendingEvents.filterIsInstance<BudgetThresholdCrossed>() shouldHaveSize 2
    }

    @Test
    fun `report du reste positif sur les mois suivants`() {
        val b = budget(rollover = true)
        // septembre : 300 € dépensés sur 400 → 100 reportés ; octobre : 400 + 100 = 500, 550 dépensés → rien reporté
        val spent = mapOf(sep to 30_000L, oct to 55_000L)
        Budget.effectiveLimit(b, sep, spent) shouldBe 40_000
        Budget.effectiveLimit(b, oct, spent) shouldBe 50_000
        Budget.effectiveLimit(b, nov, spent) shouldBe 40_000
    }

    @Test
    fun `sans report le plafond effectif est le plafond du mois`() {
        Budget.effectiveLimit(budget(), oct, mapOf(sep to 0L)) shouldBe 40_000
    }

    @Test
    fun `un budget clos refuse les changements et devient inactif`() {
        val b = budget()
        b.close(nov)
        b.isActive(oct) shouldBe true
        b.isActive(nov) shouldBe false
        shouldThrow<ConflictException> { b.changeLimit(10_000, nov) }
    }

    @Test
    fun `plafond positif obligatoire`() {
        shouldThrow<ValidationException> { Budget(UUID.randomUUID()).create("food", 0, "EUR", false, sep) }
    }
}
