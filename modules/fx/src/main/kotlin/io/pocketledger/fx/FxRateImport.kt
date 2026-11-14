package io.pocketledger.fx

import com.fasterxml.jackson.databind.JsonNode
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

data class RateSet(val base: String, val rates: Map<String, BigDecimal>, val source: String)

interface FxRateProvider {
    fun fetch(date: LocalDate): RateSet?
}

/**
 * Fournisseur HTTP générique : l'URL accepte {date} et {appId} et doit renvoyer
 * {"base": "USD", "rates": {"EUR": 0.92, ...}} (format d'Open Exchange Rates et de plusieurs autres).
 * Le choix du fournisseur reste ouvert (PRD §11) : il doit couvrir XOF, XAF, NGN, GHS…
 */
@Component
class HttpFxRateProvider(
    @Value("\${pocketledger.fx.provider.url:}") private val urlTemplate: String,
    @Value("\${pocketledger.fx.provider.app-id:}") private val appId: String,
    @Value("\${pocketledger.fx.provider.name:http}") private val name: String,
) : FxRateProvider {
    private val client = RestClient.create()

    @Retry(name = "fx-provider")
    @CircuitBreaker(name = "fx-provider")
    override fun fetch(date: LocalDate): RateSet? {
        if (urlTemplate.isBlank()) return null
        val url = urlTemplate.replace("{date}", date.toString()).replace("{appId}", appId)
        val body = client.get().uri(url).retrieve().body(JsonNode::class.java) ?: return null
        val rates = mutableMapOf<String, BigDecimal>()
        body.path("rates").fields().forEach { (code, value) -> rates[code] = value.decimalValue() }
        return RateSet(body.path("base").asText("USD"), rates, name)
    }
}

/** Import quotidien des taux (FR-94) ; une alerte opérateur est loguée et comptée en cas d'échec. */
@Component
class FxRateImportJob(
    private val provider: FxRateProvider,
    private val repository: FxRateRepository,
    private val registry: CurrencyRegistry,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${pocketledger.fx.pivot:USD}") private val pivot: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        val today = LocalDate.now(clock)
        if (!repository.hasRatesFor(today, pivot)) importFor(today)
    }

    @Scheduled(cron = "\${pocketledger.fx.cron:0 23 6 * * *}", zone = "UTC")
    fun daily() = importFor(LocalDate.now(clock))

    fun importFor(date: LocalDate): Int {
        val set =
            runCatching { provider.fetch(date) }
                .onFailure {
                    meterRegistry.counter("pocketledger.fx.import.failures").increment()
                    log.error("Import des taux du {} impossible : {}", date, it.message)
                }.getOrNull() ?: return 0
        if (set.base != pivot) {
            log.error("Le fournisseur renvoie la base {} au lieu de {}", set.base, pivot)
            return 0
        }
        val supported = registry.all().map { it.code }.toSet()
        val count = repository.upsert(date, set.base, set.rates.filterKeys { it in supported }, set.source)
        log.info("{} taux importés pour le {}", count, date)
        return count
    }
}
