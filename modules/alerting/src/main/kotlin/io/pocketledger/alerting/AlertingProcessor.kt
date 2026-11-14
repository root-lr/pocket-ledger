package io.pocketledger.alerting

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.AlertRaised
import io.pocketledger.events.AlertTypes
import io.pocketledger.events.BudgetThresholdCrossed
import io.pocketledger.events.Context
import io.pocketledger.events.LowBalanceThresholdSet
import io.pocketledger.events.RecurringPaymentChanged
import io.pocketledger.events.RecurringPaymentDetected
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.EventContext
import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs

/**
 * Évalue les règles d'alerte sur le flux d'événements (§3.8 du PRD) et émet AlertRaised.
 * Chaque alerte a une clé de déduplication unique : un événement retraité ne crée pas de doublon.
 * Process manager : il émet des événements, il n'est pas rejoué lors d'un rebuild.
 */
@Component
class AlertingProcessor(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val eventStore: EventStore,
    private val preferences: UserPreferencesQuery,
    private val money: MoneyFormat,
) : EventProcessor {
    override val name = "alerting"
    override val contexts = setOf(Context.LEDGER, Context.BUDGETING, Context.CATEGORIZATION, Context.IDENTITY)
    override val rebuildable = false

    override fun handles(eventType: String) = eventType in HANDLED

    override fun process(event: StoredEvent) {
        val userId = event.userId
        when (val e = event.event) {
            is AccountOpened ->
                jdbc
                    .sql(
                        "INSERT INTO alert_balance (account_id, user_id, currency, balance_minor) VALUES (:a, :u, :c, :b) " +
                            "ON CONFLICT DO NOTHING",
                    ).param("a", e.accountId)
                    .param("u", userId)
                    .param("c", e.currency)
                    .param("b", e.openingBalanceMinor)
                    .update()
            is LowBalanceThresholdSet ->
                jdbc
                    .sql("UPDATE alert_balance SET threshold_minor = :t WHERE account_id = :a")
                    .param("t", e.thresholdMinor)
                    .param("a", e.accountId)
                    .update()
            is TransactionRecorded -> onRecorded(event, e)
            is TransactionAmended -> {
                applyBalance(userId, e.accountId, e.amountMinor - e.previousAmountMinor, event)
                jdbc
                    .sql("UPDATE alert_tx SET amount_minor = :amt, value_date = :d, merchant_hash = :mh, category_id = :c WHERE transaction_id = :t")
                    .param("amt", e.amountMinor)
                    .param("d", e.valueDate)
                    .param("mh", e.merchantHash)
                    .param("c", e.parts.firstOrNull()?.categoryId)
                    .param("t", e.transactionId)
                    .update()
            }
            is TransactionVoided -> {
                applyBalance(userId, e.accountId, -e.amountMinor, event)
                jdbc.sql("UPDATE alert_tx SET voided = true WHERE transaction_id = :t").param("t", e.transactionId).update()
            }
            is TransactionRecategorized -> recategorize(e.transactionId, e.parts.firstOrNull()?.categoryId)
            is TransactionSplit -> recategorize(e.transactionId, e.parts.firstOrNull()?.categoryId)
            is BudgetThresholdCrossed ->
                raise(
                    userId, AlertTypes.BUDGET_THRESHOLD, "budget:${e.budgetId}:${e.month}:${e.thresholdPercent}",
                    mapOf(
                        "budgetId" to e.budgetId.toString(), "categoryId" to e.categoryId, "month" to e.month,
                        "thresholdPercent" to e.thresholdPercent.toString(),
                        "spent" to money.dto(e.spentMinor, e.currency).amount, "limit" to money.dto(e.limitMinor, e.currency).amount,
                        "currency" to e.currency,
                    ),
                )
            is RecurringPaymentDetected ->
                raise(
                    userId, AlertTypes.RECURRING_PAYMENT, "recurring:${e.seriesId}:detected",
                    mapOf(
                        "kind" to "detected", "seriesId" to e.seriesId.toString(), "merchantEnc" to e.merchant,
                        "amount" to money.dto(-e.amountMinor, e.currency).amount, "currency" to e.currency,
                        "nextExpectedOn" to e.nextExpectedOn.toString(),
                    ),
                )
            is RecurringPaymentChanged ->
                raise(
                    userId, AlertTypes.RECURRING_PAYMENT, "recurring:${e.seriesId}:${e.amountMinor}",
                    mapOf(
                        "kind" to "increased", "seriesId" to e.seriesId.toString(), "merchantEnc" to e.merchant,
                        "previous" to money.dto(-e.previousAmountMinor, e.currency).amount,
                        "amount" to money.dto(-e.amountMinor, e.currency).amount, "currency" to e.currency,
                    ),
                )
            is UserKeyShredded -> resetUser(userId)
            else -> Unit
        }
    }

    private fun onRecorded(event: StoredEvent, e: TransactionRecorded) {
        val userId = event.userId
        applyBalance(userId, e.accountId, e.amountMinor, event)
        val category = e.parts.firstOrNull()?.categoryId
        if (e.amountMinor < 0 && e.transferId == null) {
            e.merchantHash?.let { merchantHash ->
                val duplicate =
                    jdbc
                        .sql(
                            """
                            SELECT transaction_id FROM alert_tx
                            WHERE account_id = :a AND amount_minor = :amt AND merchant_hash = :m AND NOT voided
                              AND transaction_id <> :t AND recorded_at > :since AND abs(value_date - CAST(:d AS date)) <= 1
                            LIMIT 1
                            """.trimIndent(),
                        ).param("a", e.accountId)
                        .param("amt", e.amountMinor)
                        .param("m", merchantHash)
                        .param("t", e.transactionId)
                        .param("since", at(event).minusHours(24))
                        .param("d", e.valueDate)
                        .query(UUID::class.java)
                        .optional()
                        .orElse(null)
                if (duplicate != null) {
                    raise(
                        userId, AlertTypes.PROBABLE_DUPLICATE, "dup:${e.transactionId}",
                        mapOf(
                            "transactionId" to e.transactionId.toString(), "duplicateOf" to duplicate.toString(),
                            "amount" to money.dto(-e.amountMinor, e.currency).amount, "currency" to e.currency,
                        ),
                    )
                }
            }
            if (category != null) {
                val history =
                    jdbc
                        .sql(
                            """
                            SELECT amount_minor FROM alert_tx
                            WHERE user_id = :u AND category_id = :c AND currency = :cur AND amount_minor < 0 AND NOT voided
                              AND value_date >= CAST(:d AS date) - 90 AND value_date <= CAST(:d AS date) AND transaction_id <> :t
                            """.trimIndent(),
                        ).param("u", userId)
                        .param("c", category)
                        .param("cur", e.currency)
                        .param("d", e.valueDate)
                        .param("t", e.transactionId)
                        .query(Long::class.javaObjectType)
                        .list()
                if (AlertRules.isUnusual(e.amountMinor, history)) {
                    raise(
                        userId, AlertTypes.UNUSUAL_SPENDING, "unusual:${e.transactionId}",
                        mapOf(
                            "transactionId" to e.transactionId.toString(), "categoryId" to category,
                            "amount" to money.dto(abs(e.amountMinor), e.currency).amount, "currency" to e.currency,
                            "averageOver90Days" to money.dto(history.map { abs(it) }.average().toLong(), e.currency).amount,
                        ),
                    )
                }
            }
        }
        jdbc
            .sql(
                """
                INSERT INTO alert_tx (transaction_id, user_id, account_id, category_id, amount_minor, currency, merchant_hash,
                    value_date, recorded_at, voided)
                VALUES (:t, :u, :a, :c, :amt, :cur, :m, :d, :at, false) ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("t", e.transactionId)
            .param("u", userId)
            .param("a", e.accountId)
            .param("c", category)
            .param("amt", e.amountMinor)
            .param("cur", e.currency)
            .param("m", e.merchantHash)
            .param("d", e.valueDate)
            .param("at", at(event))
            .update()
    }

    private fun applyBalance(userId: UUID, accountId: UUID, delta: Long, event: StoredEvent) {
        val row =
            jdbc
                .sql("SELECT balance_minor, threshold_minor, currency FROM alert_balance WHERE account_id = :a FOR UPDATE")
                .param("a", accountId)
                .query { rs, _ -> Triple(rs.getLong(1), rs.getObject(2)?.let { (it as Number).toLong() }, rs.getString(3)) }
                .optional()
                .orElse(null) ?: return
        val (previous, threshold, currency) = row
        val next = previous + delta
        jdbc.sql("UPDATE alert_balance SET balance_minor = :b WHERE account_id = :a").param("b", next).param("a", accountId).update()
        if (AlertRules.crossesBelow(previous, next, threshold)) {
            raise(
                userId, AlertTypes.LOW_BALANCE, "low:$accountId:${event.eventId}",
                mapOf(
                    "accountId" to accountId.toString(), "balance" to money.dto(next, currency).amount,
                    "threshold" to money.dto(threshold!!, currency).amount, "currency" to currency,
                ),
            )
        }
    }

    private fun recategorize(transactionId: UUID, categoryId: String?) {
        jdbc.sql("UPDATE alert_tx SET category_id = :c WHERE transaction_id = :t").param("c", categoryId).param("t", transactionId).update()
    }

    private fun raise(userId: UUID, type: String, dedupeKey: String, details: Map<String, String?>) {
        if (!preferences.isAlertEnabled(userId, type)) return
        val alertId = UUID.randomUUID()
        val clean = details.filterValues { it != null }.mapValues { it.value!! }
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO alert (id, user_id, type, dedupe_key, details, status, created_at)
                    VALUES (:id, :u, :t, :k, CAST(:d AS jsonb), 'NEW', now()) ON CONFLICT (dedupe_key) DO NOTHING
                    """.trimIndent(),
                ).param("id", alertId)
                .param("u", userId)
                .param("t", type)
                .param("k", "$userId:$dedupeKey")
                .param("d", mapper.writeValueAsString(clean))
                .update()
        if (inserted == 0) return
        val stored =
            eventStore.append(
                "Alert", Context.ALERTS, alertId, 0, userId,
                listOf(AlertRaised(alertId, type, dedupeKey, clean)), EventContext.current(),
            )
        jdbc.sql("UPDATE alert SET position = :p WHERE id = :id").param("p", stored.last().globalPosition).param("id", alertId).update()
    }

    override fun resetUser(userId: UUID) {
        listOf("alert", "alert_tx", "alert_balance").forEach {
            jdbc.sql("DELETE FROM $it WHERE user_id = :u").param("u", userId).update()
        }
    }

    private fun at(event: StoredEvent): OffsetDateTime = OffsetDateTime.ofInstant(event.occurredAt, ZoneOffset.UTC)

    private companion object {
        val HANDLED =
            setOf(
                "AccountOpened", "LowBalanceThresholdSet", "TransactionRecorded", "TransactionAmended", "TransactionVoided",
                "TransactionRecategorized", "TransactionSplit", "BudgetThresholdCrossed", "RecurringPaymentDetected",
                "RecurringPaymentChanged", "UserKeyShredded",
            )
    }
}
