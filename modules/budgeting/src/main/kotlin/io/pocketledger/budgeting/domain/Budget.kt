package io.pocketledger.budgeting.domain

import io.pocketledger.events.BudgetClosed
import io.pocketledger.events.BudgetCreated
import io.pocketledger.events.BudgetLimitChanged
import io.pocketledger.events.BudgetThresholdCrossed
import io.pocketledger.events.DomainEvent
import io.pocketledger.platform.eventstore.EventSourcedAggregate
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.ValidationException
import java.time.YearMonth
import java.util.UUID

data class BudgetState(
    val created: Boolean = false,
    val categoryId: String? = null,
    val currency: String? = null,
    val rollover: Boolean = false,
    val startMonth: String? = null,
    val closedFrom: String? = null,
    val limits: Map<String, Long> = emptyMap(), // mois d'effet → plafond
    val crossed: Set<String> = emptySet(), // "YYYY-MM:80"
)

/**
 * Budget mensuel par catégorie (FR-50 à FR-52), dans la devise de référence de l'utilisateur.
 * Changer le plafond ne réécrit jamais les mois passés : chaque plafond a un mois d'effet.
 */
class Budget(id: UUID) : EventSourcedAggregate<BudgetState>(id, BudgetState()) {
    fun create(categoryId: String, limitMinor: Long, currency: String, rollover: Boolean, startMonth: YearMonth) {
        if (state.created) throw ConflictException("budget-exists", "Budget déjà créé")
        requirePositive(limitMinor)
        raise(BudgetCreated(id, categoryId, limitMinor, currency, rollover, startMonth.toString()))
    }

    fun changeLimit(limitMinor: Long, effectiveMonth: YearMonth) {
        requireActive(effectiveMonth)
        requirePositive(limitMinor)
        if (effectiveMonth < YearMonth.parse(state.startMonth)) {
            throw ValidationException("before-start", "Le budget commence en ${state.startMonth}")
        }
        raise(BudgetLimitChanged(id, limitMinor, effectiveMonth.toString()))
    }

    fun close(effectiveMonth: YearMonth) {
        requireActive(effectiveMonth)
        raise(BudgetClosed(id, effectiveMonth.toString()))
    }

    /** Idempotent : un seuil n'est signalé qu'une fois par mois. */
    fun crossThreshold(month: YearMonth, percent: Int, spentMinor: Long, limitMinor: Long) {
        if ("$month:$percent" in state.crossed) return
        raise(BudgetThresholdCrossed(id, state.categoryId!!, month.toString(), percent, spentMinor, limitMinor, state.currency!!))
    }

    fun limitFor(month: YearMonth): Long? =
        state.limits
            .filterKeys { YearMonth.parse(it) <= month }
            .maxByOrNull { YearMonth.parse(it.key) }
            ?.value

    fun isActive(month: YearMonth): Boolean =
        state.created &&
            month >= YearMonth.parse(state.startMonth) &&
            (state.closedFrom == null || month < YearMonth.parse(state.closedFrom))

    private fun requireActive(month: YearMonth) {
        if (!state.created) throw ValidationException("budget-missing", "Budget inexistant")
        if (state.closedFrom != null && month >= YearMonth.parse(state.closedFrom)) {
            throw ConflictException("budget-closed", "Budget clos à partir de ${state.closedFrom}")
        }
    }

    private fun requirePositive(limitMinor: Long) {
        if (limitMinor <= 0) throw ValidationException("invalid-limit", "Le plafond doit être positif")
    }

    override fun evolve(state: BudgetState, event: DomainEvent): BudgetState =
        when (event) {
            is BudgetCreated ->
                state.copy(
                    created = true, categoryId = event.categoryId, currency = event.currency, rollover = event.rollover,
                    startMonth = event.startMonth, limits = mapOf(event.startMonth to event.limitMinor),
                )
            is BudgetLimitChanged -> state.copy(limits = state.limits + (event.effectiveMonth to event.limitMinor))
            is BudgetClosed -> state.copy(closedFrom = event.effectiveMonth)
            is BudgetThresholdCrossed -> state.copy(crossed = state.crossed + "${event.month}:${event.thresholdPercent}")
            else -> state
        }

    companion object {
        val THRESHOLDS = listOf(80, 100)

        /**
         * Plafond effectif avec report : plafond du mois + reste positif du mois précédent (lui-même reporté).
         * `spentByMonth` couvre les mois depuis le début du budget.
         */
        fun effectiveLimit(budget: Budget, month: YearMonth, spentByMonth: Map<YearMonth, Long>): Long {
            val base = budget.limitFor(month) ?: return 0
            if (!budget.state.rollover) return base
            val start = YearMonth.parse(budget.state.startMonth)
            var carry = 0L
            var m = start
            while (m < month) {
                val effective = (budget.limitFor(m) ?: 0) + carry
                carry = maxOf(0, effective - (spentByMonth[m] ?: 0))
                m = m.plusMonths(1)
            }
            return base + carry
        }
    }
}
