package io.pocketledger.events

/** Types d'alertes (FR-70 à FR-74), désactivables par l'utilisateur (FR-76). */
object AlertTypes {
    const val BUDGET_THRESHOLD = "BUDGET_THRESHOLD"
    const val UNUSUAL_SPENDING = "UNUSUAL_SPENDING"
    const val LOW_BALANCE = "LOW_BALANCE"
    const val RECURRING_PAYMENT = "RECURRING_PAYMENT"
    const val PROBABLE_DUPLICATE = "PROBABLE_DUPLICATE"

    val all = setOf(BUDGET_THRESHOLD, UNUSUAL_SPENDING, LOW_BALANCE, RECURRING_PAYMENT, PROBABLE_DUPLICATE)
}
