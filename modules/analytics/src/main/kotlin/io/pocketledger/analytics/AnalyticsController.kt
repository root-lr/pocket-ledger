package io.pocketledger.analytics

import io.pocketledger.platform.web.CurrentUser
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(private val queries: AnalyticsQueries, private val clock: Clock) {
    @GetMapping("/spending")
    fun spending(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): SpendingReport {
        val end = to ?: LocalDate.now(clock)
        return queries.spending(CurrentUser.id(), from ?: end.withDayOfMonth(1), end)
    }

    @GetMapping("/cashflow")
    fun cashflow(
        @RequestParam(defaultValue = "12") months: Int,
    ): List<CashflowMonth> = queries.cashflow(CurrentUser.id(), months)

    @GetMapping("/balance-history")
    fun balanceHistory(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<BalancePoint> {
        val end = to ?: LocalDate.now(clock)
        return queries.balanceHistory(CurrentUser.id(), from ?: end.minusDays(89), end)
    }

    @GetMapping("/merchants")
    fun merchants(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<MerchantSpending> {
        val end = to ?: LocalDate.now(clock)
        return queries.merchants(CurrentUser.id(), from ?: end.minusDays(89), end, limit)
    }

    @GetMapping("/recurring")
    fun recurring(): List<RecurringPayment> = queries.recurring(CurrentUser.id())
}
