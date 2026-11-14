package io.pocketledger.categorization

import io.pocketledger.events.Context
import io.pocketledger.events.DomainEvent
import io.pocketledger.events.RecurringPaymentChanged
import io.pocketledger.events.RecurringPaymentDetected
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionVoided
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.AggregateRepositories
import io.pocketledger.platform.eventstore.AggregateRepository
import io.pocketledger.platform.eventstore.EventSourcedAggregate
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

data class Occurrence(val date: LocalDate, val amountMinor: Long)

/**
 * Règle de détection d'abonnement (FR-73) : même marchand, 3 dernières occurrences
 * espacées de 28 à 31 jours, montants à ±10 % de leur moyenne.
 */
object RecurringDetection {
    fun isRecurring(occurrences: List<Occurrence>): Boolean {
        if (occurrences.size < 3) return false
        val last = occurrences.sortedBy { it.date }.takeLast(3)
        val intervals = last.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (intervals.any { it !in 28..31 }) return false
        val mean = last.map { abs(it.amountMinor) }.average()
        return last.all { abs(abs(it.amountMinor) - mean) <= mean * 0.10 }
    }

    fun averageInterval(occurrences: List<Occurrence>): Int {
        val last = occurrences.sortedBy { it.date }.takeLast(3)
        return last.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }.average().toInt()
    }

    /** Hausse de plus de 5 % par rapport au montant connu de la série. */
    fun isIncrease(previousMinor: Long, newMinor: Long): Boolean = abs(newMinor) > abs(previousMinor) * 1.05
}

data class SeriesState(val detected: Boolean = false, val lastAmountMinor: Long = 0)

class RecurringSeries(id: UUID) : EventSourcedAggregate<SeriesState>(id, SeriesState()) {
    fun detect(accountId: UUID, merchant: String, merchantHash: String, amountMinor: Long, currency: String, interval: Int, next: LocalDate) {
        if (state.detected) return
        raise(RecurringPaymentDetected(id, accountId, merchant, merchantHash, amountMinor, currency, interval, next))
    }

    fun change(merchant: String, amountMinor: Long, currency: String, next: LocalDate) {
        raise(RecurringPaymentChanged(id, merchant, state.lastAmountMinor, amountMinor, currency, next))
    }

    override fun evolve(state: SeriesState, event: DomainEvent): SeriesState =
        when (event) {
            is RecurringPaymentDetected -> state.copy(detected = true, lastAmountMinor = event.amountMinor)
            is RecurringPaymentChanged -> state.copy(lastAmountMinor = event.amountMinor)
            else -> state
        }
}

@Configuration
class CategorizationRepositories {
    @Bean
    fun recurringSeriesRepository(factory: AggregateRepositories): AggregateRepository<RecurringSeries, SeriesState> =
        factory.create("RecurringSeries", Context.CATEGORIZATION, SeriesState::class.java) { RecurringSeries(it) }
}

/**
 * Process manager : observe les dépenses et émet RecurringPaymentDetected / Changed.
 * Il émet des événements, il n'est donc pas rejoué lors d'un rebuild.
 */
@Component
class RecurringDetector(
    private val jdbc: JdbcClient,
    private val series: AggregateRepository<RecurringSeries, SeriesState>,
) : EventProcessor {
    override val name = "categorization-recurring"
    override val contexts = setOf(Context.LEDGER, Context.IDENTITY)
    override val rebuildable = false

    override fun handles(eventType: String) = eventType in setOf("TransactionRecorded", "TransactionVoided", "UserKeyShredded")

    override fun process(event: StoredEvent) {
        when (val e = event.event) {
            is TransactionRecorded -> onRecorded(event.userId, e)
            is TransactionVoided ->
                jdbc.sql("UPDATE recurring_occurrence SET voided = true WHERE transaction_id = :t").param("t", e.transactionId).update()
            is UserKeyShredded -> {
                jdbc.sql("DELETE FROM recurring_occurrence WHERE user_id = :u").param("u", event.userId).update()
                jdbc.sql("DELETE FROM recurring_series WHERE user_id = :u").param("u", event.userId).update()
            }
            else -> Unit
        }
    }

    private fun onRecorded(userId: UUID, e: TransactionRecorded) {
        if (e.amountMinor >= 0 || e.transferId != null) return
        val merchant = e.merchant ?: return
        val merchantHash = e.merchantHash ?: return
        jdbc
            .sql(
                """
                INSERT INTO recurring_occurrence (transaction_id, user_id, account_id, merchant_hash, amount_minor, currency, value_date)
                VALUES (:t, :u, :a, :m, :amt, :c, :d) ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("t", e.transactionId)
            .param("u", userId)
            .param("a", e.accountId)
            .param("m", merchantHash)
            .param("amt", e.amountMinor)
            .param("c", e.currency)
            .param("d", e.valueDate)
            .update()

        val occurrences =
            jdbc
                .sql(
                    """
                    SELECT value_date, amount_minor FROM recurring_occurrence
                    WHERE user_id = :u AND merchant_hash = :m AND currency = :c AND NOT voided
                    ORDER BY value_date DESC LIMIT 6
                    """.trimIndent(),
                ).param("u", userId)
                .param("m", merchantHash)
                .param("c", e.currency)
                .query { rs, _ -> Occurrence(rs.getObject("value_date", LocalDate::class.java), rs.getLong("amount_minor")) }
                .list()

        val existing =
            jdbc
                .sql("SELECT series_id FROM recurring_series WHERE user_id = :u AND merchant_hash = :m AND currency = :c")
                .param("u", userId)
                .param("m", merchantHash)
                .param("c", e.currency)
                .query(UUID::class.java)
                .optional()
                .orElse(null)

        if (existing == null) {
            if (!RecurringDetection.isRecurring(occurrences)) return
            val interval = RecurringDetection.averageInterval(occurrences)
            val seriesId = UUID.randomUUID()
            val aggregate = RecurringSeries(seriesId)
            val next = e.valueDate.plusDays(interval.toLong())
            aggregate.detect(e.accountId, merchant, merchantHash, e.amountMinor, e.currency, interval, next)
            series.save(aggregate, userId)
            jdbc
                .sql(
                    "INSERT INTO recurring_series (series_id, user_id, merchant_hash, currency, interval_days, created_at) " +
                        "VALUES (:s, :u, :m, :c, :i, now())",
                ).param("s", seriesId)
                .param("u", userId)
                .param("m", merchantHash)
                .param("c", e.currency)
                .param("i", interval)
                .update()
        } else {
            val aggregate = series.get(existing)
            if (RecurringDetection.isIncrease(aggregate.state.lastAmountMinor, e.amountMinor)) {
                val interval =
                    jdbc
                        .sql("SELECT interval_days FROM recurring_series WHERE series_id = :s")
                        .param("s", existing)
                        .query(Int::class.javaObjectType)
                        .single()
                aggregate.change(merchant, e.amountMinor, e.currency, e.valueDate.plusDays(interval.toLong()))
                series.save(aggregate, userId)
            }
        }
    }
}
