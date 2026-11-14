package io.pocketledger.ledger.projection

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.events.AccountArchived
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.AccountReactivated
import io.pocketledger.events.AccountRenamed
import io.pocketledger.events.Context
import io.pocketledger.events.LowBalanceThresholdSet
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransactionVoided
import io.pocketledger.events.TransferCompensated
import io.pocketledger.events.TransferCompleted
import io.pocketledger.events.TransferInitiated
import io.pocketledger.events.UserKeyShredded
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Read model `account_view` : soldes courants et attributs des comptes. */
@Component
class AccountViewProjection(private val jdbc: JdbcClient) : EventProcessor {
    override val name = "ledger-account-view"
    override val contexts = setOf(Context.LEDGER, Context.IDENTITY)

    override fun handles(eventType: String) = eventType in HANDLED

    override fun process(event: StoredEvent) {
        val e = event.event
        when (e) {
            is AccountOpened ->
                jdbc
                    .sql(
                        """
                        INSERT INTO account_view (account_id, user_id, name_enc, type, currency, opening_minor, balance_minor,
                                                  archived, opened_on, version, position, created_at, updated_at)
                        VALUES (:id, :u, :n, :t, :c, :o, :o, false, :d, :v, :p, :at, :at)
                        ON CONFLICT (account_id) DO NOTHING
                        """.trimIndent(),
                    ).param("id", e.accountId)
                    .param("u", event.userId)
                    .param("n", e.name)
                    .param("t", e.type)
                    .param("c", e.currency)
                    .param("o", e.openingBalanceMinor)
                    .param("d", e.openedOn)
                    .param("v", event.version)
                    .param("p", event.globalPosition)
                    .param("at", at(event))
                    .update()
            is AccountRenamed -> update(event, "name_enc = :x", e.name)
            is AccountArchived -> update(event, "archived = true", null)
            is AccountReactivated -> update(event, "archived = false", null)
            is LowBalanceThresholdSet -> update(event, "low_threshold_minor = :x", e.thresholdMinor)
            is TransactionRecorded -> update(event, "balance_minor = balance_minor + :x", e.amountMinor)
            is TransactionAmended -> update(event, "balance_minor = balance_minor + :x", e.amountMinor - e.previousAmountMinor)
            is TransactionVoided -> update(event, "balance_minor = balance_minor - :x", e.amountMinor)
            is TransactionRecategorized, is TransactionSplit -> update(event, "balance_minor = balance_minor", null)
            is UserKeyShredded -> resetUser(event.userId)
            else -> Unit
        }
    }

    private fun update(event: StoredEvent, set: String, value: Any?) {
        val spec =
            jdbc
                .sql("UPDATE account_view SET $set, version = :v, position = :p, updated_at = :at WHERE account_id = :id")
                .param("v", event.version)
                .param("p", event.globalPosition)
                .param("at", at(event))
                .param("id", event.aggregateId)
        if (set.contains(":x")) spec.param("x", value)
        spec.update()
    }

    override fun reset() {
        jdbc.sql("TRUNCATE account_view").update()
    }

    override fun resetUser(userId: UUID) {
        jdbc.sql("DELETE FROM account_view WHERE user_id = :u").param("u", userId).update()
    }

    private companion object {
        val HANDLED =
            setOf(
                "AccountOpened", "AccountRenamed", "AccountArchived", "AccountReactivated", "LowBalanceThresholdSet",
                "TransactionRecorded", "TransactionAmended", "TransactionVoided", "TransactionRecategorized", "TransactionSplit",
                "UserKeyShredded",
            )
    }
}

/** Read model `transaction_view` : recherche, filtres, sync mobile. */
@Component
class TransactionViewProjection(private val jdbc: JdbcClient, private val mapper: ObjectMapper) : EventProcessor {
    override val name = "ledger-transaction-view"
    override val contexts = setOf(Context.LEDGER, Context.IDENTITY)

    override fun handles(eventType: String) =
        eventType in
            setOf(
                "TransactionRecorded", "TransactionAmended", "TransactionVoided", "TransactionRecategorized",
                "TransactionSplit", "UserKeyShredded",
            )

