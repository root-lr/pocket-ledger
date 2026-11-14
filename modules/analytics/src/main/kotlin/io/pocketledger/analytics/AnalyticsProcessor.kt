package io.pocketledger.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.Context
import io.pocketledger.events.RecurringPaymentChanged
import io.pocketledger.events.RecurringPaymentDetected
import io.pocketledger.events.ReferenceCurrencyChanged
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ports.FxConverter
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Projection analytics : chaque part de transaction est stockée avec son montant d'origine
 * et sa conversion dans la devise de référence au taux de sa date (FR-93).
 * Conversion impossible → amount_ref_minor NULL (« non converti »), jamais un montant faux.
 * Changement de devise de référence → recalcul des conversions de l'utilisateur (FR-97).
 */
@Component
class AnalyticsProcessor(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val fx: FxConverter,
    private val preferences: UserPreferencesQuery,
    private val cache: AnalyticsCache,
) : EventProcessor {
    override val name = "analytics"
    override val contexts = setOf(Context.LEDGER, Context.CATEGORIZATION, Context.IDENTITY)

    override fun handles(eventType: String) = eventType in HANDLED

    override fun process(event: StoredEvent) {
        val userId = event.userId
        when (val e = event.event) {
            is AccountOpened ->
                jdbc
                    .sql(
                        "INSERT INTO analytics_account (account_id, user_id, currency, opening_minor, opened_on) " +
                            "VALUES (:a, :u, :c, :o, :d) ON CONFLICT DO NOTHING",
                    ).param("a", e.accountId)
                    .param("u", userId)
                    .param("c", e.currency)
                    .param("o", e.openingBalanceMinor)
                    .param("d", e.openedOn)
                    .update()
            is TransactionRecorded -> {
                jdbc
                    .sql(
                        """
                        INSERT INTO analytics_tx (transaction_id, user_id, account_id, value_date, currency, amount_minor,
                            merchant_hash, merchant_enc, parts, is_transfer, voided)
                        VALUES (:t, :u, :a, :d, :c, :amt, :mh, :m, CAST(:p AS jsonb), :tr, false)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """.trimIndent(),
                    ).param("t", e.transactionId)
                    .param("u", userId)
                    .param("a", e.accountId)
                    .param("d", e.valueDate)
                    .param("c", e.currency)
                    .param("amt", e.amountMinor)
                    .param("mh", e.merchantHash)
                    .param("m", e.merchant)
                    .param("p", mapper.writeValueAsString(e.parts))
                    .param("tr", e.transferId != null)
                    .update()
                refreshParts(e.transactionId, preferences.referenceCurrency(userId).value)
            }
            is TransactionAmended -> {
                jdbc
                    .sql(
                        """
                        UPDATE analytics_tx SET value_date = :d, amount_minor = :amt, merchant_hash = :mh, merchant_enc = :m,
                            parts = CAST(:p AS jsonb) WHERE transaction_id = :t
                        """.trimIndent(),
                    ).param("d", e.valueDate)
                    .param("amt", e.amountMinor)
                    .param("mh", e.merchantHash)
                    .param("m", e.merchant)
                    .param("p", mapper.writeValueAsString(e.parts))
                    .param("t", e.transactionId)
                    .update()
                refreshParts(e.transactionId, preferences.referenceCurrency(userId).value)
            }
            is TransactionRecategorized -> updateParts(userId, e.transactionId, e.parts)
            is TransactionSplit -> updateParts(userId, e.transactionId, e.parts)
            is TransactionVoided -> {
                jdbc.sql("UPDATE analytics_tx SET voided = true WHERE transaction_id = :t").param("t", e.transactionId).update()
                refreshParts(e.transactionId, preferences.referenceCurrency(userId).value)
            }
            is RecurringPaymentDetected ->
                jdbc
                    .sql(
                        """
                        INSERT INTO analytics_recurring (series_id, user_id, account_id, merchant_enc, amount_minor, currency,
                            interval_days, next_expected_on, updated_at)
                        VALUES (:s, :u, :a, :m, :amt, :c, :i, :n, now()) ON CONFLICT (series_id) DO NOTHING
                        """.trimIndent(),
                    ).param("s", e.seriesId)
                    .param("u", userId)
                    .param("a", e.accountId)
                    .param("m", e.merchant)
                    .param("amt", e.amountMinor)
                    .param("c", e.currency)
                    .param("i", e.intervalDays)
                    .param("n", e.nextExpectedOn)
                    .update()
            is RecurringPaymentChanged ->
                jdbc
                    .sql("UPDATE analytics_recurring SET amount_minor = :amt, next_expected_on = :n, updated_at = now() WHERE series_id = :s")
                    .param("amt", e.amountMinor)
                    .param("n", e.nextExpectedOn)
                    .param("s", e.seriesId)
                    .update()
            is ReferenceCurrencyChanged -> recomputeUser(userId, e.current)
            is UserKeyShredded -> resetUser(userId)
            else -> Unit
        }
        cache.invalidateUser(userId)
    }

    private fun updateParts(userId: UUID, txId: UUID, parts: List<TransactionPart>) {
        jdbc
            .sql("UPDATE analytics_tx SET parts = CAST(:p AS jsonb) WHERE transaction_id = :t")
            .param("p", mapper.writeValueAsString(parts))
            .param("t", txId)
            .update()
        refreshParts(txId, preferences.referenceCurrency(userId).value)
    }

    /** Remplace les parts analytiques d'une transaction à partir de sa copie locale. */
    private fun refreshParts(txId: UUID, refCurrency: String) {
        jdbc.sql("DELETE FROM analytics_tx_part WHERE transaction_id = :t").param("t", txId).update()
        val tx =
            jdbc
                .sql(
                    "SELECT user_id, account_id, value_date, currency, merchant_hash, merchant_enc, parts, is_transfer, voided " +
                        "FROM analytics_tx WHERE transaction_id = :t",
                ).param("t", txId)
                .query { rs, _ ->
                    TxCopy(
                        rs.getObject("user_id", UUID::class.java),
                        rs.getObject("account_id", UUID::class.java),
                        rs.getObject("value_date", LocalDate::class.java),
                        rs.getString("currency"),
                        rs.getString("merchant_hash"),
                        rs.getString("merchant_enc"),
                        mapper.readValue(rs.getString("parts"), Array<TransactionPart>::class.java).toList(),
                        rs.getBoolean("is_transfer"),
                        rs.getBoolean("voided"),
                    )
                }.optional()
                .orElse(null) ?: return
        if (tx.voided) return
        tx.parts.forEachIndexed { index, part ->
            jdbc
                .sql(
                    """
                    INSERT INTO analytics_tx_part (transaction_id, part_index, user_id, account_id, value_date, category_id,
                        amount_minor, currency, amount_ref_minor, ref_currency, merchant_hash, merchant_enc, is_transfer)
                    VALUES (:t, :i, :u, :a, :d, :cat, :amt, :c, :ref, :rc, :mh, :m, :tr)
                    """.trimIndent(),
                ).param("t", txId)
                .param("i", index)
                .param("u", tx.userId)
                .param("a", tx.accountId)
                .param("d", tx.valueDate)
                .param("cat", part.categoryId)
                .param("amt", part.amountMinor)
                .param("c", tx.currency)
                .param("ref", fx.convert(part.amountMinor, CurrencyCode(tx.currency), CurrencyCode(refCurrency), tx.valueDate))
                .param("rc", refCurrency)
                .param("mh", tx.merchantHash)
                .param("m", tx.merchantEnc)
                .param("tr", tx.transfer)
                .update()
        }
    }

    private fun recomputeUser(userId: UUID, refCurrency: String) {
        jdbc
            .sql("SELECT transaction_id FROM analytics_tx WHERE user_id = :u")
            .param("u", userId)
            .query(UUID::class.java)
            .list()
            .forEach { refreshParts(it, refCurrency) }
    }

    override fun reset() {
        jdbc.sql("TRUNCATE analytics_tx_part, analytics_tx, analytics_account, analytics_recurring").update()
        cache.invalidateAll()
    }

    override fun resetUser(userId: UUID) {
        listOf("analytics_tx_part", "analytics_tx", "analytics_account", "analytics_recurring").forEach {
            jdbc.sql("DELETE FROM $it WHERE user_id = :u").param("u", userId).update()
        }
        cache.invalidateUser(userId)
    }

    private class TxCopy(
        val userId: UUID,
        val accountId: UUID,
        val valueDate: LocalDate,
        val currency: String,
        val merchantHash: String?,
        val merchantEnc: String?,
        val parts: List<TransactionPart>,
        val transfer: Boolean,
        val voided: Boolean,
    )

    private companion object {
        val HANDLED =
            setOf(
                "AccountOpened", "TransactionRecorded", "TransactionAmended", "TransactionRecategorized", "TransactionSplit",
                "TransactionVoided", "RecurringPaymentDetected", "RecurringPaymentChanged", "ReferenceCurrencyChanged", "UserKeyShredded",
            )
    }
}
