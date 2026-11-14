package io.pocketledger.shared

import java.time.LocalDate
import java.time.YearMonth

/** Mois budgétaire, éventuellement décalé (jour de début de mois configurable, FR-04). */
object BudgetMonths {
    fun of(date: LocalDate, startDay: Int = 1): YearMonth {
        require(startDay in 1..28) { "Le jour de début de mois doit être entre 1 et 28" }
        val ym = YearMonth.from(date)
        return if (date.dayOfMonth >= startDay) ym else ym.minusMonths(1)
    }
}
