package io.pocketledger.ledger.application

import io.pocketledger.events.TransactionPart
import io.pocketledger.ledger.domain.Transfer
import io.pocketledger.ledger.domain.TransferStatus
import io.pocketledger.ledger.domain.TxText
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.DomainException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

data class TransferCommand(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: String,
    val creditAmount: String? = null,
    val fee: String? = null,
    val valueDate: LocalDate,
    val label: String,
)

data class TransferResult(val transferId: UUID, val status: String, val debitTransactionId: UUID, val creditTransactionId: UUID)

/**
 * Saga de virement interne : initier → débiter la source → créditer la destination → terminer.
 * Si le crédit échoue, le débit est annulé (compensation). Chaque étape est idempotente
 * (identifiants de transaction déterministes), ce qui permet la reprise par [resumeStuck].
 */
@Service
class TransferSaga(
    private val transfers: TransferRepository,
    private val accounts: AccountRepository,
    private val index: LedgerWriteIndex,
    private val cipher: FieldCipher,
    private val money: MoneyFormat,
    private val commands: CommandExecutor,
    private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun transfer(userId: UUID, command: TransferCommand): TransferResult {
        index.requireOwner(command.fromAccountId, userId)
        index.requireOwner(command.toAccountId, userId)
        val from = accounts.get(command.fromAccountId)
        val to = accounts.get(command.toAccountId)
        val debit = money.parse(command.amount, from.currency)
        if (debit.amountMinor <= 0) throw ValidationException("invalid-amount", "Le montant du virement doit être positif")
        // Même devise : crédit = débit. Devises différentes : l'utilisateur saisit le montant réellement crédité (FR-92).
        val credit =
            when {
                command.creditAmount != null -> money.parse(command.creditAmount, to.currency)
                from.currency == to.currency -> debit.copy()
                else -> throw ValidationException("credit-amount-required", "Virement entre devises : montant crédité requis")
            }
        val fee = command.fee?.let { money.parse(it, from.currency).amountMinor } ?: 0L

        val transferId = UUID.randomUUID()
        commands.execute {
            val transfer = Transfer(transferId)
            transfer.initiate(
                from.id, to.id, debit.amountMinor, from.currency, credit.amountMinor, to.currency, fee,
                command.valueDate, cipher.encrypt(userId, command.label),
            )
            transfers.save(transfer, userId)
            index.registerTransfer(transferId, userId)
        }
        return resume(userId, transferId)
    }

    fun resume(userId: UUID, transferId: UUID): TransferResult {
        val transfer = transfers.get(transferId)
        if (transfer.state.status == TransferStatus.INITIATED) {
            val s = transfer.state
            val label = cipher.decrypt(userId, s.label!!) ?: "Virement"
            val debitRecorded =
                runStep {
                    record(
                        userId, s.fromAccountId!!, transfer.debitTransactionId, -(s.debitMinor + s.feeMinor), s.valueDate!!, label, transferId,
                        buildList {
                            add(TransactionPart(TRANSFER_CATEGORY, -s.debitMinor))
                            if (s.feeMinor > 0) add(TransactionPart(FEES_CATEGORY, -s.feeMinor))
                        },
                    )
                }
            val creditRecorded =
                debitRecorded &&
                    runStep {
                        record(
                            userId, s.toAccountId!!, transfer.creditTransactionId, s.creditMinor, s.valueDate!!, label, transferId,
                            listOf(TransactionPart(TRANSFER_CATEGORY, s.creditMinor)),
                        )
                    }
            commands.execute {
                val fresh = transfers.get(transferId)
                if (creditRecorded) {
                    fresh.complete()
                } else {
                    if (debitRecorded) voidDebit(userId, s.fromAccountId!!, transfer.debitTransactionId)
                    fresh.compensate(debitRecorded, "Crédit du compte destination impossible")
                }
                transfers.save(fresh, userId)
            }
        }
        val final = transfers.get(transferId)
        return TransferResult(transferId, final.state.status.name, final.debitTransactionId, final.creditTransactionId)
    }

    private fun record(
        userId: UUID,
        accountId: UUID,
        transactionId: UUID,
        amountMinor: Long,
        valueDate: LocalDate,
        label: String,
        transferId: UUID,
        parts: List<TransactionPart>,
    ) {
        if (index.transactionExists(transactionId)) return // étape déjà faite (reprise)
        commands.execute {
            val account = accounts.get(accountId)
            val text =
                TxText(
                    cipher.encrypt(userId, label), null, null, cipher.blindTokens(userId, label), null, listOf("virement"),
                )
            account.record(transactionId, amountMinor, valueDate, text, parts, transferId = transferId)
            accounts.save(account, userId)
            index.registerTransaction(transactionId, accountId, userId)
        }
    }

    private fun voidDebit(userId: UUID, accountId: UUID, transactionId: UUID) {
        val account = accounts.get(accountId)
        if (account.transaction(transactionId).voided) return
        account.void(transactionId, null, "Compensation de virement", allowTransfer = true)
        accounts.save(account, userId)
    }

    private fun runStep(step: () -> Unit): Boolean =
        try {
            step()
            true
        } catch (e: DomainException) {
            log.warn("Étape de virement refusée : {}", e.message)
            false
        }

    /** Reprise des virements restés initiés (crash entre deux étapes). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun resumeStuck() {
        jdbc
            .sql(
                "SELECT transfer_id, user_id FROM transfer_view WHERE status = 'INITIATED' " +
                    "AND created_at < now() - interval '1 minute' LIMIT 100",
            ).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
            .list()
            .forEach { (transferId, userId) ->
                runCatching { resume(userId, transferId) }
                    .onFailure { log.error("Reprise du virement {} impossible", transferId, it) }
            }
    }

    companion object {
        const val TRANSFER_CATEGORY = "transfers.internal"
        const val FEES_CATEGORY = "finance.fees"
    }
}
