package io.pocketledger.ledger.query

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.ledger.application.LedgerWriteIndex
import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.platform.web.Cursors
import io.pocketledger.platform.web.MoneyDto
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.platform.web.Page
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class AccountDto(
    val id: UUID,
    val name: String?,
    val type: String,
    val currency: String,
    val balance: MoneyDto,
    val openingBalance: MoneyDto,
    val lowBalanceThreshold: MoneyDto?,
    val archived: Boolean,
    val openedOn: LocalDate,
    val version: Int,
)

data class PartDto(val categoryId: String?, val amount: MoneyDto)

data class TransactionDto(
    val id: UUID,
    val accountId: UUID,
    val revision: Int,
    val amount: MoneyDto,
    val valueDate: LocalDate,
    val label: String?,
    val merchant: String?,
    val note: String?,
    val tags: List<String>,
    val parts: List<PartDto>,
    val transferId: UUID?,
    val importId: UUID?,
    val voided: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class TransactionVersionDto(
    val revision: Int,
    val eventType: String,
    val occurredAt: Instant,
    val amount: MoneyDto?,
    val valueDate: LocalDate?,
    val label: String?,
    val parts: List<PartDto>?,
    val reason: String?,
)

data class TransactionFilter(
    val accountId: UUID? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val categoryId: String? = null,
    val tag: String? = null,
    val minAmount: String? = null,
    val maxAmount: String? = null,
    val currency: String? = null,
    val q: String? = null,
    val includeVoided: Boolean = false,
)

data class TransferDto(
    val id: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val debit: MoneyDto,
    val credit: MoneyDto,
    val fee: MoneyDto,
    val impliedRate: String?,
    val valueDate: LocalDate,
    val status: String,
)

@Service
class LedgerQueries(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    private val money: MoneyFormat,
    private val mapper: ObjectMapper,
    private val eventStore: EventStore,
    private val index: LedgerWriteIndex,
) {
    fun accounts(userId: UUID, includeArchived: Boolean = true): List<AccountDto> =
        jdbc
            .sql("$ACCOUNT_SELECT WHERE user_id = :u ${if (includeArchived) "" else "AND NOT archived"} ORDER BY created_at")
            .param("u", userId)
            .query { rs, _ -> account(rs, userId) }
            .list()

    fun account(userId: UUID, accountId: UUID): AccountDto =
        jdbc
            .sql("$ACCOUNT_SELECT WHERE user_id = :u AND account_id = :a")
            .param("u", userId)
            .param("a", accountId)
            .query { rs, _ -> account(rs, userId) }
            .optional()
            .orElseThrow { NotFoundException("Compte", accountId) }

    fun accountViewVersion(accountId: UUID): Int =
        jdbc
            .sql("SELECT version FROM account_view WHERE account_id = :a")
            .param("a", accountId)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(0)

    /** Solde à une date (FR-12) : solde d'ouverture + transactions non annulées jusqu'à cette date incluse. */
    fun balanceAt(userId: UUID, accountId: UUID, date: LocalDate?): MoneyDto {
        val acc = account(userId, accountId)
        if (date == null) return acc.balance
        if (date.isBefore(acc.openedOn)) return money.dto(0, acc.currency)
        val sum =
            jdbc
                .sql(
                    "SELECT COALESCE(SUM(amount_minor), 0) FROM transaction_view " +
                        "WHERE account_id = :a AND NOT voided AND value_date <= :d",
                ).param("a", accountId)
                .param("d", date)
                .query(Long::class.javaObjectType)
                .single()
        return money.dto(acc.openingBalance.amountMinor + sum, acc.currency)
    }

    fun transactions(userId: UUID, filter: TransactionFilter, cursor: String?, limit: Int): Page<TransactionDto> {
        val where = mutableListOf("user_id = :u")
        val params = mutableMapOf<String, Any?>("u" to userId, "limit" to limit + 1)
        if (!filter.includeVoided) where += "NOT voided"
        filter.accountId?.let {
            where += "account_id = :a"
            params["a"] = it
        }
        filter.from?.let {
            where += "value_date >= :from"
            params["from"] = it
        }
        filter.to?.let {
            where += "value_date <= :to"
            params["to"] = it
        }
        filter.categoryId?.let {
            // Une catégorie parente inclut ses sous-catégories ("food" couvre "food.restaurants").
            where += "EXISTS (SELECT 1 FROM unnest(category_ids) c WHERE c = :cat OR c LIKE :catPrefix)"
            params["cat"] = it
            params["catPrefix"] = "$it.%"
        }
        filter.tag?.let {
            where += ":tag = ANY(tags)"
            params["tag"] = it.lowercase()
        }
        if (filter.minAmount != null || filter.maxAmount != null) {
            val currency = filter.currency ?: throw ValidationException("currency-required", "Filtre de montant : préciser la devise")
            where += "currency = :cur"
            params["cur"] = money.currency(currency).value
            filter.minAmount?.let {
                where += "abs(amount_minor) >= :min"
                params["min"] = money.parse(it, currency).amountMinor
            }
            filter.maxAmount?.let {
                where += "abs(amount_minor) <= :max"
                params["max"] = money.parse(it, currency).amountMinor
            }
        }
        filter.q?.takeIf { it.isNotBlank() }?.let {
            val tokens = cipher.blindTokens(userId, it)
            if (tokens.isNotEmpty()) {
                where += "label_tokens @> CAST(:tokens AS text[])"
                params["tokens"] = tokens.toTypedArray()
            }
        }
        Cursors.decode(cursor)?.let { (date, id) ->
            where += "(value_date, transaction_id) < (:cDate, :cId)"
            params["cDate"] = LocalDate.parse(date)
            params["cId"] = UUID.fromString(id)
        }
        val rows =
            jdbc
                .sql("$TX_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY value_date DESC, transaction_id DESC LIMIT :limit")
                .params(params)
                .query { rs, _ -> transaction(rs, userId) }
                .list()
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { Cursors.encode(it.valueDate, it.id) } else null
        return Page(items, next)
    }

    fun transaction(userId: UUID, transactionId: UUID): TransactionDto =
        jdbc
            .sql("$TX_SELECT WHERE user_id = :u AND transaction_id = :t")
            .param("u", userId)
            .param("t", transactionId)
            .query { rs, _ -> transaction(rs, userId) }
            .optional()
            .orElseThrow { NotFoundException("Transaction", transactionId) }

    /** Versions successives d'une transaction (FR-21), relues depuis l'event store. */
    fun history(userId: UUID, transactionId: UUID): List<TransactionVersionDto> {
        val accountId = index.accountOf(transactionId, userId)
        return eventStore.load(accountId).mapNotNull { stored ->
            when (val e = stored.event) {
                is TransactionRecorded ->
                    e.takeIf { it.transactionId == transactionId }?.let {
                        version(e.revision, stored.eventType, stored.occurredAt, e.amountMinor, e.currency, e.valueDate, e.label, e.parts, null, userId)
                    }
                is TransactionAmended ->
                    e.takeIf { it.transactionId == transactionId }?.let {
                        version(e.revision, stored.eventType, stored.occurredAt, e.amountMinor, e.currency, e.valueDate, e.label, e.parts, null, userId)
                    }
                is TransactionRecategorized ->
                    e.takeIf { it.transactionId == transactionId }?.let {
                        TransactionVersionDto(e.revision, stored.eventType, stored.occurredAt, null, null, null, parts(e.parts, null), null)
                    }
                is TransactionSplit ->
                    e.takeIf { it.transactionId == transactionId }?.let {
                        TransactionVersionDto(e.revision, stored.eventType, stored.occurredAt, null, null, null, parts(e.parts, null), null)
                    }
                is TransactionVoided ->
                    e.takeIf { it.transactionId == transactionId }?.let {
                        TransactionVersionDto(e.revision, stored.eventType, stored.occurredAt, money.dto(e.amountMinor, e.currency), null, null, null, e.reason)
                    }
                else -> null
            }
        }
    }

    fun transfer(userId: UUID, transferId: UUID): TransferDto =
        jdbc
            .sql(
                "SELECT transfer_id, from_account_id, to_account_id, debit_minor, debit_currency, credit_minor, credit_currency, " +
                    "fee_minor, implied_rate, value_date, status FROM transfer_view WHERE transfer_id = :t AND user_id = :u",
            ).param("t", transferId)
            .param("u", userId)
            .query { rs, _ ->
                TransferDto(
                    rs.getObject("transfer_id", UUID::class.java),
                    rs.getObject("from_account_id", UUID::class.java),
                    rs.getObject("to_account_id", UUID::class.java),
                    money.dto(rs.getLong("debit_minor"), rs.getString("debit_currency")),
                    money.dto(rs.getLong("credit_minor"), rs.getString("credit_currency")),
                    money.dto(rs.getLong("fee_minor"), rs.getString("debit_currency")),
                    rs.getString("implied_rate"),
                    rs.getObject("value_date", LocalDate::class.java),
                    rs.getString("status"),
                )
            }.optional()
            .orElseThrow { NotFoundException("Virement", transferId) }

    private fun version(
        revision: Int,
        type: String,
        occurredAt: Instant,
        amountMinor: Long,
        currency: String,
        valueDate: LocalDate,
        label: String,
        parts: List<TransactionPart>,
        reason: String?,
        userId: UUID,
    ) = TransactionVersionDto(
        revision, type, occurredAt, money.dto(amountMinor, currency), valueDate, cipher.decrypt(userId, label),
        parts(parts, currency), reason,
    )

    private fun parts(parts: List<TransactionPart>, currency: String?): List<PartDto> =
        parts.map { PartDto(it.categoryId, currency?.let { c -> money.dto(it.amountMinor, c) } ?: MoneyDto(it.amountMinor, "", "")) }

    private fun account(rs: ResultSet, userId: UUID): AccountDto {
        val currency = rs.getString("currency")
        return AccountDto(
            id = rs.getObject("account_id", UUID::class.java),
            name = cipher.decrypt(userId, rs.getString("name_enc")),
            type = rs.getString("type"),
            currency = currency,
            balance = money.dto(rs.getLong("balance_minor"), currency),
            openingBalance = money.dto(rs.getLong("opening_minor"), currency),
            lowBalanceThreshold = rs.getObject("low_threshold_minor")?.let { money.dto((it as Number).toLong(), currency) },
            archived = rs.getBoolean("archived"),
            openedOn = rs.getObject("opened_on", LocalDate::class.java),
            version = rs.getInt("version"),
        )
    }

    private fun transaction(rs: ResultSet, userId: UUID): TransactionDto {
        val currency = rs.getString("currency")

        @Suppress("UNCHECKED_CAST")
        val tags = (rs.getArray("tags")?.array as? Array<String>)?.toList() ?: emptyList()
        val parts = mapper.readValue(rs.getString("parts"), Array<TransactionPart>::class.java).toList()
        return TransactionDto(
            id = rs.getObject("transaction_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            revision = rs.getInt("revision"),
            amount = money.dto(rs.getLong("amount_minor"), currency),
            valueDate = rs.getObject("value_date", LocalDate::class.java),
            label = cipher.decrypt(userId, rs.getString("label_enc")),
            merchant = rs.getString("merchant_enc")?.let { cipher.decrypt(userId, it) },
            note = rs.getString("note_enc")?.let { cipher.decrypt(userId, it) },
            tags = tags,
            parts = parts(parts, currency),
            transferId = rs.getObject("transfer_id", UUID::class.java),
            importId = rs.getObject("import_id", UUID::class.java),
            voided = rs.getBoolean("voided"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        )
    }

    private companion object {
        const val ACCOUNT_SELECT =
            "SELECT account_id, name_enc, type, currency, opening_minor, balance_minor, low_threshold_minor, archived, " +
                "opened_on, version FROM account_view"
        const val TX_SELECT =
            "SELECT transaction_id, account_id, revision, amount_minor, currency, value_date, label_enc, merchant_enc, note_enc, " +
                "tags, parts, transfer_id, import_id, voided, created_at, updated_at FROM transaction_view"
    }
}
