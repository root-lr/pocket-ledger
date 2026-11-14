package io.pocketledger.fx

import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CurrencyCatalog
import io.pocketledger.shared.ports.CurrencyInfo
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/** Référentiel ISO 4217 chargé en mémoire depuis la table `currency` (FR-90). */
@Component
class CurrencyRegistry(private val jdbc: JdbcClient) : CurrencyCatalog {
    private val currencies = AtomicReference<Map<String, CurrencyInfo>>(emptyMap())

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 0)
    fun reload() {
        currencies.set(
            jdbc
                .sql("SELECT code, minor_digits, name FROM currency WHERE active ORDER BY code")
                .query { rs, _ -> CurrencyInfo(rs.getString("code").trim(), rs.getInt("minor_digits"), rs.getString("name")) }
                .list()
                .associateBy { it.code },
        )
    }

    private fun map(): Map<String, CurrencyInfo> {
        if (currencies.get().isEmpty()) reload()
        return currencies.get()
    }

    override fun isSupported(code: CurrencyCode): Boolean = map().containsKey(code.value)

    override fun minorDigits(code: CurrencyCode): Int =
        map()[code.value]?.minorDigits ?: throw ValidationException("unsupported-currency", "Devise non prise en charge : $code")

    override fun all(): List<CurrencyInfo> = map().values.sortedBy { it.code }
}
