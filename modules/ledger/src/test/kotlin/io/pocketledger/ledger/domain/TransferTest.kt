package io.pocketledger.ledger.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pocketledger.events.TransferCompensated
import io.pocketledger.events.TransferInitiated
import io.pocketledger.shared.ConflictException
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class TransferTest {
    private val day = LocalDate.of(2026, 11, 5)

    @Test
    fun `virement entre devises enregistre le taux implicite`() {
        val transfer = Transfer(UUID.randomUUID())
        // 100,00 EUR débités, 65 595 XOF crédités
        transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), 10_000, "EUR", 65_595, "XOF", 0, day, "enc:l")
        val event = transfer.pendingEvents.single() as TransferInitiated
        event.impliedRate shouldBe "6.5595"
        transfer.state.status shouldBe TransferStatus.INITIATED
    }

    @Test
    fun `même compte refusé`() {
        val id = UUID.randomUUID()
        shouldThrow<ConflictException> { Transfer(UUID.randomUUID()).initiate(id, id, 100, "EUR", 100, "EUR", 0, day, "l") }
    }

    @Test
    fun `identifiants de transaction déterministes pour la reprise`() {
        val id = UUID.randomUUID()
        Transfer(id).debitTransactionId shouldBe Transfer(id).debitTransactionId
        Transfer(id).debitTransactionId shouldNotBe Transfer(id).creditTransactionId
    }

    @Test
    fun `compensation après un débit`() {
        val transfer = Transfer(UUID.randomUUID())
        transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), 100, "EUR", 100, "EUR", 0, day, "l")
        transfer.compensate(debitRecorded = true, reason = "compte archivé")
        (transfer.pendingEvents.last() as TransferCompensated).debitTransactionId shouldBe transfer.debitTransactionId
        transfer.state.status shouldBe TransferStatus.COMPENSATED
        shouldThrow<ConflictException> { transfer.complete() }
    }
}
