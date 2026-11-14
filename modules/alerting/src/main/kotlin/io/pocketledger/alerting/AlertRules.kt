package io.pocketledger.alerting

import kotlin.math.abs
import kotlin.math.sqrt

/** Règles pures, testées sans infrastructure. */
object AlertRules {
    const val MIN_HISTORY = 10
    const val SIGMAS = 3.0

    /** FR-71 : montant > moyenne + 3 écarts-types de la catégorie sur 90 jours (au moins 10 points). */
    fun isUnusual(amountMinor: Long, history: List<Long>): Boolean {
        if (history.size < MIN_HISTORY) return false
        val values = history.map { abs(it).toDouble() }
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return abs(amountMinor) > mean + SIGMAS * sqrt(variance)
    }

    /** FR-72 : alerte au franchissement du seuil vers le bas, pas à chaque transaction sous le seuil. */
    fun crossesBelow(previousBalance: Long, newBalance: Long, threshold: Long?): Boolean =
        threshold != null && previousBalance >= threshold && newBalance < threshold
}
