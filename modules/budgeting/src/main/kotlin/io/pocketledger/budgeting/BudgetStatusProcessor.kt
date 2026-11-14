package io.pocketledger.budgeting

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.budgeting.domain.Budget
import io.pocketledger.events.BudgetClosed
import io.pocketledger.events.BudgetCreated
import io.pocketledger.events.BudgetLimitChanged
import io.pocketledger.events.Context
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import io.pocketledger.shared.BudgetMonths
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ports.FxConverter
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Consommation des budgets : chaque part de transaction contribue aux budgets de sa catégorie
 * (et de sa catégorie parente), convertie dans la devise du budget au taux de sa date (FR-96).
 * Une correction ou annulation remplace les contributions de la transaction : le calcul reste exact.
 * Les franchissements de 80 % et 100 % sont émis via l'agrégat Budget, qui les déduplique ;
 * rejouer ce processeur ne crée donc pas de doublon.
 */
@Component
class BudgetStatusProcessor(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val budgets: BudgetRepository,
    private val fx: FxConverter,
    private val preferences: UserPreferencesQuery,
) : EventProcessor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "budgeting-status"
    override val contexts = setOf(Context.LEDGER, Context.BUDGETING, Context.IDENTITY)

    override fun handles(eventType: String) = eventType in HANDLED

    override fun process(event: StoredEvent) {
        val userId = event.userId
        // Sérialise le traitement par utilisateur : budgets et transactions arrivent par des topics différents.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))").param("k", "budget:$userId").query { _, _ -> 1 }.list()
        when (val e = event.event) {
            is TransactionRecorded -> {
                upsertTx(userId, e.transactionId, e.valueDate, e.currency, e.parts, e.transferId != null)
                recompute(userId, e.transactionId)
            }
            is TransactionAmended -> {
                upsertTx(userId, e.transactionId, e.valueDate, e.currency, e.parts, null)
                recompute(userId, e.transactionId)
            }
            is TransactionRecategorized -> updateParts(userId, e.transactionId, e.parts)
            is TransactionSplit -> updateParts(userId, e.transactionId, e.parts)
            is TransactionVoided -> {
                jdbc.sql("UPDATE budget_tx SET voided = true WHERE transaction_id = :t").param("t", e.transactionId).update()
                recompute(userId, e.transactionId)
            }
            is BudgetCreated -> {
                jdbc
                    .sql(
                        """
                        INSERT INTO budget_view (budget_id, user_id, category_id, currency, rollover, start_month)
                        VALUES (:b, :u, :c, :cur, :r, :s) ON CONFLICT (budget_id) DO NOTHING
                        """.trimIndent(),
                    ).param("b", e.budgetId)
                    .param("u", userId)
                    .param("c", e.categoryId)
                    .param("cur", e.currency)
                    .param("r", e.rollover)
                    .param("s", e.startMonth)
                    .update()
                backfill(userId, e.budgetId)
            }
            is BudgetLimitChanged -> monthsWithSpending(e.budgetId).filter { it >= YearMonth.parse(e.effectiveMonth) }
                .forEach { checkThresholds(userId, e.budgetId, it) }
            is BudgetClosed ->
                jdbc.sql("UPDATE budget_view SET closed_from = :m WHERE budget_id = :b").param("m", e.effectiveMonth).param("b", e.budgetId).update()
            is UserKeyShredded -> resetUser(userId)
            else -> Unit
        }
    }

    private fun upsertTx(userId: UUID, txId: UUID, valueDate: LocalDate, currency: String, parts: List<TransactionPart>, transfer: Boolean?) {
        jdbc
            .sql(
                """
                INSERT INTO budget_tx (transaction_id, user_id, value_date, currency, parts, is_transfer, voided)
                VALUES (:t, :u, :d, :c, CAST(:p AS jsonb), COALESCE(CAST(:tr AS boolean), false), false)
                ON CONFLICT (transaction_id) DO UPDATE SET value_date = EXCLUDED.value_date, currency = EXCLUDED.currency,
                    parts = EXCLUDED.parts, is_transfer = COALESCE(CAST(:tr AS boolean), budget_tx.is_transfer)
                """.trimIndent(),
            ).param("t", txId)
            .param("u", userId)
            .param("d", valueDate)
            .param("c", currency)
            .param("p", mapper.writeValueAsString(parts))
            .param("tr", transfer)
            .update()
    }

    private fun updateParts(userId: UUID, txId: UUID, parts: List<TransactionPart>) {
        jdbc
            .sql("UPDATE budget_tx SET parts = CAST(:p AS jsonb) WHERE transaction_id = :t")
            .param("p", mapper.writeValueAsString(parts))
            .param("t", txId)
            .update()
        recompute(userId, txId)
    }

    /** Remplace les contributions d'une transaction puis vérifie les seuils des budgets concernés. */
    private fun recompute(userId: UUID, txId: UUID) {
        val affected = mutableSetOf<Pair<UUID, YearMonth>>()
        jdbc
            .sql("SELECT budget_id, month, amount_minor FROM budget_contribution WHERE transaction_id = :t")
            .param("t", txId)
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getLong(3)) }
            .list()
            .forEach { (budgetId, month, amount) ->
                addSpent(budgetId, month, -amount)
                affected += budgetId to YearMonth.parse(month)
            }
        jdbc.sql("DELETE FROM budget_contribution WHERE transaction_id = :t").param("t", txId).update()
        loadTx(txId)?.let { tx -> affected += contribute(userId, tx, null) }
        affected.forEach { (budgetId, month) -> checkThresholds(userId, budgetId, month) }
    }

    /** Un budget créé après des dépenses du mois les compte aussi. */
    private fun backfill(userId: UUID, budgetId: UUID) {
        val affected = mutableSetOf<Pair<UUID, YearMonth>>()
        jdbc
            .sql("SELECT transaction_id FROM budget_tx WHERE user_id = :u AND NOT voided AND NOT is_transfer")
            .param("u", userId)
            .query(UUID::class.java)
            .list()
            .forEach { txId -> loadTx(txId)?.let { affected += contribute(userId, it, budgetId) } }
        affected.forEach { (b, m) -> checkThresholds(userId, b, m) }
    }

    private fun contribute(userId: UUID, tx: TxRow, onlyBudget: UUID?): Set<Pair<UUID, YearMonth>> {
        if (tx.voided || tx.transfer) return emptySet()
        val month = BudgetMonths.of(tx.valueDate, preferences.budgetMonthStartDay(userId))
        val affected = mutableSetOf<Pair<UUID, YearMonth>>()
        tx.parts.forEachIndexed { index, part ->
            val category = part.categoryId ?: return@forEachIndexed
            matchingBudgets(userId, category, month).filter { onlyBudget == null || it.first == onlyBudget }.forEach { (budgetId, currency) ->
                // Dépense positive ; un remboursement (montant positif) réduit la consommation.
                val converted = fx.convert(-part.amountMinor, CurrencyCode(tx.currency), CurrencyCode(currency), tx.valueDate)
                if (converted == null) {
                    log.warn("Taux {}→{} indisponible au {} : contribution ignorée", tx.currency, currency, tx.valueDate)
                    return@forEach
                }
                val inserted =
                    jdbc
                        .sql(
                            "INSERT INTO budget_contribution (transaction_id, part_index, budget_id, month, amount_minor) " +
                                "VALUES (:t, :i, :b, :m, :a) ON CONFLICT DO NOTHING",
                        ).param("t", tx.id)
                        .param("i", index)
                        .param("b", budgetId)
                        .param("m", month.toString())
                        .param("a", converted)
                        .update()
                if (inserted > 0) {
                    addSpent(budgetId, month.toString(), converted)
                    affected += budgetId to month
                }
            }
        }
        return affected
    }

    private fun matchingBudgets(userId: UUID, categoryId: String, month: YearMonth): List<Pair<UUID, String>> =
        jdbc
            .sql(
                """
                SELECT budget_id, currency FROM budget_view
                WHERE user_id = :u AND (category_id = :c OR :c LIKE category_id || '.%')
                  AND start_month <= :m AND (closed_from IS NULL OR closed_from > :m)
                """.trimIndent(),
            ).param("u", userId)
            .param("c", categoryId)
            .param("m", month.toString())
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
            .list()

    private fun addSpent(budgetId: UUID, month: String, delta: Long) {
        jdbc
            .sql(
                """
                INSERT INTO budget_month (budget_id, month, spent_minor) VALUES (:b, :m, :d)
                ON CONFLICT (budget_id, month) DO UPDATE SET spent_minor = budget_month.spent_minor + EXCLUDED.spent_minor
                """.trimIndent(),
            ).param("b", budgetId)
            .param("m", month)
            .param("d", delta)
            .update()
    }

    private fun monthsWithSpending(budgetId: UUID): List<YearMonth> =
        jdbc
            .sql("SELECT month FROM budget_month WHERE budget_id = :b")
            .param("b", budgetId)
            .query(String::class.java)
            .list()
            .map(YearMonth::parse)

    private fun checkThresholds(userId: UUID, budgetId: UUID, month: YearMonth) {
        val budget = budgets.find(budgetId) ?: return
        if (!budget.isActive(month)) return
        val spentByMonth =
            jdbc
                .sql("SELECT month, spent_minor FROM budget_month WHERE budget_id = :b")
                .param("b", budgetId)
                .query { rs, _ -> YearMonth.parse(rs.getString(1)) to rs.getLong(2) }
                .list()
                .toMap()
        val limit = Budget.effectiveLimit(budget, month, spentByMonth)
        val spent = spentByMonth[month] ?: 0
        if (limit <= 0) return
        Budget.THRESHOLDS.filter { spent * 100 >= limit * it }.forEach { budget.crossThreshold(month, it, spent, limit) }
        budgets.save(budget, userId)
    }

    private fun loadTx(txId: UUID): TxRow? =
        jdbc
            .sql("SELECT transaction_id, value_date, currency, parts, is_transfer, voided FROM budget_tx WHERE transaction_id = :t")
            .param("t", txId)
            .query { rs, _ ->
                TxRow(
                    rs.getObject("transaction_id", UUID::class.java),
                    rs.getObject("value_date", LocalDate::class.java),
                    rs.getString("currency"),
                    mapper.readValue(rs.getString("parts"), Array<TransactionPart>::class.java).toList(),
                    rs.getBoolean("is_transfer"),
                    rs.getBoolean("voided"),
                )
            }.optional()
            .orElse(null)

    private class TxRow(
        val id: UUID,
        val valueDate: LocalDate,
        val currency: String,
        val parts: List<TransactionPart>,
        val transfer: Boolean,
        val voided: Boolean,
    )

    override fun reset() {
        jdbc.sql("TRUNCATE budget_contribution, budget_month, budget_tx, budget_view").update()
    }

    override fun resetUser(userId: UUID) {
        jdbc
            .sql("DELETE FROM budget_contribution WHERE transaction_id IN (SELECT transaction_id FROM budget_tx WHERE user_id = :u)")
            .param("u", userId)
            .update()
        jdbc.sql("DELETE FROM budget_month WHERE budget_id IN (SELECT budget_id FROM budget_view WHERE user_id = :u)").param("u", userId).update()
        jdbc.sql("DELETE FROM budget_tx WHERE user_id = :u").param("u", userId).update()
        jdbc.sql("DELETE FROM budget_view WHERE user_id = :u").param("u", userId).update()
    }

    private companion object {
        val HANDLED =
            setOf(
                "TransactionRecorded", "TransactionAmended", "TransactionRecategorized", "TransactionSplit", "TransactionVoided",
                "BudgetCreated", "BudgetLimitChanged", "BudgetClosed", "UserKeyShredded",
            )
    }
}
