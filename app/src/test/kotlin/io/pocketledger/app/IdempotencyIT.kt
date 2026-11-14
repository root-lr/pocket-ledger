package io.pocketledger.app

import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.pocketledger.app.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class IdempotencyIT : IntegrationTest() {
    @Test
    fun `50 envois simultanés de la même clé créent une seule transaction`() {
        val session = register()
        val accountId = openAccount(session)
        val key = UUID.randomUUID().toString()
        val body = mapOf("accountId" to accountId, "amount" to "-9.99", "valueDate" to LocalDate.now().toString(), "label" to "Abonnement")

        val statuses =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                (1..50)
                    .map {
                        executor.submit(
                            Callable { call(HttpMethod.POST, "/api/v1/transactions", session, body, idempotencyKey = key).statusCode.value() },
                        )
                    }
                    .map { it.get() }
            }

        // 201 (première exécution ou rejeu) ou 409 (même clé encore en cours) : jamais de 5xx, jamais de doublon.
        statuses.forAll { it shouldBeIn listOf(201, 409) }
        jdbc
            .sql("SELECT count(*) FROM ledger_transaction_index WHERE account_id = :a")
            .param("a", accountId)
            .query(Long::class.javaObjectType)
            .single() shouldBe 1L

        val replay = call(HttpMethod.POST, "/api/v1/transactions", session, body, idempotencyKey = key)
        replay.statusCode.value() shouldBe 201
        replay.headers.getFirst("Idempotent-Replayed") shouldBe "true"
    }

    @Test
    fun `même clé avec un autre corps est refusée`() {
        val session = register()
        val accountId = openAccount(session)
        val key = UUID.randomUUID().toString()
        val today = LocalDate.now().toString()
        call(HttpMethod.POST, "/api/v1/transactions", session, mapOf("accountId" to accountId, "amount" to "-1", "valueDate" to today, "label" to "A"), key)
            .statusCode.value() shouldBe 201
        val conflict =
            call(HttpMethod.POST, "/api/v1/transactions", session, mapOf("accountId" to accountId, "amount" to "-2", "valueDate" to today, "label" to "A"), key)
        conflict.statusCode.value() shouldBe 422
        conflict.body!!["title"].asText() shouldBe "idempotency-key-reused"
    }

    @Test
    fun `une écriture sans clé est refusée`() {
        val session = register()
        call(HttpMethod.POST, "/api/v1/accounts", session, mapOf("name" to "x", "type" to "CASH", "currency" to "EUR"), idempotencyKey = null)
            .statusCode.value() shouldBe 400
    }
}
