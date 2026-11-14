package io.pocketledger.ledger.application

import io.pocketledger.events.TransactionPart
import io.pocketledger.ledger.domain.Account
import io.pocketledger.ledger.domain.TxText
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.CurrencyMismatchException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CategoryDirectory
import io.pocketledger.shared.ports.CategorySuggester
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

data class PartInput(val categoryId: String?, val amount: String)

data class RecordTransactionCommand(
    val accountId: UUID,
    val amount: String,
    val currency: String?,
    val valueDate: LocalDate,
    val label: String,
    val merchant: String? = null,
    val categoryId: String? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val parts: List<PartInput>? = null,
    val transactionId: UUID? = null,
    val importId: UUID? = null,
)

data class AmendTransactionCommand(
    val amount: String? = null,
    val valueDate: LocalDate? = null,
    val label: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val tags: List<String>? = null,
    val categoryId: String? = null,
    val parts: List<PartInput>? = null,
)

data class TransactionRef(val transactionId: UUID, val accountId: UUID, val revision: Int, val aggregateVersion: Int)

@Service
class TransactionService(
    private val accounts: AccountRepository,
    private val index: LedgerWriteIndex,
    private val cipher: FieldCipher,
    private val money: MoneyFormat,
    private val suggester: CategorySuggester,
    private val categories: CategoryDirectory,
    private val commands: CommandExecutor,
) {
    fun record(userId: UUID, command: RecordTransactionCommand): TransactionRef =
        commands.execute {
            index.requireOwner(command.accountId, userId)
            val account = accounts.get(command.accountId)
            if (command.currency != null && command.currency.uppercase() != account.currency) {
                throw CurrencyMismatchException(CurrencyCode(account.currency), money.currency(command.currency))
            }
            val amount = money.parse(command.amount, account.currency).amountMinor
            val parts =
                command.parts?.let { parse(it, account) }
                    ?: listOf(
                        TransactionPart(
                            command.categoryId ?: suggester.suggest(userId, command.label, command.merchant),
                            amount,
                        ),
                    )
            validateCategories(userId, parts)
            val transactionId = command.transactionId ?: UUID.randomUUID()
            account.record(
                transactionId, amount, command.valueDate,
                text(userId, command.label, command.merchant, command.note, command.tags), parts, importId = command.importId,
            )
            accounts.save(account, userId)
            index.registerTransaction(transactionId, account.id, userId)
            TransactionRef(transactionId, account.id, 1, account.version)
        }

    fun amend(userId: UUID, transactionId: UUID, expectedRevision: Int?, command: AmendTransactionCommand): TransactionRef =
        mutate(userId, transactionId) { account ->
            val current = account.transaction(transactionId)
            val onlyCategory =
                command.categoryId != null &&
                    listOf(command.amount, command.valueDate, command.label, command.merchant, command.note, command.tags, command.parts)
                        .all { it == null }
            if (onlyCategory) {
                validateCategories(userId, listOf(TransactionPart(command.categoryId, current.amountMinor)))
                account.recategorize(transactionId, expectedRevision, command.categoryId)
                return@mutate
            }
            val amount = command.amount?.let { money.parse(it, account.currency).amountMinor }
            var parts = command.parts?.let { parse(it, account) }
            if (parts == null && command.categoryId != null) {
                parts = listOf(TransactionPart(command.categoryId, amount ?: current.amountMinor))
            }
            parts?.let { validateCategories(userId, it) }
            val textChanged = listOf(command.label, command.merchant, command.note, command.tags).any { it != null }
            val text =
                if (textChanged) {
                    text(
                        userId,
                        command.label ?: decrypt(userId, current.label),
                        command.merchant ?: current.merchant?.let { decrypt(userId, it) },
                        command.note ?: current.note?.let { decrypt(userId, it) },
                        command.tags ?: current.tags,
                    )
                } else {
                    null
                }
            account.amend(transactionId, expectedRevision, amount, command.valueDate, text, parts)
        }

    fun split(userId: UUID, transactionId: UUID, expectedRevision: Int?, parts: List<PartInput>): TransactionRef =
        mutate(userId, transactionId) { account ->
            val parsed = parse(parts, account)
            validateCategories(userId, parsed)
            account.split(transactionId, expectedRevision, parsed)
        }

    fun void(userId: UUID, transactionId: UUID, expectedRevision: Int?, reason: String?): TransactionRef =
        mutate(userId, transactionId) { it.void(transactionId, expectedRevision, reason) }

    private fun mutate(userId: UUID, transactionId: UUID, change: (Account) -> Unit): TransactionRef =
        commands.execute {
            val accountId = index.accountOf(transactionId, userId)
            val account = accounts.get(accountId)
            change(account)
            accounts.save(account, userId)
            TransactionRef(transactionId, accountId, account.transaction(transactionId).revision, account.version)
        }

    private fun parse(parts: List<PartInput>, account: Account): List<TransactionPart> =
        parts.map { TransactionPart(it.categoryId, money.parse(it.amount, account.currency).amountMinor) }

    private fun validateCategories(userId: UUID, parts: List<TransactionPart>) {
        parts.mapNotNull { it.categoryId }.distinct().forEach {
            if (!categories.exists(userId, it)) throw ValidationException("unknown-category", "Catégorie inconnue : $it")
        }
    }

    private fun decrypt(userId: UUID, value: String): String = cipher.decrypt(userId, value) ?: ""

    private fun text(userId: UUID, label: String, merchant: String?, note: String?, tags: List<String>): TxText {
        if (label.isBlank()) throw ValidationException("label-required", "Libellé requis")
        val cleanMerchant = merchant?.trim()?.takeIf { it.isNotEmpty() }
        return TxText(
            label = cipher.encrypt(userId, label.trim()),
            merchant = cleanMerchant?.let { cipher.encrypt(userId, it) },
            merchantHash = cleanMerchant?.let { cipher.blindIndex(userId, it) },
            labelTokens = cipher.blindTokens(userId, listOfNotNull(label, cleanMerchant).joinToString(" ")),
            note = note?.takeIf { it.isNotBlank() }?.let { cipher.encrypt(userId, it) },
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(20),
        )
    }
}
