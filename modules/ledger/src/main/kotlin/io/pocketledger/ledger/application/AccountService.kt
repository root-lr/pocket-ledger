package io.pocketledger.ledger.application

import io.pocketledger.ledger.domain.Account
import io.pocketledger.ledger.domain.AccountType
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.StaleVersionException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class OpenAccountCommand(
    val name: String,
    val type: String,
    val currency: String,
    val openingBalance: String,
    val openedOn: LocalDate?,
)

data class UpdateAccountCommand(val name: String?, val lowBalanceThreshold: String?, val clearLowBalanceThreshold: Boolean = false)

data class AggregateRef(val id: UUID, val version: Int)

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val index: LedgerWriteIndex,
    private val cipher: FieldCipher,
    private val money: MoneyFormat,
    private val commands: CommandExecutor,
    private val clock: Clock,
) {
    fun open(userId: UUID, command: OpenAccountCommand): AggregateRef =
        commands.execute {
            val type =
                runCatching { AccountType.valueOf(command.type.uppercase()) }
                    .getOrElse { throw ValidationException("invalid-account-type", "Type de compte invalide : ${command.type}") }
            if (command.name.isBlank()) throw ValidationException("name-required", "Nom de compte requis")
            val opening = money.parse(command.openingBalance, command.currency)
            val account = Account(UUID.randomUUID())
            account.open(
                cipher.encrypt(userId, command.name.trim()), type, opening.currency.value, opening.amountMinor,
                command.openedOn ?: LocalDate.now(clock),
            )
            accounts.save(account, userId)
            index.registerAccount(account.id, userId)
            AggregateRef(account.id, account.version)
        }

    fun update(userId: UUID, accountId: UUID, expectedVersion: Int, command: UpdateAccountCommand): AggregateRef =
        mutate(userId, accountId, expectedVersion) { account ->
            command.name?.takeIf { it.isNotBlank() }?.let { account.rename(cipher.encrypt(userId, it.trim())) }
            when {
                command.clearLowBalanceThreshold -> account.setLowBalanceThreshold(null)
                command.lowBalanceThreshold != null ->
                    account.setLowBalanceThreshold(money.parse(command.lowBalanceThreshold, account.currency).amountMinor)
            }
        }

    fun archive(userId: UUID, accountId: UUID): AggregateRef = mutate(userId, accountId, null) { it.archive() }

    fun reactivate(userId: UUID, accountId: UUID): AggregateRef = mutate(userId, accountId, null) { it.reactivate() }

    private fun mutate(userId: UUID, accountId: UUID, expectedVersion: Int?, change: (Account) -> Unit): AggregateRef =
        commands.execute {
            index.requireOwner(accountId, userId)
            val account = accounts.get(accountId)
            if (expectedVersion != null && expectedVersion != account.version) {
                throw StaleVersionException("Version ${account.version} ≠ $expectedVersion attendue")
            }
            change(account)
            accounts.save(account, userId)
            AggregateRef(account.id, account.version)
        }
}
