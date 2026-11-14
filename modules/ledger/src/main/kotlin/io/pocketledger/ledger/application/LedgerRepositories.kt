package io.pocketledger.ledger.application

import io.pocketledger.events.Context
import io.pocketledger.ledger.domain.Account
import io.pocketledger.ledger.domain.AccountState
import io.pocketledger.ledger.domain.ImportJob
import io.pocketledger.ledger.domain.ImportState
import io.pocketledger.ledger.domain.Transfer
import io.pocketledger.ledger.domain.TransferState
import io.pocketledger.platform.eventstore.AggregateRepositories
import io.pocketledger.platform.eventstore.AggregateRepository
import io.pocketledger.shared.NotFoundException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

typealias AccountRepository = AggregateRepository<Account, AccountState>
typealias TransferRepository = AggregateRepository<Transfer, TransferState>
typealias ImportRepository = AggregateRepository<ImportJob, ImportState>

@Configuration
class LedgerRepositories {
    @Bean
    fun accountRepository(factory: AggregateRepositories): AccountRepository =
        factory.create("Account", Context.LEDGER, AccountState::class.java) { Account(it) }

    @Bean
    fun transferRepository(factory: AggregateRepositories): TransferRepository =
        factory.create("Transfer", Context.LEDGER, TransferState::class.java) { Transfer(it) }

    @Bean
    fun importRepository(factory: AggregateRepositories): ImportRepository =
        factory.create("Import", Context.LEDGER, ImportState::class.java) { ImportJob(it) }
}

/**
 * Index côté écriture : propriétaire de chaque compte et compte de chaque transaction,
 * écrit dans la même transaction que les événements (pas de dépendance aux projections).
 */
@Component
class LedgerWriteIndex(private val jdbc: JdbcClient) {
    fun registerAccount(accountId: UUID, userId: UUID) {
        jdbc
            .sql("INSERT INTO ledger_account_owner (account_id, user_id) VALUES (:a, :u)")
            .param("a", accountId)
            .param("u", userId)
            .update()
    }

    fun requireOwner(accountId: UUID, userId: UUID) {
        val owner =
            jdbc
                .sql("SELECT user_id FROM ledger_account_owner WHERE account_id = :a")
                .param("a", accountId)
                .query(UUID::class.java)
                .optional()
                .orElse(null)
        // 404 plutôt que 403 : ne pas révéler l'existence d'une ressource d'un autre utilisateur.
        if (owner != userId) throw NotFoundException("Compte", accountId)
    }

    fun registerTransaction(transactionId: UUID, accountId: UUID, userId: UUID) {
        jdbc
            .sql("INSERT INTO ledger_transaction_index (transaction_id, account_id, user_id) VALUES (:t, :a, :u) ON CONFLICT DO NOTHING")
            .param("t", transactionId)
            .param("a", accountId)
            .param("u", userId)
            .update()
    }

    fun accountOf(transactionId: UUID, userId: UUID): UUID =
        jdbc
            .sql("SELECT account_id FROM ledger_transaction_index WHERE transaction_id = :t AND user_id = :u")
            .param("t", transactionId)
            .param("u", userId)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { NotFoundException("Transaction", transactionId) }

    fun transactionExists(transactionId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM ledger_transaction_index WHERE transaction_id = :t)")
            .param("t", transactionId)
            .query(Boolean::class.javaObjectType)
            .single()

    fun registerTransfer(transferId: UUID, userId: UUID) {
        jdbc
            .sql("INSERT INTO ledger_transfer_owner (transfer_id, user_id) VALUES (:t, :u)")
            .param("t", transferId)
            .param("u", userId)
            .update()
    }

    fun requireTransferOwner(transferId: UUID, userId: UUID) {
        val owner =
            jdbc
                .sql("SELECT user_id FROM ledger_transfer_owner WHERE transfer_id = :t")
                .param("t", transferId)
                .query(UUID::class.java)
                .optional()
                .orElse(null)
        if (owner != userId) throw NotFoundException("Virement", transferId)
    }

    fun ownerOfTransfer(transferId: UUID): UUID? =
        jdbc
            .sql("SELECT user_id FROM ledger_transfer_owner WHERE transfer_id = :t")
            .param("t", transferId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
}
