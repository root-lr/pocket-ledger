package io.pocketledger.events

import java.time.LocalDate
import java.util.UUID

// ---- Contexte budgeting ----

data class BudgetCreated(
    val budgetId: UUID,
    val categoryId: String,
    val limitMinor: Long,
    val currency: String,
    val rollover: Boolean,
    val startMonth: String, // "YYYY-MM"
) : DomainEvent

data class BudgetLimitChanged(val budgetId: UUID, val limitMinor: Long, val effectiveMonth: String) : DomainEvent

data class BudgetClosed(val budgetId: UUID, val effectiveMonth: String) : DomainEvent

data class BudgetThresholdCrossed(
    val budgetId: UUID,
    val categoryId: String,
    val month: String,
    val thresholdPercent: Int,
    val spentMinor: Long,
    val limitMinor: Long,
    val currency: String,
) : DomainEvent

// ---- Contexte categorization ----

data class RecurringPaymentDetected(
    val seriesId: UUID,
    val accountId: UUID,
    val merchant: String, // chiffré
    val merchantHash: String,
    val amountMinor: Long,
    val currency: String,
    val intervalDays: Int,
    val nextExpectedOn: LocalDate,
) : DomainEvent

data class RecurringPaymentChanged(
    val seriesId: UUID,
    val merchant: String, // chiffré
    val previousAmountMinor: Long,
    val amountMinor: Long,
    val currency: String,
    val nextExpectedOn: LocalDate,
) : DomainEvent

// ---- Contexte alerts ----

data class AlertRaised(
    val alertId: UUID,
    val alertType: String,
    val dedupeKey: String,
    val details: Map<String, String>,
) : DomainEvent

data class AlertDismissed(val alertId: UUID) : DomainEvent

// ---- Contexte identity ----

/** La clé de chiffrement de l'utilisateur a été détruite : toutes les projections purgent ses lignes. */
data class UserKeyShredded(val shreddedAt: String) : DomainEvent

/** Devise de référence modifiée : les vues consolidées de l'utilisateur sont reconstruites (FR-97). */
data class ReferenceCurrencyChanged(val previous: String, val current: String) : DomainEvent
