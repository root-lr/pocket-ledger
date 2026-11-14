package io.pocketledger.ledger

import io.micrometer.core.instrument.MeterRegistry
import io.pocketledger.ledger.application.AccountRepository
import io.pocketledger.ledger.query.LedgerQueries
import io.pocketledger.ledger.query.TransactionFilter
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.SyncItem
import io.pocketledger.shared.ports.SyncSource
import io.pocketledger.shared.ports.UserDataExporter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Réconciliation nocturne (PRD §8) : le solde projeté doit égaler le solde recalculé
 * depuis les événements. Un écart lève une alerte opérateur (log + métrique).
 */
@Component
class BalanceReconciliationJob(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mismatches = meterRegistry.counter("pocketledger.reconciliation.mismatches")
    private val checked = meterRegistry.counter("pocketledger.reconciliation.checked")

    @Scheduled(cron = "\${pocketledger.reconciliation.cron:0 41 2 * * *}", zone = "UTC")
    fun reconcile(): Int {
        var errors = 0
        var after = UUID(0, 0)
        while (true) {
            val batch =
                jdbc
                    .sql("SELECT account_id, balance_minor, version FROM account_view WHERE account_id > :after ORDER BY account_id LIMIT 200")
                    .param("after", after)
                    .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getInt(3)) }
                    .list()
            if (batch.isEmpty()) break
            batch.forEach { (accountId, projected, version) ->
                val aggregate = accounts.find(accountId) ?: return@forEach
                // Une projection en retard n'est pas un écart : on ne compare qu'à version égale.
                if (aggregate.version == version) {
                    checked.increment()
                    if (aggregate.state.balanceMinor != projected) {
                        errors++
                        mismatches.increment()
                        log.error(
                            "Écart de solde sur le compte {} : projeté {}, recalculé {}",
                            accountId, projected, aggregate.state.balanceMinor,
                        )
                    }
                }
            }
            after = batch.last().first
        }
        return errors
    }
}

@Component
class LedgerExporter(private val queries: LedgerQueries) : UserDataExporter {
    override val section = "ledger"

    override fun export(userId: UUID): Any {
        val transactions = mutableListOf<Any>()
        var cursor: String? = null
        do {
            val page = queries.transactions(userId, TransactionFilter(includeVoided = true), cursor, 200)
            transactions.addAll(page.items)
            cursor = page.nextCursor
        } while (cursor != null)
        return mapOf("accounts" to queries.accounts(userId), "transactions" to transactions)
    }
}

/** Sync delta mobile (FR-26) : comptes et transactions modifiés depuis une position globale. */
@Component
class AccountSyncSource(private val jdbc: JdbcClient, private val cipher: FieldCipher, private val money: MoneyFormat) : SyncSource {
    override val section = "accounts"

    override fun changesSince(userId: UUID, position: Long, limit: Int): List<SyncItem> =
        jdbc
            .sql(
                "SELECT account_id, name_enc, type, currency, balance_minor, archived, version, position FROM account_view " +
                    "WHERE user_id = :u AND position > :p ORDER BY position LIMIT :l",
            ).param("u", userId)
            .param("p", position)
            .param("l", limit)
            .query { rs, _ ->
                SyncItem(
                    rs.getObject("account_id", UUID::class.java).toString(),
                    rs.getLong("position"),
                    false,
                    mapOf(
                        "name" to cipher.decrypt(userId, rs.getString("name_enc")),
                        "type" to rs.getString("type"),
                        "balance" to money.dto(rs.getLong("balance_minor"), rs.getString("currency")),
                        "archived" to rs.getBoolean("archived"),
                        "version" to rs.getInt("version"),
                    ),
                )
            }.list()
}

@Component
class TransactionSyncSource(private val jdbc: JdbcClient, private val queries: LedgerQueries) : SyncSource {
    override val section = "transactions"

    override fun changesSince(userId: UUID, position: Long, limit: Int): List<SyncItem> =
        jdbc
            .sql(
                "SELECT transaction_id, position, voided FROM transaction_view WHERE user_id = :u AND position > :p " +
                    "ORDER BY position LIMIT :l",
            ).param("u", userId)
            .param("p", position)
            .param("l", limit)
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getBoolean(3)) }
            .list()
            .map { (id, pos, voided) ->
                SyncItem(id.toString(), pos, voided, if (voided) null else queries.transaction(userId, id))
            }
}
