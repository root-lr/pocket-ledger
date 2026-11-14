package io.pocketledger.analytics

import com.fasterxml.jackson.core.type.TypeReference
import io.pocketledger.platform.web.MoneyDto
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.FxConverter
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.sum
import org.jooq.impl.DSL.table
import org.jooq.impl.DSL.trunc
import org.jooq.impl.DSL.`when`
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CategorySpending(val categoryId: String?, val amount: MoneyDto, val previous: MoneyDto, val changePercent: Int?)

data class SpendingReport(
    val from: LocalDate,
    val to: LocalDate,
    val currency: String,
    val categories: List<CategorySpending>,
    val total: MoneyDto,
    val unconvertedCount: Int,
)

data class CashflowMonth(val month: String, val income: MoneyDto, val expenses: MoneyDto, val net: MoneyDto)

data class BalancePoint(val date: LocalDate, val balance: MoneyDto?, val partial: Boolean)

data class MerchantSpending(val merchant: String?, val transactions: Int, val amount: MoneyDto)

data class RecurringPayment(
    val seriesId: UUID,
    val merchant: String?,
    val amount: MoneyDto,
    val intervalDays: Int,
    val nextExpectedOn: LocalDate,
)

/** Requêtes analytics en jOOQ (DSL sans génération de code, voir ADR-005). */
@Service
class AnalyticsQueries(
    private val dsl: DSLContext,
    private val cache: AnalyticsCache,
    private val money: MoneyFormat,
    private val preferences: UserPreferencesQuery,
    private val fx: FxConverter,
    private val cipher: FieldCipher,
    private val clock: Clock,
) {
    private val parts = table(name("analytics_tx_part"))
    private val userId = field(name("user_id"), SQLDataType.UUID)
    private val valueDate = field(name("value_date"), SQLDataType.LOCALDATE)
    private val category = field(name("category_id"), SQLDataType.VARCHAR)
    private val amount = field(name("amount_minor"), SQLDataType.BIGINT)
    private val amountRef = field(name("amount_ref_minor"), SQLDataType.BIGINT)
    private val transfer = field(name("is_transfer"), SQLDataType.BOOLEAN)
    private val merchantHash = field(name("merchant_hash"), SQLDataType.VARCHAR)
    private val merchantEnc = field(name("merchant_enc"), SQLDataType.VARCHAR)
    private val txId = field(name("transaction_id"), SQLDataType.UUID)

    /** FR-60 : dépenses par catégorie sur une période, comparées à la période précédente de même durée. */
    fun spending(user: UUID, from: LocalDate, to: LocalDate): SpendingReport {
        requireRange(from, to)
        return cache.getOrCompute(user, "spending:$from:$to", object : TypeReference<SpendingReport>() {}) {
            val currency = preferences.referenceCurrency(user).value
            val days = ChronoUnit.DAYS.between(from, to) + 1
            val current = spendingByCategory(user, from, to)
            val previous = spendingByCategory(user, from.minusDays(days), from.minusDays(1))
            val unconverted =
                dsl
                    .selectCount()
                    .from(parts)
                    .where(userId.eq(user), valueDate.between(from, to), amountRef.isNull(), transfer.isFalse())
                    .fetchOne(0, Int::class.javaObjectType) ?: 0
            val categories =
                (current.keys + previous.keys).map { cat ->
                    val now = current[cat] ?: 0
                    val before = previous[cat] ?: 0
                    CategorySpending(
                        cat, money.dto(now, currency), money.dto(before, currency),
                        if (before > 0) ((now - before) * 100 / before).toInt() else null,
                    )
                }.sortedByDescending { it.amount.amountMinor }
            SpendingReport(from, to, currency, categories, money.dto(current.values.sum(), currency), unconverted)
        }
    }

    private fun spendingByCategory(user: UUID, from: LocalDate, to: LocalDate): Map<String?, Long> =
        dsl
            .select(category, sum(amountRef))
            .from(parts)
            .where(userId.eq(user), valueDate.between(from, to), amount.lt(0L), transfer.isFalse(), amountRef.isNotNull())
            .groupBy(category)
            .fetch()
            .associate { it.value1() to -(it.value2()?.toLong() ?: 0L) }

    /** FR-61 : entrées, sorties et solde net par mois, virements internes exclus. */
    fun cashflow(user: UUID, months: Int): List<CashflowMonth> {
        val count = months.coerceIn(1, 36)
        return cache.getOrCompute(user, "cashflow:$count", object : TypeReference<List<CashflowMonth>>() {}) {
            val currency = preferences.referenceCurrency(user).value
            val start = LocalDate.now(clock).withDayOfMonth(1).minusMonths(count - 1L)
            val month = trunc(valueDate, DatePart.MONTH)
            val income = sum(`when`(amountRef.gt(0L), amountRef).otherwise(0L))
            val expenses = sum(`when`(amountRef.lt(0L), amountRef).otherwise(0L))
            val rows =
                dsl
                    .select(month, income, expenses)
                    .from(parts)
                    .where(userId.eq(user), valueDate.ge(start), transfer.isFalse(), amountRef.isNotNull())
                    .groupBy(month)
                    .fetch()
                    .associate { it.value1().toString().take(7) to (toLong(it.value2()) to toLong(it.value3())) }
            (0 until count).map { i ->
                val key = start.plusMonths(i.toLong()).toString().take(7)
                val (inc, exp) = rows[key] ?: (0L to 0L)
                CashflowMonth(key, money.dto(inc, currency), money.dto(-exp, currency), money.dto(inc + exp, currency))
            }
        }
    }

    /**
     * FR-62 : solde total jour par jour. Chaque compte est cumulé dans sa devise puis converti
     * au taux du jour ; un jour sans taux pour un compte est marqué partiel.
     */
    fun balanceHistory(user: UUID, from: LocalDate, to: LocalDate): List<BalancePoint> {
        requireRange(from, to)
        return cache.getOrCompute(user, "balance:$from:$to", object : TypeReference<List<BalancePoint>>() {}) {
            val ref = preferences.referenceCurrency(user)
            val accountId = field(name("account_id"), SQLDataType.UUID)
            val accounts =
                dsl
                    .select(
                        accountId,
                        field(name("currency"), SQLDataType.VARCHAR),
                        field(name("opening_minor"), SQLDataType.BIGINT),
                        field(name("opened_on"), SQLDataType.LOCALDATE),
                    ).from(table(name("analytics_account")))
                    .where(userId.eq(user))
                    .fetch()
                    .map { AccountRow(it.value1(), it.value2(), it.value3(), it.value4()) }
            val voided = field(name("voided"), SQLDataType.BOOLEAN)
            val deltas: Map<UUID, Map<LocalDate, Long>> =
                dsl
                    .select(accountId, valueDate, sum(amount))
                    .from(table(name("analytics_tx")))
                    .where(userId.eq(user), voided.isFalse(), valueDate.le(to))
                    .groupBy(accountId, valueDate)
                    .fetch()
                    .groupBy { it.value1() }
                    .mapValues { (_, rows) -> rows.associate { it.value2() to toLong(it.value3()) } }

            // Solde de chaque compte la veille de `from`.
            val balances =
                accounts.associate { acc ->
                    val before = deltas[acc.id].orEmpty().filterKeys { it < from }.values.sum()
                    acc.id to (if (acc.openedOn < from) acc.openingMinor else 0L) + before
                }.toMutableMap()

            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { day ->
                    var total = 0L
                    var partial = false
                    accounts.forEach { acc ->
                        val opening = if (acc.openedOn == day) acc.openingMinor else 0L
                        val balance = balances.getValue(acc.id) + opening + (deltas[acc.id]?.get(day) ?: 0L)
                        balances[acc.id] = balance
                        if (!acc.openedOn.isAfter(day)) {
                            val converted = fx.convert(balance, CurrencyCode(acc.currency), ref, day)
                            if (converted == null) partial = true else total += converted
                        }
                    }
                    BalancePoint(day, money.dto(total, ref.value), partial)
                }.toList()
        }
    }

    private class AccountRow(val id: UUID, val currency: String, val openingMinor: Long, val openedOn: LocalDate)

    fun merchants(user: UUID, from: LocalDate, to: LocalDate, limit: Int): List<MerchantSpending> {
        requireRange(from, to)
        return cache.getOrCompute(user, "merchants:$from:$to:$limit", object : TypeReference<List<MerchantSpending>>() {}) {
            val currency = preferences.referenceCurrency(user).value
            val total = sum(amountRef)
            dsl
                .select(merchantHash, max(merchantEnc), count(txId), total)
                .from(parts)
                .where(userId.eq(user), valueDate.between(from, to), amount.lt(0L), transfer.isFalse(), merchantHash.isNotNull())
                .groupBy(merchantHash)
                .orderBy(total.asc())
                .limit(limit.coerceIn(1, 50))
                .fetch()
                .map {
                    MerchantSpending(it.value2()?.let { enc -> cipher.decrypt(user, enc) }, it.value3(), money.dto(-toLong(it.value4()), currency))
                }
        }
    }

    fun recurring(user: UUID): List<RecurringPayment> =
        dsl
            .select(
                field(name("series_id"), SQLDataType.UUID), field(name("merchant_enc"), SQLDataType.VARCHAR),
                field(name("amount_minor"), SQLDataType.BIGINT), field(name("currency"), SQLDataType.VARCHAR),
                field(name("interval_days"), SQLDataType.INTEGER), field(name("next_expected_on"), SQLDataType.LOCALDATE),
            ).from(table(name("analytics_recurring")))
            .where(userId.eq(user))
            .orderBy(field(name("next_expected_on")))
            .fetch()
            .map {
                RecurringPayment(
                    it.value1(), cipher.decrypt(user, it.value2()), money.dto(-it.value3(), it.value4()), it.value5(), it.value6(),
                )
            }

    private fun requireRange(from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) throw ValidationException("invalid-range", "from doit précéder to")
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ValidationException("range-too-long", "Période limitée à 366 jours")
    }

    private fun toLong(value: BigDecimal?): Long = value?.toLong() ?: 0L
}
