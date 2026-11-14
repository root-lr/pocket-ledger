package io.pocketledger.shared

import java.math.BigDecimal
import java.math.RoundingMode

/** Code devise ISO 4217 (trois lettres majuscules). */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) { "Code devise invalide : $value" }
    }

    override fun toString(): String = value
}

/**
 * Montant en unités mineures de sa devise. Jamais de flottant :
 * 12,34 EUR = Money(1234, EUR) ; 1 500 XOF = Money(1500, XOF) ; 1,250 KWD = Money(1250, KWD).
 */
data class Money(val amountMinor: Long, val currency: CurrencyCode) {
    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(amountMinor, other.amountMinor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(amountMinor), currency)

    val isNegative: Boolean get() = amountMinor < 0
    val isZero: Boolean get() = amountMinor == 0L

    fun toDecimal(minorDigits: Int): BigDecimal = BigDecimal.valueOf(amountMinor, minorDigits)

    private fun requireSameCurrency(other: Money) {
        if (other.currency != currency) throw CurrencyMismatchException(currency, other.currency)
    }

    companion object {
        fun zero(currency: CurrencyCode) = Money(0, currency)

        /**
         * Convertit un montant décimal en unités mineures. Refuse un montant dont la précision
         * dépasse les décimales de la devise (FR-91) : "12.345" EUR est refusé.
         */
        fun ofDecimal(amount: BigDecimal, currency: CurrencyCode, minorDigits: Int): Money {
            val normalized = amount.stripTrailingZeros()
            if (normalized.scale() > minorDigits) {
                throw ValidationException(
                    "amount-precision",
                    "Le montant $amount a plus de $minorDigits décimale(s) pour la devise $currency",
                )
            }
            return Money(normalized.setScale(minorDigits, RoundingMode.UNNECESSARY).unscaledValue().longValueExact(), currency)
        }
    }
}

class CurrencyMismatchException(expected: CurrencyCode, actual: CurrencyCode) :
    DomainException("currency-mismatch", "Devise attendue $expected, reçue $actual")
