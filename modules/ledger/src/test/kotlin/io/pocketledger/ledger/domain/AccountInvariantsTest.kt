package io.pocketledger.ledger.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pocketledger.events.AccountArchived
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.AccountReactivated
import io.pocketledger.events.AccountRenamed
import io.pocketledger.events.ImportCompleted
import io.pocketledger.events.ImportRowSkippedAsDuplicate
import io.pocketledger.events.ImportStarted
import io.pocketledger.events.LowBalanceThresholdSet
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecategorized
import io.pocketledger.events.TransactionSplit
import io.pocketledger.events.TransferCompleted
import io.pocketledger.events.TransferInitiated
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.StaleVersionException
import io.pocketledger.shared.ValidationException
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class AccountInvariantsTest {
    private val day = LocalDate.of(2026, 11, 5)
    private val text = TxText("enc:label", "enc:m", "hash", listOf("t"), "enc:note", listOf("tag"))

    private fun openedAccount(balance: Long = 0): Account =
        Account(UUID.randomUUID()).apply {
            replay(AccountOpened(id, "enc:name", "CHECKING", "EUR", balance, day), 1)
        }

    private fun Account.withTx(amount: Long = -1000, category: String? = "food"): UUID {
        val tx = UUID.randomUUID()
        record(tx, amount, day, text, listOf(TransactionPart(category, amount)))
        markCommitted()
        return tx
    }

    @Test
    fun `un compte non ouvert refuse toute opération`() {
        val account = Account(UUID.randomUUID())
        shouldThrow<NotFoundException> { account.rename("x") }
        shouldThrow<NotFoundException> { account.archive() }
        shouldThrow<NotFoundException> { account.reactivate() }
        shouldThrow<NotFoundException> { account.setLowBalanceThreshold(1) }
        shouldThrow<NotFoundException> { account.void(UUID.randomUUID(), null, null) }
        shouldThrow<IllegalStateException> { account.currency }
    }

    @Test
    fun `ouvrir deux fois est refusé`() {
        val account = openedAccount()
        shouldThrow<ConflictException> { account.open("n", AccountType.CASH, "EUR", 0, day) }
    }

    @Test
    fun `renommer, archiver et réactiver émettent un seul événement chacun`() {
        val account = openedAccount()
        account.rename("enc:autre")
        account.archive()
        account.archive()
        account.reactivate()
        account.reactivate()
        val events = account.pendingEvents
        events shouldHaveSize 3
        events[0].shouldBeInstanceOf<AccountRenamed>()
        events[1].shouldBeInstanceOf<AccountArchived>()
        events[2].shouldBeInstanceOf<AccountReactivated>()
        account.state.archived shouldBe false
    }

    @Test
    fun `seuil de solde bas défini puis retiré`() {
        val account = openedAccount()
        account.setLowBalanceThreshold(5000)
        account.state.lowThresholdMinor shouldBe 5000
        account.setLowBalanceThreshold(null)
        account.state.lowThresholdMinor shouldBe null
        (account.pendingEvents.first() as LowBalanceThresholdSet).thresholdMinor shouldBe 5000
    }

    @Test
    fun `seuil refusé sur un compte archivé`() {
        val account = openedAccount()
        account.archive()
        shouldThrow<ConflictException> { account.setLowBalanceThreshold(1) }
    }

    @Test
    fun `parts vides ou nulles refusées`() {
        val account = openedAccount()
        shouldThrow<ValidationException> { account.record(UUID.randomUUID(), -100, day, text, emptyList()) }.code shouldBe "parts-empty"
        shouldThrow<ValidationException> {
            account.record(UUID.randomUUID(), -100, day, text, listOf(TransactionPart("a", -100), TransactionPart("b", 0)))
        }.code shouldBe "zero-part"
    }

    @Test
    fun `une transaction enregistrée garde tous ses champs`() {
        val account = openedAccount()
        val tx = account.withTx(-2500, "food.coffee")
        val state = account.transaction(tx)
        state.amountMinor shouldBe -2500
        state.valueDate shouldBe day
        state.label shouldBe "enc:label"
        state.merchant shouldBe "enc:m"
        state.merchantHash shouldBe "hash"
        state.labelTokens shouldBe listOf("t")
        state.note shouldBe "enc:note"
        state.tags shouldBe listOf("tag")
        state.revision shouldBe 1
        state.voided shouldBe false
        state.transferId shouldBe null
        shouldThrow<NotFoundException> { account.transaction(UUID.randomUUID()) }
    }

    @Test
    fun `corriger le texte et la date sans changer le montant garde les parts`() {
        val account = openedAccount()
        val tx = account.withTx(-1000)
        val newText = TxText("enc:new", null, null, emptyList(), null, emptyList())
        account.amend(tx, null, null, day.plusDays(1), newText, null)
        val event = account.pendingEvents.single() as TransactionAmended
        event.amountMinor shouldBe -1000
        event.previousAmountMinor shouldBe -1000
        event.valueDate shouldBe day.plusDays(1)
        event.label shouldBe "enc:new"
        event.parts shouldBe listOf(TransactionPart("food", -1000))
        account.state.balanceMinor shouldBe -1000
        account.transaction(tx).label shouldBe "enc:new"
        account.transaction(tx).revision shouldBe 2
    }

    @Test
    fun `corriger avec de nouvelles parts explicites`() {
        val account = openedAccount()
        val tx = account.withTx(-1000)
        account.amend(tx, 1, -1200, null, null, listOf(TransactionPart("a", -700), TransactionPart("b", -500)))
        account.transaction(tx).parts shouldHaveSize 2
        account.state.balanceMinor shouldBe -1200
        shouldThrow<ValidationException> { account.amend(tx, 2, 0, null, null, null) }.code shouldBe "zero-amount"
    }

    @Test
    fun `recatégoriser change les parts, sans effet si identique`() {
        val account = openedAccount()
        val tx = account.withTx(-1000, "food")
        account.recategorize(tx, 1, "food")
        account.pendingEvents.shouldBeEmpty()
        account.recategorize(tx, 1, "leisure")
        (account.pendingEvents.single() as TransactionRecategorized).parts shouldBe listOf(TransactionPart("leisure", -1000))
        account.transaction(tx).revision shouldBe 2
        account.state.balanceMinor shouldBe -1000
        shouldThrow<StaleVersionException> { account.recategorize(tx, 1, "food") }
    }

    @Test
    fun `répartir exige au moins deux parts équilibrées`() {
        val account = openedAccount()
        val tx = account.withTx(-1000)
        shouldThrow<ValidationException> { account.split(tx, 1, listOf(TransactionPart("a", -1000))) }.code shouldBe "split-too-small"
        shouldThrow<ValidationException> {
            account.split(tx, 1, listOf(TransactionPart("a", -400), TransactionPart("b", -400)))
        }.code shouldBe "split-unbalanced"
        account.split(tx, 1, listOf(TransactionPart("a", -400), TransactionPart("b", -600)))
        (account.pendingEvents.single() as TransactionSplit).revision shouldBe 2
        account.state.balanceMinor shouldBe -1000
    }

    @Test
    fun `une transaction annulée ne se modifie plus`() {
        val account = openedAccount()
        val tx = account.withTx(-1000)
        account.void(tx, 1, "erreur")
        account.transaction(tx).voided shouldBe true
        shouldThrow<ConflictException> { account.amend(tx, null, -5, null, null, null) }.code shouldBe "transaction-voided"
        shouldThrow<ConflictException> { account.recategorize(tx, null, "x") }
    }

    @Test
    fun `revenu positif accepté avec des parts positives`() {
        val account = openedAccount(100)
        account.withTx(250_000, "income.salary")
        account.state.balanceMinor shouldBe 250_100
        shouldThrow<ValidationException> {
            account.record(UUID.randomUUID(), 1000, day, text, listOf(TransactionPart("a", 1500), TransactionPart("b", -500)))
        }.code shouldBe "split-sign"
    }
}

