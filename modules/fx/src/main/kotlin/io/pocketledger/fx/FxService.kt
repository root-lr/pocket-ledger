package io.pocketledger.fx

import com.github.benmanes.caffeine.cache.Caffeine
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ports.CurrencyCatalog
import io.pocketledger.shared.ports.FxConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate

/** Parités fixes, prioritaires sur le fournisseur (FR-95) : 1 EUR = 655,957 XOF = 655,957 XAF. */
object FixedPegs {
    data class Peg(val anchor: String, val unitsPerAnchor: BigDecimal)

    val pegs: Map<String, Peg> =
        mapOf(
            "XOF" to Peg("EUR", BigDecimal("655.957")),
            "XAF" to Peg("EUR", BigDecimal("655.957")),
        )
}

@Component
class FxRateRepository(private val jdbc: JdbcClient) {
    /** Dernier taux connu de moins de 7 jours (FR-94), en unités de `quote` pour 1 `base`. */
    fun find(base: String, quote: String, date: LocalDate): BigDecimal? =
        jdbc
            .sql(
                """
                SELECT rate FROM fx_rate
                WHERE base = :base AND quote = :quote AND rate_date <= :date AND rate_date >= :oldest
                ORDER BY rate_date DESC LIMIT 1
                """.trimIndent(),
            ).param("base", base)
            .param("quote", quote)
            .param("date", date)
            .param("oldest", date.minusDays(MAX_AGE_DAYS))
            .query(BigDecimal::class.java)
            .optional()
            .orElse(null)

    fun upsert(date: LocalDate, base: String, rates: Map<String, BigDecimal>, source: String): Int =
        rates.entries.sumOf { (quote, rate) ->
            jdbc
                .sql(
                    """
                    INSERT INTO fx_rate (rate_date, base, quote, rate, source) VALUES (:d, :b, :q, :r, :s)
                    ON CONFLICT (rate_date, base, quote) DO UPDATE SET rate = EXCLUDED.rate, source = EXCLUDED.source
                    """.trimIndent(),
                ).param("d", date)
                .param("b", base)
                .param("q", quote)
                .param("r", rate)
                .param("s", source)
                .update()
        }

    fun hasRatesFor(date: LocalDate, base: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM fx_rate WHERE rate_date = :d AND base = :b)")
            .param("d", date)
            .param("b", base)
            .query(Boolean::class.javaObjectType)
            .single()

    companion object {
        const val MAX_AGE_DAYS = 7L
    }
}

/**
 * Conversion via une devise pivot (celle du fournisseur, USD par défaut). BigDecimal, arrondi HALF_EVEN
 * aux décimales de la devise cible. Taux introuvable → null : la ligne sera « non convertie », jamais fausse.
 */
@Service
class FxService(
    private val rates: FxRateRepository,
    private val catalog: CurrencyCatalog,
    @Value("\${pocketledger.fx.pivot:USD}") private val pivot: String,
) : FxConverter {
    private val mc = MathContext.DECIMAL128
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(100_000)
            .build<Pair<String, LocalDate>, BigDecimal>()

    override fun convert(amountMinor: Long, from: CurrencyCode, to: CurrencyCode, date: LocalDate): Long? {
        if (from == to) return amountMinor
        val rate = rate(from.value, to.value, date) ?: return null
        return BigDecimal
            .valueOf(amountMinor, catalog.minorDigits(from))
            .multiply(rate, mc)
            .setScale(catalog.minorDigits(to), RoundingMode.HALF_EVEN)
            .unscaledValue()
            .longValueExact()
    }

    /** Unités de `to` pour 1 unité de `from`. */
    fun rate(from: String, to: String, date: LocalDate): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        FixedPegs.pegs[from]?.let { if (it.anchor == to) return BigDecimal.ONE.divide(it.unitsPerAnchor, mc) }
        FixedPegs.pegs[to]?.let { if (it.anchor == from) return it.unitsPerAnchor }
        val fromPerPivot = perPivot(from, date) ?: return null
        val toPerPivot = perPivot(to, date) ?: return null
        return toPerPivot.divide(fromPerPivot, mc)
    }

    private fun perPivot(code: String, date: LocalDate): BigDecimal? {
        if (code == pivot) return BigDecimal.ONE
        FixedPegs.pegs[code]?.let { peg -> return perPivot(peg.anchor, date)?.multiply(peg.unitsPerAnchor, mc) }
        cache.getIfPresent(code to date)?.let { return it }
        return rates.find(pivot, code, date)?.also { cache.put(code to date, it) }
    }
}
