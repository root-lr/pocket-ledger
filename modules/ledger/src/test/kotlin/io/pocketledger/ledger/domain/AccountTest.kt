package io.pocketledger.ledger.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.pocketledger.events.AccountOpened
import io.pocketledger.events.DomainEvent
import io.pocketledger.events.TransactionAmended
import io.pocketledger.events.TransactionPart
import io.pocketledger.events.TransactionRecorded
import io.pocketledger.events.TransactionVoided
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.StaleVersionException
import io.pocketledger.shared.ValidationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Style « given events / when command / then events ». */
class AccountTest {
    private val day = LocalDate.of(2026, 11, 5)
    private val text = TxText("enc:label", null, null, emptyList(), null, emptyList())

    private fun given(vararg events: DomainEvent): Account {
        val account = Account(UUID.randomUUID())
        events.forEachIndexed { i, e -> account.replay(e, i + 1) }
        return account
    }

    private fun opened(balance: Long = 0) = AccountOpened(UUID.randomUUID(), "enc:name", "CHECKING", "EUR", balance, day)

    private fun Account.recordSimple(id: UUID, amount: Long, category: String? = "food") =
        record(id, amount, day, text, listOf(TransactionPart(category, amount)))

    @Test
    fun `ouvrir un compte fixe devise et solde d'ouverture`() {
        val account = Account(UUID.randomUUID())
        account.open("enc:n", AccountType.SAVINGS, "XOF", 50_000, day)
        account.pendingEvents.single().shouldBeInstanceOf<AccountOpened>()
        account.state.balanceMinor shouldBe 50_000
        account.currency shouldBe "XOF"
    }

    @Test
    fun `enregistrer une transaction met à jour le solde`() {
        val account = given(opened(10_000))
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -2_450)
        val event = account.pendingEvents.single() as TransactionRecorded
        event.revision shouldBe 1
        event.currency shouldBe "EUR"
        account.state.balanceMinor shouldBe 7_550
    }

    @Test
    fun `refuse un split déséquilibré ou de signe incohérent`() {
        val account = given(opened())
        shouldThrow<ValidationException> {
            account.record(UUID.randomUUID(), -1000, day, text, listOf(TransactionPart("a", -600), TransactionPart("b", -300)))
        }
        shouldThrow<ValidationException> {
            account.record(UUID.randomUUID(), -1000, day, text, listOf(TransactionPart("a", -1100), TransactionPart("b", 100)))
        }
    }

    @Test
    fun `refuse un montant nul et une transaction en double`() {
        val account = given(opened())
        shouldThrow<ValidationException> { account.recordSimple(UUID.randomUUID(), 0) }
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -100)
        shouldThrow<ConflictException> { account.recordSimple(tx, -100) }
    }

    @Test
    fun `compte archivé en lecture seule`() {
        val account = given(opened())
        account.archive()
        shouldThrow<ConflictException> { account.recordSimple(UUID.randomUUID(), -100) }
        account.reactivate()
        account.recordSimple(UUID.randomUUID(), -100)
    }

    @Test
    fun `corriger une transaction émet une correction avec l'ancien montant`() {
        val account = given(opened(1_000))
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -300)
        account.amend(tx, 1, -500, null, null, null)
        val amended = account.pendingEvents.last() as TransactionAmended
        amended.previousAmountMinor shouldBe -300
        amended.amountMinor shouldBe -500
        amended.parts shouldBe listOf(TransactionPart("food", -500))
        amended.revision shouldBe 2
        account.state.balanceMinor shouldBe 500
    }

    @Test
    fun `une révision obsolète est refusée`() {
        val account = given(opened())
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -300)
        account.amend(tx, 1, -400, null, null, null)
        shouldThrow<StaleVersionException> { account.amend(tx, 1, -500, null, null, null) }
    }

    @Test
    fun `une transaction répartie exige les nouvelles parts si le montant change`() {
        val account = given(opened())
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -1000)
        account.split(tx, 1, listOf(TransactionPart("food", -700), TransactionPart("home", -300)))
        shouldThrow<ValidationException> { account.amend(tx, 2, -1200, null, null, null) }
    }

    @Test
    fun `annuler retire la transaction du solde et bloque les modifications`() {
        val account = given(opened(1_000))
        val tx = UUID.randomUUID()
        account.recordSimple(tx, -300)
        account.void(tx, null, "erreur")
        (account.pendingEvents.last() as TransactionVoided).amountMinor shouldBe -300
        account.state.balanceMinor shouldBe 1_000
        shouldThrow<ConflictException> { account.void(tx, null, null) }
    }

    @Test
    fun `une transaction de virement ne se modifie pas directement`() {
        val account = given(opened())
        val tx = UUID.randomUUID()
        account.record(tx, -100, day, text, listOf(TransactionPart("transfers.internal", -100)), transferId = UUID.randomUUID())
        shouldThrow<ConflictException> { account.void(tx, null, null) }
        account.void(tx, null, "compensation", allowTransfer = true)
        account.pendingEvents shouldHaveSize 2
    }

    @Test
    fun `propriété - le solde égale l'ouverture plus la somme des transactions non annulées`() =
        runBlocking<Unit> {
            checkAll(Arb.long(-1_000_000, 1_000_000), Arb.list(Arb.long(-50_000, 50_000).let { a -> a }, 1..30)) { opening, amounts ->
                val account = given(opened(opening))
                val kept = mutableListOf<Long>()
                amounts.filter { it != 0L }.forEachIndexed { i, amount ->
                    val tx = UUID.randomUUID()
                    account.recordSimple(tx, amount)
                    if (i % 3 == 0) account.void(tx, null, null) else kept += amount
                }
                account.state.balanceMinor shouldBe opening + kept.sum()
            }
        }
}
