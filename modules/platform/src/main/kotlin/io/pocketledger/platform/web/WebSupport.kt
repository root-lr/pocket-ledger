package io.pocketledger.platform.web

import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.Money
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CurrencyCatalog
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Base64
import java.util.UUID

/** Utilisateur authentifié (sujet du JWT). */
object CurrentUser {
    fun id(): UUID =
        SecurityContextHolder.getContext().authentication?.name?.let(UUID::fromString)
            ?: error("Aucun utilisateur authentifié")
}

/** Montant exposé par l'API : unités mineures, décimal formaté et devise. */
data class MoneyDto(val amountMinor: Long, val amount: String, val currency: String)

@Component
class MoneyFormat(private val catalog: CurrencyCatalog) {
    fun dto(amountMinor: Long, currency: String): MoneyDto {
        val digits = catalog.minorDigits(CurrencyCode(currency))
        return MoneyDto(amountMinor, BigDecimal.valueOf(amountMinor, digits).toPlainString(), currency)
    }

    fun currency(code: String): CurrencyCode {
        val currency =
            try {
                CurrencyCode(code.uppercase())
            } catch (e: IllegalArgumentException) {
                throw ValidationException("invalid-currency", "Code devise invalide : $code")
            }
        if (!catalog.isSupported(currency)) throw ValidationException("unsupported-currency", "Devise non prise en charge : $code")
        return currency
    }

    /** "12.34" + "EUR" → Money(1234, EUR) ; refuse "12.345" EUR (FR-91). */
    fun parse(amount: String, currencyCode: String): Money {
        val currency = currency(currencyCode)
        val decimal =
            amount.trim().replace(',', '.').toBigDecimalOrNull()
                ?: throw ValidationException("invalid-amount", "Montant invalide : $amount")
        return Money.ofDecimal(decimal, currency, catalog.minorDigits(currency))
    }
}

/** Curseurs de pagination opaques (jamais d'offset). */
object Cursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(vararg parts: Any): String = encoder.encodeToString(parts.joinToString("|").toByteArray())

    fun decode(cursor: String?): List<String>? =
        cursor?.let {
            try {
                String(decoder.decode(it)).split("|")
            } catch (e: IllegalArgumentException) {
                throw ValidationException("invalid-cursor", "Curseur invalide")
            }
        }
}

data class Page<T>(val items: List<T>, val nextCursor: String?)

fun clampLimit(limit: Int?): Int = (limit ?: 50).coerceIn(1, 200)

/**
 * Lecture après écriture : attend (au plus 2 s) que la projection ait atteint la version demandée.
 * Retourne false si le délai est dépassé ; la réponse est alors servie telle quelle.
 */
fun awaitVersion(minVersion: Int?, current: () -> Int): Boolean {
    if (minVersion == null) return true
    val deadline = System.nanoTime() + 2_000_000_000L
    while (System.nanoTime() < deadline) {
        if (current() >= minVersion) return true
        Thread.sleep(50)
    }
    return current() >= minVersion
}
