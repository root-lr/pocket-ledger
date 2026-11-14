package io.pocketledger.app

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pocketledger.app.support.IntegrationTest
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.ignoreExceptions
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.LocalDate

/** Parcours complet : saisie → projection (via Debezium et Kafka) → budget → alerte → analytics. */
class ApiFlowIT : IntegrationTest() {
    private val today = LocalDate.now().toString()

    @Test
    fun `authentification : rotation du refresh token et détection de réutilisation`() {
        val session = register()
        val first = call(HttpMethod.POST, "/api/v1/auth/refresh", null, mapOf("refreshToken" to session.refreshToken))
        first.statusCode.value() shouldBe 200
        val reused = call(HttpMethod.POST, "/api/v1/auth/refresh", null, mapOf("refreshToken" to session.refreshToken))
        reused.statusCode.value() shouldBe 401
        reused.body!!["title"].asText() shouldBe "refresh-token-reused"
        // Toute la famille est révoquée, y compris le jeton obtenu par la rotation.
        val rotated = first.body!!["refreshToken"].asText()
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, mapOf("refreshToken" to rotated)).statusCode.value() shouldBe 401
    }

    @Test
    fun `une transaction met à jour le solde projeté et se retrouve par recherche chiffrée`() {
        val session = register()
        val accountId = openAccount(session, opening = "1000")
        val recorded =
            call(
                HttpMethod.POST, "/api/v1/transactions", session,
                mapOf(
                    "accountId" to accountId, "amount" to "-24.50", "valueDate" to today, "label" to "Boulangerie du Port",
                    "merchant" to "Boulangerie du Port", "categoryId" to "food.groceries",
                ),
            )
        recorded.statusCode.value() shouldBe 201
        val version = recorded.headers.getFirst("X-Aggregate-Version")!!.toInt()

        // Lecture après écriture : la projection est éventuellement cohérente (Debezium → Kafka → projection).
        await.ignoreExceptions() atMost Duration.ofSeconds(30) untilAsserted {
            val account = call(HttpMethod.GET, "/api/v1/accounts/$accountId?minVersion=$version", session)
            account.body!!["balance"]["amount"].asText() shouldBe "975.50"
            account.headers.eTag shouldBe "\"$version\""
        }

        await.ignoreExceptions() atMost Duration.ofSeconds(30) untilAsserted {
            val found = call(HttpMethod.GET, "/api/v1/transactions?q=boulangerie", session)
            found.body!!["items"].size() shouldBe 1
            found.body!!["items"][0]["label"].asText() shouldBe "Boulangerie du Port"
        }
        // En base, le libellé n'apparaît jamais en clair.
        val stored = jdbc.sql("SELECT label_enc FROM transaction_view WHERE account_id = :a").param("a", accountId).query(String::class.java).single()
        stored.startsWith("enc:v1:") shouldBe true
    }

    @Test
    fun `If-Match obsolète refusé, historique des versions conservé`() {
        val session = register()
        val accountId = openAccount(session)
        val txId =
            call(
                HttpMethod.POST, "/api/v1/transactions", session,
                mapOf("accountId" to accountId, "amount" to "-10", "valueDate" to today, "label" to "Taxi", "categoryId" to "transport.taxi"),
            ).body!!["transactionId"].asText()
        call(HttpMethod.PATCH, "/api/v1/transactions/$txId", session, mapOf("amount" to "-12"), headers = mapOf("If-Match" to "\"1\""))
            .statusCode.value() shouldBe 200
        call(HttpMethod.PATCH, "/api/v1/transactions/$txId", session, mapOf("amount" to "-15"), headers = mapOf("If-Match" to "\"1\""))
            .statusCode.value() shouldBe 412
        val history = call(HttpMethod.GET, "/api/v1/transactions/$txId/history", session).body!!
        history.map { it["eventType"].asText() } shouldBe listOf("TransactionRecorded", "TransactionAmended")
    }

    @Test
    fun `un montant trop précis pour la devise est refusé`() {
        val session = register()
        val accountId = openAccount(session, currency = "XOF", opening = "0")
        val response =
            call(
                HttpMethod.POST, "/api/v1/transactions", session,
                mapOf("accountId" to accountId, "amount" to "-1500.5", "valueDate" to today, "label" to "Marché"),
            )
        response.statusCode.value() shouldBe 422
        response.body!!["title"].asText() shouldBe "amount-precision"
    }

    @Test
    fun `budget à 85 pourcent : alerte émise et dépense convertie depuis le XOF`() {
        val session = register(currency = "EUR")
        val accountId = openAccount(session, currency = "XOF", opening = "1000000")
        call(HttpMethod.POST, "/api/v1/budgets", session, mapOf("categoryId" to "food", "limit" to "100"))
            .statusCode.value() shouldBe 201
        // 55 757 XOF ≈ 85,00 € (parité fixe 655,957)
        call(
            HttpMethod.POST, "/api/v1/transactions", session,
            mapOf("accountId" to accountId, "amount" to "-55757", "valueDate" to today, "label" to "Supermarché", "categoryId" to "food.groceries"),
        ).statusCode.value() shouldBe 201

        await.ignoreExceptions() atMost Duration.ofSeconds(60) untilAsserted {
            val status = call(HttpMethod.GET, "/api/v1/budgets/status", session).body!!
            status[0]["spent"]["amount"].asText() shouldBe "85.00"
            status[0]["percentUsed"].asInt() shouldBe 85
        }
        await.ignoreExceptions() atMost Duration.ofSeconds(60) untilAsserted {
            val alerts = call(HttpMethod.GET, "/api/v1/alerts", session).body!!["items"]
            alerts.map { it["type"].asText() } shouldContain "BUDGET_THRESHOLD"
        }
        await.ignoreExceptions() atMost Duration.ofSeconds(60) untilAsserted {
            val spending = call(HttpMethod.GET, "/api/v1/analytics/spending", session).body!!
            spending["currency"].asText() shouldBe "EUR"
            spending["total"]["amount"].asText() shouldBe "85.00"
        }
    }

    @Test
    fun `virement entre devises : débit, crédit et taux implicite`() {
        val session = register()
        val eur = openAccount(session, currency = "EUR", opening = "500")
        val xof = openAccount(session, currency = "XOF", opening = "0")
        val transfer =
            call(
                HttpMethod.POST, "/api/v1/transfers", session,
                mapOf("fromAccountId" to eur, "toAccountId" to xof, "amount" to "100", "creditAmount" to "65000", "valueDate" to today),
            )
        transfer.statusCode.value() shouldBe 201
        transfer.body!!["status"].asText() shouldBe "COMPLETED"
        await.ignoreExceptions() atMost Duration.ofSeconds(30) untilAsserted {
            call(HttpMethod.GET, "/api/v1/accounts/$xof", session).body!!["balance"]["amount"].asText() shouldBe "65000"
            call(HttpMethod.GET, "/api/v1/accounts/$eur", session).body!!["balance"]["amount"].asText() shouldBe "400.00"
        }
        val id = transfer.body!!["transferId"].asText()
        await.ignoreExceptions() atMost Duration.ofSeconds(30) untilAsserted {
            call(HttpMethod.GET, "/api/v1/transfers/$id", session).body!!["impliedRate"].asText() shouldNotBe ""
        }
    }
}
