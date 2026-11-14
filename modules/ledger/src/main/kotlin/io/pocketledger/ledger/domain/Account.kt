package io.pocketledger.ledger.domain

import io.pocketledger.events.AccountArchived
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.AccountReactivated
import io.pocketledger.events.AccountRenamed
import io.pocketledger.events.DomainEvent
import io.pocketledger.events.LowBalanceThresholdSet
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.platform.eventstore.EventSourcedAggregate
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.StaleVersionException
import io.pocketledger.shared.ValidationException
import java.time.LocalDate
import java.util.UUID

enum class AccountType { CHECKING, SAVINGS, CASH, CREDIT_CARD }

data class TxState(
    val amountMinor: Long,
    val valueDate: LocalDate,
    val label: String,
    val merchant: String?,
    val merchantHash: String?,
    val labelTokens: List<String>,
    val note: String?,
    val tags: List<String>,
    val parts: List<TransactionPart>,
    val revision: Int,
    val voided: Boolean,
    val transferId: UUID?,
)

data class AccountState(
    val opened: Boolean = false,
    val type: String? = null,
    val currency: String? = null,
    val balanceMinor: Long = 0,
    val archived: Boolean = false,
    val lowThresholdMinor: Long? = null,
    val transactions: Map<UUID, TxState> = emptyMap(),
)

/** Champs textuels d'une transaction, déjà chiffrés par la couche application. */
data class TxText(
    val label: String,
    val merchant: String?,
    val merchantHash: String?,
    val labelTokens: List<String>,
    val note: String?,
    val tags: List<String>,
)

/**
 * Agrégat compte + grand livre : le solde et les transactions vivent dans le même agrégat
 * pour que les invariants (compte actif, devise, split équilibré) soient vérifiés atomiquement (ADR-004).
 */
class Account(id: UUID) : EventSourcedAggregate<AccountState>(id, AccountState()) {
    val currency: String get() = state.currency ?: throw IllegalStateException("Compte non ouvert")

    fun open(name: String, type: AccountType, currency: String, openingBalanceMinor: Long, openedOn: LocalDate) {
        if (state.opened) throw ConflictException("account-exists", "Compte déjà ouvert")
        raise(AccountOpened(id, name, type.name, currency, openingBalanceMinor, openedOn))
    }

    fun rename(name: String) {
        requireOpen()
        raise(AccountRenamed(id, name))
    }

    fun archive() {
        requireOpen()
        if (state.archived) return
        raise(AccountArchived(id))
    }

    fun reactivate() {
        requireOpen()
        if (!state.archived) return
        raise(AccountReactivated(id))
    }

    fun setLowBalanceThreshold(thresholdMinor: Long?) {
        requireActive()
        raise(LowBalanceThresholdSet(id, thresholdMinor))
    }

    fun record(
        transactionId: UUID,
        amountMinor: Long,
        valueDate: LocalDate,
        text: TxText,
        parts: List<TransactionPart>,
        transferId: UUID? = null,
        importId: UUID? = null,
    ) {
        requireActive()
        if (state.transactions.containsKey(transactionId)) {
            throw ConflictException("transaction-exists", "Transaction déjà enregistrée : $transactionId")
        }
        if (amountMinor == 0L) throw ValidationException("zero-amount", "Le montant ne peut pas être nul")
        requireBalanced(amountMinor, parts)
        raise(
            TransactionRecorded(
                id, transactionId, 1, amountMinor, currency, valueDate, text.label, text.merchant, text.merchantHash,
                text.labelTokens, text.note, text.tags, parts, transferId, importId,
            ),
        )
    }

    fun amend(
        transactionId: UUID,
        expectedRevision: Int?,
        amountMinor: Long?,
        valueDate: LocalDate?,
        text: TxText?,
        parts: List<TransactionPart>?,
    ) {
        requireActive()
        val tx = editable(transactionId, expectedRevision)
        val newAmount = amountMinor ?: tx.amountMinor
        if (newAmount == 0L) throw ValidationException("zero-amount", "Le montant ne peut pas être nul")
        val newParts =
            parts ?: when {
                newAmount == tx.amountMinor -> tx.parts
                tx.parts.size == 1 -> listOf(tx.parts.single().copy(amountMinor = newAmount))
                else -> throw ValidationException("parts-required", "Transaction répartie : fournir les nouvelles parts")
            }
        requireBalanced(newAmount, newParts)
        val t = text ?: TxText(tx.label, tx.merchant, tx.merchantHash, tx.labelTokens, tx.note, tx.tags)
        raise(
            TransactionAmended(
                id, transactionId, tx.revision + 1, tx.amountMinor, newAmount, currency, valueDate ?: tx.valueDate,
                t.label, t.merchant, t.merchantHash, t.labelTokens, t.note, t.tags, newParts,
            ),
        )
    }