class TransferAndImportTest {
    private val day = LocalDate.of(2026, 11, 5)

    @Test
    fun `virement même devise sans taux implicite, terminé une seule fois`() {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val transfer = Transfer(UUID.randomUUID())
        transfer.initiate(from, to, 5000, "EUR", 5000, "EUR", 150, day, "enc:l")
        val initiated = transfer.pendingEvents.single() as TransferInitiated
        initiated.impliedRate shouldBe null
        initiated.feeMinor shouldBe 150
        transfer.state.fromAccountId shouldBe from
        transfer.state.toAccountId shouldBe to
        transfer.state.debitMinor shouldBe 5000
        transfer.state.creditMinor shouldBe 5000
        transfer.state.feeMinor shouldBe 150
        transfer.state.valueDate shouldBe day
        transfer.complete()
        transfer.complete()
        transfer.pendingEvents.filterIsInstance<TransferCompleted>() shouldHaveSize 1
        transfer.state.status shouldBe TransferStatus.COMPLETED
        transfer.compensate(true, "trop tard")
        transfer.state.status shouldBe TransferStatus.COMPLETED
    }

    @Test
    fun `montants de virement invalides refusés`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        shouldThrow<ConflictException> { Transfer(UUID.randomUUID()).initiate(a, b, 0, "EUR", 1, "EUR", 0, day, "l") }
        shouldThrow<ConflictException> { Transfer(UUID.randomUUID()).initiate(a, b, 1, "EUR", 0, "EUR", 0, day, "l") }
        shouldThrow<ConflictException> { Transfer(UUID.randomUUID()).initiate(a, b, 1, "EUR", 1, "EUR", -1, day, "l") }
        val t = Transfer(UUID.randomUUID())
        t.initiate(a, b, 1, "EUR", 1, "EUR", 0, day, "l")
        shouldThrow<ConflictException> { t.initiate(a, b, 1, "EUR", 1, "EUR", 0, day, "l") }
        shouldThrow<ConflictException> { Transfer(UUID.randomUUID()).complete() }
    }

    @Test
    fun `compensation sans débit enregistré`() {
        val t = Transfer(UUID.randomUUID())
        t.initiate(UUID.randomUUID(), UUID.randomUUID(), 1, "EUR", 1, "EUR", 0, day, "l")
        t.compensate(debitRecorded = false, reason = "compte source archivé")
        t.state.status shouldBe TransferStatus.COMPENSATED
    }

    @Test
    fun `cycle de vie d'un import`() {
        val job = ImportJob(UUID.randomUUID())
        val account = UUID.randomUUID()
        job.start(account, "enc:file", "hash", "CSV")
        job.start(account, "enc:file", "hash", "CSV")
        job.skipDuplicate(3, "fp")
        job.complete(10, 1, 0)
        job.complete(10, 1, 0)
        val events = job.pendingEvents
        events shouldHaveSize 3
        (events[0] as ImportStarted).format shouldBe "CSV"
        (events[1] as ImportRowSkippedAsDuplicate).rowNumber shouldBe 3
        (events[2] as ImportCompleted).imported shouldBe 10
        job.state.started shouldBe true
        job.state.completed shouldBe true
    }
}
