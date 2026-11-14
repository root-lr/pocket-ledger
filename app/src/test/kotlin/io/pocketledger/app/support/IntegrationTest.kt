package io.pocketledger.app.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.debezium.testing.testcontainers.DebeziumContainer
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.lifecycle.Startables
import java.util.UUID

/**
 * Base des tests d'intégration : vraie infrastructure, comme en production.
 * PostgreSQL (wal_level=logical) → Debezium (Kafka Connect) → Kafka → processeurs, plus Redis.
 * Les conteneurs sont partagés par toutes les classes de test (démarrés une fois).
 */
@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "pocketledger.admin-emails=admin@pocketledger.test",
        "pocketledger.kafka.concurrency=1",
        "pocketledger.rate-limit.per-minute=100000",
    ],
)
abstract class IntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcClient

    @Autowired
    protected lateinit var mapper: ObjectMapper

    protected val client: RestClient by lazy { RestClient.create("http://localhost:$port") }

    data class Session(val userId: UUID, val accessToken: String, val refreshToken: String)

    protected fun register(email: String = "user-${UUID.randomUUID()}@pocketledger.test", currency: String = "EUR"): Session {
        val password = "correct-horse-battery"
        val created =
            call(HttpMethod.POST, "/api/v1/auth/register", null, mapOf("email" to email, "password" to password, "referenceCurrency" to currency))
        check(created.statusCode.value() == 201) { "Inscription refusée : ${created.body}" }
        val login = call(HttpMethod.POST, "/api/v1/auth/login", null, mapOf("email" to email, "password" to password, "deviceName" to "test"))
        val body = login.body!!
        return Session(UUID.fromString(created.body!!["userId"].asText()), body["accessToken"].asText(), body["refreshToken"].asText())
    }

    protected fun call(
        method: HttpMethod,
        path: String,
        session: Session?,
        body: Any? = null,
        idempotencyKey: String? = if (method == HttpMethod.GET) null else UUID.randomUUID().toString(),
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<JsonNode> {
        val spec = client.method(method).uri(path)
        session?.let { spec.header("Authorization", "Bearer ${it.accessToken}") }
        idempotencyKey?.let { spec.header("Idempotency-Key", it) }
        headers.forEach { (k, v) -> spec.header(k, v) }
        body?.let { spec.contentType(MediaType.APPLICATION_JSON).body(it) }
        return spec
            .retrieve()
            .onStatus({ true }) { _, _ -> }
            .toEntity(JsonNode::class.java)
    }

    protected fun openAccount(session: Session, currency: String = "EUR", opening: String = "1000"): UUID {
        val response =
            call(
                HttpMethod.POST, "/api/v1/accounts", session,
                mapOf("name" to "Compte courant", "type" to "CHECKING", "currency" to currency, "openingBalance" to opening),
            )
        check(response.statusCode.value() == 201) { "Ouverture refusée : ${response.body}" }
        return UUID.fromString(response.body!!["id"].asText())
    }

    companion object {
        private val network: Network = Network.newNetwork()

        // apply plutôt que des appels chaînés : le type auto-référencé de Testcontainers s'infère mal en Kotlin.
        private val postgres =
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withNetwork(network)
                withNetworkAliases("postgres")
                withDatabaseName("pocketledger")
                withUsername("pocketledger")
                withPassword("pocketledger")
                withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4")
            }

        private val kafka: KafkaContainer =
            KafkaContainer("apache/kafka:3.9.1")
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withListener("kafka:19092")

        private val connect: DebeziumContainer =
            DebeziumContainer("quay.io/debezium/connect:3.1")
                .withNetwork(network)
                .withKafka(network, "kafka:19092")
                .dependsOn(kafka)

        private val redis = GenericContainer<Nothing>("redis:7-alpine").apply { withExposedPorts(6379) }

        init {
            Startables.deepStart(postgres, kafka, connect, redis).join()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("pocketledger.debezium.connect-url", connect::getTarget)
            registry.add("pocketledger.debezium.database.host") { "postgres" }
            registry.add("pocketledger.debezium.database.user", postgres::getUsername)
            registry.add("pocketledger.debezium.database.password", postgres::getPassword)
            registry.add("pocketledger.debezium.database.name", postgres::getDatabaseName)
        }
    }
}
