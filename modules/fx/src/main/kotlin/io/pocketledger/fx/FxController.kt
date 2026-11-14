package io.pocketledger.fx

import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CurrencyCatalog
import io.pocketledger.shared.ports.CurrencyInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
class FxController(
    private val catalog: CurrencyCatalog,
    private val fx: FxService,
    private val repository: FxRateRepository,
    private val importJob: FxRateImportJob,
) {
    @GetMapping("/currencies")
    fun currencies(): List<CurrencyInfo> = catalog.all()

    @GetMapping("/fx/rate")
    fun rate(
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): Map<String, Any?> = mapOf("from" to from, "to" to to, "date" to date, "rate" to fx.rate(from.uppercase(), to.uppercase(), date))

    /** Saisie manuelle de taux (démo, rattrapage) — rôle ADMIN. */
    @PostMapping("/admin/fx-rates")
    fun manualRates(
        @Valid @RequestBody request: ManualRates,
    ): Map<String, Int> {
        (request.rates.keys + request.base).forEach { code ->
            val supported = runCatching { catalog.isSupported(CurrencyCode(code)) }.getOrDefault(false)
            if (!supported) throw ValidationException("unsupported-currency", "Devise non prise en charge : $code")
        }
        return mapOf("saved" to repository.upsert(request.date, request.base, request.rates, "manual"))
    }

    @PostMapping("/admin/fx-rates/import")
    fun importNow(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): Map<String, Int> = mapOf("imported" to importJob.importFor(date))

    data class ManualRates(val date: LocalDate, val base: String, @field:NotEmpty val rates: Map<String, BigDecimal>)
}
