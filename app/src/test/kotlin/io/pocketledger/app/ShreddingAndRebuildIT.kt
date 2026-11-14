package io.pocketledger.app

import io.kotest.matchers.shouldBe
import io.pocketledger.app.support.IntegrationTest
import io.pocketledger.shared.ports.FieldCipher
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

class ShreddingAndRebuildIT : IntegrationTest() {
    @Autowired
    lateinit var cipher: FieldCipher

    @Autowired
    lateinit var redis: StringRedisTemplate

    private fun count(table: String, userId: UUID): Long =
        jdbc.sql("SELECT count(*) FROM $table WHERE user_id = :u").param("u", userId).query(Long::class.javaObjectType).single()

    @Test
    fun `après crypto-shredding aucune donnée lisible ne subsiste`() {
        val session = register()
        val accountId = openAccount(session)
        call(
            HttpMethod.POST, "/api/v1/transactions", session,
            mapOf(
                "accountId" to accountId, "amount" to "-42", "valueDate" to LocalDate.now().toString(),
                "label" to "Pharmacie Centrale", "categoryId" to "health.pharmacy",
            ),
        )
        await.ignoreExceptions() atMost Duration.ofSeconds(60) untilAsserted {
            listOf("transaction_view", "analytics_tx", "alert_tx", "budget_tx").forEach { count(it, session.userId) shouldBe 1L }
        }
        call(HttpMethod.GET, "/api/v1/analytics/spending", session) // remplit le cache Redis

        call(HttpMethod.POST, "/api/v1/me/delete", session, mapOf("password" to "correct-horse-battery")).statusCode.value() shouldBe 204

        await.ignoreExceptions() atMost Duration.ofSeconds(60) untilAsserted {
            listOf("account_view", "transaction_view", "analytics_tx_part", "analytics_tx", "alert_tx", "budget_tx").forEach {
                count(it, session.userId) shouldBe 0L
            }
        }
        jdbc.sql("SELECT count(*) FROM user_keys WHERE user_id = :u").param("u", session.userId).query(Long::class.javaObjectType).single() shouldBe 0L
        // Les événements restent (intégrité) mais leurs champs sensibles sont indéchiffrables.
        val labels =
            jdbc
                .sql("SELECT payload->>'label' FROM event_store WHERE user_id = :u AND event_type = 'TransactionRecorded'")
                .param("u", session.userId)
                .query(String::class.java)
                .list()
        labels.size shouldBe 1
        cipher.decrypt(session.userId, labels.single()) shouldBe null
        redis.keys("analytics:${session.userId}:*").orEmpty().size shouldBe 0
        // Le jeton d'accès reste valide jusqu'à expiration, mais la session ne peut plus être prolongée.
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, mapOf("refreshToken" to session.refreshToken)).statusCode.value() shouldBe 401
    }

    @Test
    fun `une projection reconstruite depuis l'event store égale la projection construite en flux`() {
        val session = register()
        val accountId = openAccount(session, opening = "200")
        repeat(5) { i ->
            call(
                HttpMethod.POST, "/api/v1/transactions", session,
                mapOf("accountId" to accountId, "amount" to "-${i + 1}", "valueDate" to LocalDate.now().toString(), "label" to "Achat $i"),
            )
        }
        await.ignoreExceptions() atMost Duration.ofSeconds(30) untilAsserted {
            call(HttpMethod.GET, "/api/v1/accounts/$accountId", session).body!!["balance"]["amount"].asText() shouldBe "185.00"
        }
        val before = jdbc.sql("SELECT balance_minor, version FROM account_view WHERE account_id = :a").param("a", accountId)
            .query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.single()

        val admin = register(email = "admin@pocketledger.test")
        val rebuild = call(HttpMethod.POST, "/api/v1/admin/projections/ledger-account-view/rebuild", admin)
        rebuild.statusCode.value() shouldBe 200

        val after = jdbc.sql("SELECT balance_minor, version FROM account_view WHERE account_id = :a").param("a", accountId)
            .query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.single()
        after shouldBe before
        call(HttpMethod.POST, "/api/v1/admin/reconciliation/run", admin).body!!["mismatches"].asInt() shouldBe 0
    }

    @Test
    fun `un utilisateur ne voit pas les ressources d'un autre`() {
        val alice = register()
        val bob = register()
        val accountId = openAccount(alice)
        call(HttpMethod.GET, "/api/v1/accounts/$accountId", bob).statusCode.value() shouldBe 404
        call(
            HttpMethod.POST, "/api/v1/transactions", bob,
            mapOf("accountId" to accountId, "amount" to "-1", "valueDate" to LocalDate.now().toString(), "label" to "x"),
        ).statusCode.value() shouldBe 404
        call(HttpMethod.POST, "/api/v1/admin/projections/ledger-account-view/rebuild", bob).statusCode.value() shouldBe 403
    }
}
