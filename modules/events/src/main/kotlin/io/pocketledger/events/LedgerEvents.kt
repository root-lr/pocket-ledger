package io.pocketledger.events

import java.time.LocalDate
import java.util.UUID

/*
 * Contexte ledger. Les champs marqués (chiffré) contiennent "enc:v1:<keyId>:<base64>",
 * jamais le texte clair. Les montants sont en unités mineures de la devise d'origine.
 */

data class AccountOpened(
    val accountId: UUID,
    val name: String, // chiffré
    val type: String,
    val currency: String,
    val openingBalanceMinor: Long,
    val openedOn: LocalDate,
) : DomainEvent

data class AccountRenamed(val accountId: UUID, val name: String) : DomainEvent // chiffré

data class AccountArchived(val accountId: UUID) : DomainEvent

data class AccountReactivated(val accountId: UUID) : DomainEvent

data class LowBalanceThresholdSet(val accountId: UUID, val thresholdMinor: Long?) : DomainEvent

/** Une part d'une transaction ; une transaction non répartie a une seule part. */
data class TransactionPart(val categoryId: String?, val amountMinor: Long)

/**
 * Chaque événement de transaction porte l'état complet résultant, pour que les consommateurs
 * puissent remplacer leur vue de la transaction sans connaître l'historique.
 */
data class TransactionRecorded(
    val accountId: UUID,
    val transactionId: UUID,
    val revision: Int,
    val amountMinor: Long,
    val currency: String,
    val valueDate: LocalDate,
    val label: String, // chiffré
    val merchant: String?, // chiffré
    val merchantHash: String?,
    val labelTokens: List<String>,
    val note: String?, // chiffré
    val tags: List<String>,
    val parts: List<TransactionPart>,
    val transferId: UUID? = null,
    val importId: UUID? = null,
) : DomainEvent

data class TransactionAmended(
    val accountId: UUID,
    val transactionId: UUID,
    val revision: Int,
    val previousAmountMinor: Long,
    val amountMinor: Long,
    val currency: String,
    val valueDate: LocalDate,
    val label: String,
    val merchant: String?,
    val merchantHash: String?,
    val labelTokens: List<String>,
    val note: String?,
    val tags: List<String>,
    val parts: List<TransactionPart>,
) : DomainEvent

data class TransactionVoided(
    val accountId: UUID,
    val transactionId: UUID,
    val revision: Int,
    val amountMinor: Long,
    val currency: String,
    val reason: String?,
) : DomainEvent

data class TransactionRecategorized(
    val accountId: UUID,
    val transactionId: UUID,
    val revision: Int,
    val parts: List<TransactionPart>,
) : DomainEvent

data class TransactionSplit(
    val accountId: UUID,
    val transactionId: UUID,
    val revision: Int,
    val parts: List<TransactionPart>,
) : DomainEvent

data class TransferInitiated(
    val transferId: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val debitMinor: Long,
    val debitCurrency: String,
    val creditMinor: Long,
    val creditCurrency: String,
    val impliedRate: String?, // créditeur / débiteur, null si même devise
    val feeMinor: Long,
    val valueDate: LocalDate,
    val label: String, // chiffré
) : DomainEvent

data class TransferCompleted(val transferId: UUID, val debitTransactionId: UUID, val creditTransactionId: UUID) : DomainEvent

data class TransferCompensated(val transferId: UUID, val debitTransactionId: UUID?, val reason: String) : DomainEvent

data class ImportStarted(
    val importId: UUID,
    val accountId: UUID,
    val fileName: String, // chiffré
    val fileHash: String,
    val format: String,
) : DomainEvent

data class ImportRowSkippedAsDuplicate(val importId: UUID, val rowNumber: Int, val fingerprint: String) : DomainEvent

data class ImportCompleted(val importId: UUID, val imported: Int, val duplicates: Int, val failed: Int) : DomainEvent
