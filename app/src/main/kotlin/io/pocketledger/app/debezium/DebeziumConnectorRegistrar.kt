package io.pocketledger.app.debezium

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Enregistre (ou met à jour) le connecteur Debezium au démarrage via l'API REST de Kafka Connect.
 * PUT /connectors/{name}/config est idempotent. Désactivé si aucune URL n'est configurée.
 * Lancé après Flyway : la publication `pocketledger_outbox` existe déjà.
 */
@Component
class DebeziumConnectorRegistrar(
    @Value("\${pocketledger.debezium.connect-url:}") private val connectUrl: String,
    @Value("\${pocketledger.debezium.connector-name:pocketledger-outbox}") private val connectorName: String,
    @Value("\${pocketledger.debezium.slot-name:pocketledger_outbox}") private val slotName: String,
    @Value("\${pocketledger.debezium.database.host:postgres}") private val dbHost: String,
    @Value("\${pocketledger.debezium.database.port:5432}") private val dbPort: Int,
    @Value("\${pocketledger.debezium.database.name:pocketledger}") private val dbName: String,
    @Value("\${pocketledger.debezium.database.user:pocketledger}") private val dbUser: String,
    @Value("\${pocketledger.debezium.database.password:pocketledger}") private val dbPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun config(): Map<String, String> =
        mapOf(
            "connector.class" to "io.debezium.connector.postgresql.PostgresConnector",
            "plugin.name" to "pgoutput",
            "database.hostname" to dbHost,
            "database.port" to dbPort.toString(),
            "database.user" to dbUser,
            "database.password" to dbPassword,
            "database.dbname" to dbName,
            "topic.prefix" to "pocketledger",
            "publication.name" to "pocketledger_outbox",
            "publication.autocreate.mode" to "disabled",
            "slot.name" to slotName,
            "table.include.list" to "public.outbox",
            "tombstones.on.delete" to "false",
            "heartbeat.interval.ms" to "10000",
            "key.converter" to "org.apache.kafka.connect.storage.StringConverter",
            "value.converter" to "org.apache.kafka.connect.storage.StringConverter",
            "transforms" to "outbox",
            "transforms.outbox.type" to "io.debezium.transforms.outbox.EventRouter",
            "transforms.outbox.table.field.event.id" to "id",
            "transforms.outbox.table.field.event.key" to "aggregate_id",
            "transforms.outbox.table.field.event.payload" to "payload",
            "transforms.outbox.table.fields.additional.placement" to "event_type:header:eventType",
            "transforms.outbox.route.by.field" to "aggregate_type",
            "transforms.outbox.route.topic.replacement" to "pocketledger.\${routedByValue}.events.v1",
        )

    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        if (connectUrl.isBlank()) {
            log.warn("pocketledger.debezium.connect-url absent : connecteur non enregistré (aucun événement ne sera publié)")
            return
        }
        Thread.ofVirtual().name("debezium-registrar").start {
            val client = RestClient.create(connectUrl.trimEnd('/'))
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    client
                        .put()
                        .uri("/connectors/{name}/config", connectorName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(config())
                        .retrieve()
                        .toBodilessEntity()
                    log.info("Connecteur Debezium {} enregistré", connectorName)
                    return@start
                } catch (e: Exception) {
                    log.info("Kafka Connect indisponible (tentative {}/{}) : {}", attempt + 1, MAX_ATTEMPTS, e.message)
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            log.error("Impossible d'enregistrer le connecteur Debezium après {} tentatives", MAX_ATTEMPTS)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 60
        const val RETRY_DELAY_MS = 2_000L
    }
}