    override fun process(event: StoredEvent) {
        when (val e = event.event) {
            is TransactionRecorded ->
                jdbc
                    .sql(
                        """
                        INSERT INTO transaction_view (transaction_id, account_id, user_id, revision, amount_minor, currency, value_date,
                            label_enc, merchant_enc, merchant_hash, label_tokens, note_enc, tags, parts, category_ids,
                            transfer_id, import_id, voided, position, created_at, updated_at)
                        VALUES (:id, :a, :u, :r, :amt, :c, :d, :l, :m, :mh, :tok, :n, :tags, CAST(:parts AS jsonb), :cats,
                            :tr, :imp, false, :p, :at, :at)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """.trimIndent(),
                    ).param("id", e.transactionId)
                    .param("a", e.accountId)
                    .param("u", event.userId)
                    .param("r", e.revision)
                    .param("amt", e.amountMinor)
                    .param("c", e.currency)
                    .param("d", e.valueDate)
                    .param("l", e.label)
                    .param("m", e.merchant)
                    .param("mh", e.merchantHash)
                    .param("tok", e.labelTokens.toTypedArray())
                    .param("n", e.note)
                    .param("tags", e.tags.toTypedArray())
                    .param("parts", mapper.writeValueAsString(e.parts))
                    .param("cats", e.parts.mapNotNull { it.categoryId }.distinct().toTypedArray())
                    .param("tr", e.transferId)
                    .param("imp", e.importId)
                    .param("p", event.globalPosition)
                    .param("at", at(event))
                    .update()
            is TransactionAmended ->
                jdbc
                    .sql(
                        """
                        UPDATE transaction_view SET revision = :r, amount_minor = :amt, value_date = :d, label_enc = :l,
                            merchant_enc = :m, merchant_hash = :mh, label_tokens = :tok, note_enc = :n, tags = :tags,
                            parts = CAST(:parts AS jsonb), category_ids = :cats, position = :p, updated_at = :at
                        WHERE transaction_id = :id
                        """.trimIndent(),
                    ).param("r", e.revision)
                    .param("amt", e.amountMinor)
                    .param("d", e.valueDate)
                    .param("l", e.label)
                    .param("m", e.merchant)
                    .param("mh", e.merchantHash)
                    .param("tok", e.labelTokens.toTypedArray())
                    .param("n", e.note)
                    .param("tags", e.tags.toTypedArray())
                    .param("parts", mapper.writeValueAsString(e.parts))
                    .param("cats", e.parts.mapNotNull { it.categoryId }.distinct().toTypedArray())
                    .param("p", event.globalPosition)
                    .param("at", at(event))
                    .param("id", e.transactionId)
                    .update()
            is TransactionRecategorized -> updateParts(event, e.transactionId, e.revision, e.parts)
            is TransactionSplit -> updateParts(event, e.transactionId, e.revision, e.parts)
            is TransactionVoided ->
                jdbc
                    .sql("UPDATE transaction_view SET voided = true, revision = :r, position = :p, updated_at = :at WHERE transaction_id = :id")
                    .param("r", e.revision)
                    .param("p", event.globalPosition)
                    .param("at", at(event))
                    .param("id", e.transactionId)
                    .update()
            is UserKeyShredded -> resetUser(event.userId)
            else -> Unit
        }
    }

    private fun updateParts(event: StoredEvent, txId: UUID, revision: Int, parts: List<io.pocketledger.events.TransactionPart>) {
        jdbc
            .sql(
                """
                UPDATE transaction_view SET revision = :r, parts = CAST(:parts AS jsonb), category_ids = :cats,
                    position = :p, updated_at = :at WHERE transaction_id = :id
                """.trimIndent(),
            ).param("r", revision)
            .param("parts", mapper.writeValueAsString(parts))
            .param("cats", parts.mapNotNull { it.categoryId }.distinct().toTypedArray())
            .param("p", event.globalPosition)
            .param("at", at(event))
            .param("id", txId)
            .update()
    }

    override fun reset() {
        jdbc.sql("TRUNCATE transaction_view").update()
    }

    override fun resetUser(userId: UUID) {
        jdbc.sql("DELETE FROM transaction_view WHERE user_id = :u").param("u", userId).update()
    }
}

/** Read model `transfer_view` : suivi des virements et reprise des sagas bloquées. */
@Component
class TransferViewProjection(private val jdbc: JdbcClient) : EventProcessor {
    override val name = "ledger-transfer-view"
    override val contexts = setOf(Context.LEDGER, Context.IDENTITY)

    override fun handles(eventType: String) =
        eventType in setOf("TransferInitiated", "TransferCompleted", "TransferCompensated", "UserKeyShredded")

    override fun process(event: StoredEvent) {
        when (val e = event.event) {
            is TransferInitiated ->
                jdbc
                    .sql(
                        """
                        INSERT INTO transfer_view (transfer_id, user_id, from_account_id, to_account_id, debit_minor, debit_currency,
                            credit_minor, credit_currency, fee_minor, implied_rate, value_date, status, created_at, updated_at, position)
                        VALUES (:id, :u, :f, :t, :dm, :dc, :cm, :cc, :fee, :rate, :d, 'INITIATED', :at, :at, :p)
                        ON CONFLICT (transfer_id) DO NOTHING
                        """.trimIndent(),
                    ).param("id", e.transferId)
                    .param("u", event.userId)
                    .param("f", e.fromAccountId)
                    .param("t", e.toAccountId)
                    .param("dm", e.debitMinor)
                    .param("dc", e.debitCurrency)
                    .param("cm", e.creditMinor)
                    .param("cc", e.creditCurrency)
                    .param("fee", e.feeMinor)
                    .param("rate", e.impliedRate)
                    .param("d", e.valueDate)
                    .param("at", at(event))
                    .param("p", event.globalPosition)
                    .update()
            is TransferCompleted -> status(event, e.transferId, "COMPLETED")
            is TransferCompensated -> status(event, e.transferId, "COMPENSATED")
            is UserKeyShredded -> resetUser(event.userId)
            else -> Unit
        }
    }

    private fun status(event: StoredEvent, transferId: UUID, status: String) {
        jdbc
            .sql("UPDATE transfer_view SET status = :s, updated_at = :at, position = :p WHERE transfer_id = :id")
            .param("s", status)
            .param("at", at(event))
            .param("p", event.globalPosition)
            .param("id", transferId)
            .update()
    }

    override fun reset() {
        jdbc.sql("TRUNCATE transfer_view").update()
    }

    override fun resetUser(userId: UUID) {
        jdbc.sql("DELETE FROM transfer_view WHERE user_id = :u").param("u", userId).update()
    }
}

internal fun at(event: StoredEvent): OffsetDateTime = OffsetDateTime.ofInstant(event.occurredAt, ZoneOffset.UTC)