    fun recategorize(transactionId: UUID, expectedRevision: Int?, categoryId: String?) {
        requireActive()
        val tx = editable(transactionId, expectedRevision, allowTransfer = true)
        val parts = listOf(TransactionPart(categoryId, tx.amountMinor))
        if (parts == tx.parts) return
        raise(TransactionRecategorized(id, transactionId, tx.revision + 1, parts))
    }

    fun split(transactionId: UUID, expectedRevision: Int?, parts: List<TransactionPart>) {
        requireActive()
        val tx = editable(transactionId, expectedRevision)
        if (parts.size < 2) throw ValidationException("split-too-small", "Une répartition compte au moins deux parts")
        requireBalanced(tx.amountMinor, parts)
        raise(TransactionSplit(id, transactionId, tx.revision + 1, parts))
    }

    /** Annulation : la transaction sort des soldes mais reste dans l'audit (FR-22). */
    fun void(transactionId: UUID, expectedRevision: Int?, reason: String?, allowTransfer: Boolean = false) {
        requireOpen()
        val tx = editable(transactionId, expectedRevision, allowTransfer)
        raise(TransactionVoided(id, transactionId, tx.revision + 1, tx.amountMinor, currency, reason))
    }

    fun transaction(transactionId: UUID): TxState =
        state.transactions[transactionId] ?: throw NotFoundException("Transaction", transactionId)

    private fun editable(transactionId: UUID, expectedRevision: Int?, allowTransfer: Boolean = false): TxState {
        val tx = transaction(transactionId)
        if (tx.voided) throw ConflictException("transaction-voided", "Transaction annulée")
        if (tx.transferId != null && !allowTransfer) {
            throw ConflictException("transfer-transaction", "Une transaction de virement se modifie via le virement")
        }
        if (expectedRevision != null && expectedRevision != tx.revision) {
            throw StaleVersionException("Révision ${tx.revision} ≠ $expectedRevision attendue")
        }
        return tx
    }

    private fun requireBalanced(amountMinor: Long, parts: List<TransactionPart>) {
        if (parts.isEmpty()) throw ValidationException("parts-empty", "Au moins une part est requise")
        if (parts.any { it.amountMinor == 0L }) throw ValidationException("zero-part", "Une part ne peut pas être nulle")
        if (parts.sumOf { it.amountMinor } != amountMinor) {
            throw ValidationException("split-unbalanced", "La somme des parts doit être égale au montant")
        }
        if (parts.any { (it.amountMinor > 0) != (amountMinor > 0) }) {
            throw ValidationException("split-sign", "Toutes les parts doivent avoir le signe du montant")
        }
    }

    private fun requireOpen() {
        if (!state.opened) throw NotFoundException("Compte", id)
    }

    private fun requireActive() {
        requireOpen()
        if (state.archived) throw ConflictException("account-archived", "Compte archivé : lecture seule")
    }

    override fun evolve(state: AccountState, event: DomainEvent): AccountState =
        when (event) {
            is AccountOpened ->
                state.copy(opened = true, type = event.type, currency = event.currency, balanceMinor = event.openingBalanceMinor)
            is AccountRenamed -> state
            is AccountArchived -> state.copy(archived = true)
            is AccountReactivated -> state.copy(archived = false)
            is LowBalanceThresholdSet -> state.copy(lowThresholdMinor = event.thresholdMinor)
            is TransactionRecorded ->
                state.copy(
                    balanceMinor = state.balanceMinor + event.amountMinor,
                    transactions =
                        state.transactions +
                            (
                                event.transactionId to
                                    TxState(
                                        event.amountMinor, event.valueDate, event.label, event.merchant, event.merchantHash,
                                        event.labelTokens, event.note, event.tags, event.parts, event.revision, false, event.transferId,
                                    )
                            ),
                )
            is TransactionAmended ->
                updateTx(state, event.transactionId, event.amountMinor - event.previousAmountMinor) {
                    it.copy(
                        amountMinor = event.amountMinor, valueDate = event.valueDate, label = event.label, merchant = event.merchant,
                        merchantHash = event.merchantHash, labelTokens = event.labelTokens, note = event.note, tags = event.tags,
                        parts = event.parts, revision = event.revision,
                    )
                }
            is TransactionRecategorized -> updateTx(state, event.transactionId, 0) { it.copy(parts = event.parts, revision = event.revision) }
            is TransactionSplit -> updateTx(state, event.transactionId, 0) { it.copy(parts = event.parts, revision = event.revision) }
            is TransactionVoided ->
                updateTx(state, event.transactionId, -event.amountMinor) { it.copy(voided = true, revision = event.revision) }
            else -> state
        }

    private fun updateTx(state: AccountState, txId: UUID, balanceDelta: Long, change: (TxState) -> TxState): AccountState =
        state.copy(
            balanceMinor = state.balanceMinor + balanceDelta,
            transactions = state.transactions + (txId to change(state.transactions.getValue(txId))),
        )
}
