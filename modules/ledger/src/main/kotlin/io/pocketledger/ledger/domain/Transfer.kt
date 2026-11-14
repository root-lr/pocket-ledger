package io.pocketledger.ledger.domain

import io.pocketledger.events.DomainEvent
import io.pocketledger.events.ImportCompleted
import io.pocketledger.events.ImportRowSkippedAsDuplicate
import io.pocketledger.events.ImportStarted
import io.pocketledger.events.TransferCompensated
import io.pocketledger.events.TransferCompleted
import io.pocketledger.events.TransferInitiated
import io.pocketledger.platform.eventstore.EventSourcedAggregate
import io.pocketledger.shared.ConflictException
import java.time.LocalDate
import java.util.UUID

enum class TransferStatus { NONE, INITIATED, COMPLETED, COMPENSATED }

data class TransferState(
    val status: TransferStatus = TransferStatus.NONE,
    val fromAccountId: UUID? = null,
    val toAccountId: UUID? = null,
    val debitMinor: Long = 0,
    val creditMinor: Long = 0,
    val feeMinor: Long = 0,
    val valueDate: LocalDate? = null,
    val label: String? = null,
)

/** Virement interne : saga en deux étapes (débit, crédit) avec compensation (FR-23, FR-92). */
class Transfer(id: UUID) : EventSourcedAggregate<TransferState>(id, TransferState()) {
    val debitTransactionId: UUID get() = UUID.nameUUIDFromBytes("transfer-debit:$id".toByteArray())
    val creditTransactionId: UUID get() = UUID.nameUUIDFromBytes("transfer-credit:$id".toByteArray())

    fun initiate(
        fromAccountId: UUID,
        toAccountId: UUID,
        debitMinor: Long,
        debitCurrency: String,
        creditMinor: Long,
        creditCurrency: String,
        feeMinor: Long,
        valueDate: LocalDate,
        label: String,
    ) {
        if (state.status != TransferStatus.NONE) throw ConflictException("transfer-exists", "Virement déjà initié")
        if (fromAccountId == toAccountId) throw ConflictException("same-account", "Comptes source et destination identiques")
        if (debitMinor <= 0 || creditMinor <= 0 || feeMinor < 0) {
            throw ConflictException("invalid-transfer-amount", "Montants de virement invalides")
        }
        val impliedRate =
            if (debitCurrency == creditCurrency) {
                null
            } else {
                // Taux réellement appliqué par la banque, en unités mineures (les décimales sont reprises côté lecture).
                creditMinor.toBigDecimal().divide(debitMinor.toBigDecimal(), java.math.MathContext.DECIMAL64).toPlainString()
            }
        raise(
            TransferInitiated(
                id, fromAccountId, toAccountId, debitMinor, debitCurrency, creditMinor, creditCurrency, impliedRate,
                feeMinor, valueDate, label,
            ),
        )
    }

    fun complete() {
        if (state.status == TransferStatus.COMPLETED) return
        if (state.status != TransferStatus.INITIATED) throw ConflictException("transfer-state", "Virement non initié")
        raise(TransferCompleted(id, debitTransactionId, creditTransactionId))
    }

    fun compensate(debitRecorded: Boolean, reason: String) {
        if (state.status != TransferStatus.INITIATED) return
        raise(TransferCompensated(id, if (debitRecorded) debitTransactionId else null, reason))
    }

    override fun evolve(state: TransferState, event: DomainEvent): TransferState =
        when (event) {
            is TransferInitiated ->
                state.copy(
                    status = TransferStatus.INITIATED, fromAccountId = event.fromAccountId, toAccountId = event.toAccountId,
                    debitMinor = event.debitMinor, creditMinor = event.creditMinor, feeMinor = event.feeMinor,
                    valueDate = event.valueDate, label = event.label,
                )
            is TransferCompleted -> state.copy(status = TransferStatus.COMPLETED)
            is TransferCompensated -> state.copy(status = TransferStatus.COMPENSATED)
            else -> state
        }
}

data class ImportState(val started: Boolean = false, val completed: Boolean = false)

/** Journal d'un import (audit) ; l'avancement détaillé vit dans la table import_job. */
class ImportJob(id: UUID) : EventSourcedAggregate<ImportState>(id, ImportState()) {
    fun start(accountId: UUID, fileName: String, fileHash: String, format: String) {
        if (state.started) return
        raise(ImportStarted(id, accountId, fileName, fileHash, format))
    }

    fun skipDuplicate(rowNumber: Int, fingerprint: String) = raise(ImportRowSkippedAsDuplicate(id, rowNumber, fingerprint))

    fun complete(imported: Int, duplicates: Int, failed: Int) {
        if (state.completed) return
        raise(ImportCompleted(id, imported, duplicates, failed))
    }

    override fun evolve(state: ImportState, event: DomainEvent): ImportState =
        when (event) {
            is ImportStarted -> state.copy(started = true)
            is ImportCompleted -> state.copy(completed = true)
            else -> state
        }
}
