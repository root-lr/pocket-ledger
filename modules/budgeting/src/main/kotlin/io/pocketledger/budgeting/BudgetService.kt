package io.pocketledger.budgeting

import io.pocketledger.budgeting.domain.Budget
import io.pocketledger.budgeting.domain.BudgetState
import io.pocketledger.events.Context
import io.pocketledger.platform.eventstore.AggregateRepositories
import io.pocketledger.platform.eventstore.AggregateRepository
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.platform.web.MoneyDto
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.BudgetMonths
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CategoryDirectory
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

typealias BudgetRepository = AggregateRepository<Budget, BudgetState>

@Configuration
class BudgetingRepositories {
    @Bean
    fun budgetRepository(factory: AggregateRepositories): BudgetRepository =
        factory.create("Budget", Context.BUDGETING, BudgetState::class.java) { Budget(it) }
}

data class CreateBudgetCommand(val categoryId: String, val limit: String, val rollover: Boolean, val startMonth: YearMonth?)

data class BudgetRef(val id: UUID, val version: Int)

@Service
class BudgetService(
    private val budgets: BudgetRepository,
    private val jdbc: JdbcClient,
    private val categories: CategoryDirectory,
    private val preferences: UserPreferencesQuery,
    private val money: MoneyFormat,
    private val commands: CommandExecutor,
    private val clock: Clock,
) {
    fun create(userId: UUID, command: CreateBudgetCommand): BudgetRef =
        commands.execute {
            if (!categories.exists(userId, command.categoryId)) {
                throw ValidationException("unknown-category", "Catégorie inconnue : ${command.categoryId}")
            }
            // FR-96 : le budget est dans la devise de référence de l'utilisateur au moment de sa création.
            val currency = preferences.referenceCurrency(userId)
            val limit = money.parse(command.limit, currency.value)
            val budget = Budget(UUID.randomUUID())
            budget.create(command.categoryId, limit.amountMinor, currency.value, command.rollover, command.startMonth ?: currentMonth(userId))
            budgets.save(budget, userId)
            jdbc
                .sql("INSERT INTO budget_owner (budget_id, user_id) VALUES (:b, :u)")
                .param("b", budget.id)
                .param("u", userId)
                .update()
            BudgetRef(budget.id, budget.version)
        }

    fun changeLimit(userId: UUID, budgetId: UUID, limit: String, effectiveMonth: YearMonth?): BudgetRef =
        mutate(userId, budgetId) { budget ->
            budget.changeLimit(money.parse(limit, budget.state.currency!!).amountMinor, effectiveMonth ?: currentMonth(userId))
        }

    fun close(userId: UUID, budgetId: UUID, effectiveMonth: YearMonth?): BudgetRef =
        mutate(userId, budgetId) { it.close(effectiveMonth ?: currentMonth(userId).plusMonths(1)) }

    private fun mutate(userId: UUID, budgetId: UUID, change: (Budget) -> Unit): BudgetRef =
        commands.execute {
            requireOwner(userId, budgetId)
            val budget = budgets.get(budgetId)
            change(budget)
            budgets.save(budget, userId)
            BudgetRef(budget.id, budget.version)
        }

    fun requireOwner(userId: UUID, budgetId: UUID) {
        val owner =
            jdbc
                .sql("SELECT user_id FROM budget_owner WHERE budget_id = :b")
                .param("b", budgetId)
                .query(UUID::class.java)
                .optional()
                .orElse(null)
        if (owner != userId) throw NotFoundException("Budget", budgetId)
    }

    fun currentMonth(userId: UUID): YearMonth = BudgetMonths.of(LocalDate.now(clock), preferences.budgetMonthStartDay(userId))
}

data class BudgetStatusDto(
    val budgetId: UUID,
    val categoryId: String,
    val month: String,
    val limit: MoneyDto,
    val effectiveLimit: MoneyDto,
    val spent: MoneyDto,
    val remaining: MoneyDto,
    val percentUsed: Int,
    val projectedEndOfMonth: MoneyDto?,
    val rollover: Boolean,
)

data class BudgetDto(
    val id: UUID,
    val categoryId: String,
    val currency: String,
    val rollover: Boolean,
    val startMonth: String,
    val closedFrom: String?,
    val limits: Map<String, MoneyDto>,
)

@Service
class BudgetQueries(
    private val jdbc: JdbcClient,
    private val budgets: BudgetRepository,
    private val service: BudgetService,
    private val money: MoneyFormat,
    private val preferences: UserPreferencesQuery,
    private val clock: Clock,
) {
    fun list(userId: UUID): List<BudgetDto> =
        jdbc
            .sql("SELECT budget_id FROM budget_owner WHERE user_id = :u")
            .param("u", userId)
            .query(UUID::class.java)
            .list()
            .mapNotNull { budgets.find(it) }
            .map { dto(it) }

    fun get(userId: UUID, budgetId: UUID): BudgetDto {
        service.requireOwner(userId, budgetId)
        return dto(budgets.get(budgetId))
    }

    /** FR-51 : dépensé, restant, % consommé et projection de fin de mois au rythme actuel. */
    fun status(userId: UUID, month: YearMonth?): List<BudgetStatusDto> {
        val target = month ?: service.currentMonth(userId)
        val current = service.currentMonth(userId)
        return list(userId).mapNotNull { dto ->
            val budget = budgets.get(dto.id)
            if (!budget.isActive(target)) return@mapNotNull null
            val spentByMonth = spentByMonth(dto.id)
            val spent = spentByMonth[target] ?: 0
            val base = budget.limitFor(target) ?: 0
            val effective = Budget.effectiveLimit(budget, target, spentByMonth)
            val currency = dto.currency
            val projected =
                if (target == current) {
                    val startDay = preferences.budgetMonthStartDay(userId)
                    val periodStart = target.atDay(startDay)
                    val days = java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodStart.plusMonths(1))
                    val elapsed = (java.time.temporal.ChronoUnit.DAYS.between(periodStart, LocalDate.now(clock)) + 1).coerceIn(1, days)
                    money.dto(spent * days / elapsed, currency)
                } else {
                    null
                }
            BudgetStatusDto(
                dto.id, dto.categoryId, target.toString(), money.dto(base, currency), money.dto(effective, currency),
                money.dto(spent, currency), money.dto(effective - spent, currency),
                if (effective > 0) (spent * 100 / effective).toInt() else 0, projected, dto.rollover,
            )
        }
    }

    private fun spentByMonth(budgetId: UUID): Map<YearMonth, Long> =
        jdbc
            .sql("SELECT month, spent_minor FROM budget_month WHERE budget_id = :b")
            .param("b", budgetId)
            .query { rs, _ -> YearMonth.parse(rs.getString("month")) to rs.getLong("spent_minor") }
            .list()
            .toMap()

    private fun dto(budget: Budget): BudgetDto {
        val s = budget.state
        return BudgetDto(
            budget.id, s.categoryId!!, s.currency!!, s.rollover, s.startMonth!!, s.closedFrom,
            s.limits.mapValues { money.dto(it.value, s.currency) },
        )
    }
}
