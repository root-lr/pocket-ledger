package io.pocketledger.app.web

import io.pocketledger.app.config.EventProcessorContainers
import io.pocketledger.app.config.KafkaTopicsConfig
import io.pocketledger.identity.SecurityAudit
import io.pocketledger.ledger.BalanceReconciliationJob
import io.pocketledger.platform.processing.ProjectionRebuilder
import io.pocketledger.platform.web.CurrentUser
import io.pocketledger.platform.web.Cursors
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ports.SyncItem
import io.pocketledger.shared.ports.SyncSource
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.Properties

data class SyncResponse(val cursor: String, val hasMore: Boolean, val changes: Map<String, List<SyncItem>>)

/**
 * Sync delta mobile (FR-26) : tout ce qui a changé depuis un curseur, toutes sections confondues.
 * Le curseur encode la position globale du dernier événement vu.
 */
@RestController
@RequestMapping("/api/v1/sync")
class SyncController(private val sources: List<SyncSource>) {
    @GetMapping
    fun sync(
        @RequestParam(required = false) since: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): SyncResponse {
        val userId = CurrentUser.id()
        val position = Cursors.decode(since)?.firstOrNull()?.toLongOrNull() ?: 0L
        val size = limit.coerceIn(1, 500)
        val changes = sources.associate { it.section to it.changesSince(userId, position, size) }
        // Une section pleine peut avoir d'autres changements : le prochain curseur s'arrête à sa dernière position.
        val full = changes.values.filter { it.size >= size }.map { list -> list.maxOf { it.position } }
        val next = full.minOrNull() ?: changes.values.flatten().maxOfOrNull { it.position } ?: position
        val trimmed = if (full.isEmpty()) changes else changes.mapValues { (_, items) -> items.filter { it.position <= next } }
        return SyncResponse(Cursors.encode(next), full.isNotEmpty(), trimmed)
    }
}

/** Opérations (rôle ADMIN) : reconstruction de projection, rejeu de DLQ, réconciliation. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val rebuilder: ProjectionRebuilder,
    private val containers: EventProcessorContainers,
    private val reconciliation: BalanceReconciliationJob,
    private val audit: SecurityAudit,
    consumerFactory: ConsumerFactory<*, *>,
    kafkaTemplate: KafkaTemplate<*, *>,
) {
    @Suppress("UNCHECKED_CAST")
    private val consumers = consumerFactory as ConsumerFactory<String, String>

    @Suppress("UNCHECKED_CAST")
    private val template = kafkaTemplate as KafkaTemplate<String, String>

    @GetMapping("/projections")
    fun projections(): Map<String, Any> = mapOf("rebuildable" to rebuilder.names(), "processors" to containers.names())

    @PostMapping("/projections/{name}/rebuild")
    fun rebuild(
        @PathVariable name: String,
    ): Map<String, Any> {
        audit.record(CurrentUser.id(), "ADMIN_PROJECTION_REBUILD", mapOf("projection" to name))
        return mapOf("projection" to name, "replayedEvents" to rebuilder.rebuild(name))
    }

    /** Relit la DLQ d'un processeur et renvoie chaque message vers son topic d'origine. */
    @PostMapping("/dlq/{processor}/replay")
    fun replayDlq(
        @PathVariable processor: String,
    ): Map<String, Any> {
        if (processor !in containers.names()) throw NotFoundException("Processeur", processor)
        audit.record(CurrentUser.id(), "ADMIN_DLQ_REPLAY", mapOf("processor" to processor))
        val topic = KafkaTopicsConfig.dlqTopic(processor)
        val overrides =
            Properties().apply {
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            }
        var replayed = 0
        consumers.createConsumer("dlq-replay-$processor", null, null, overrides).use { consumer ->
            consumer.subscribe(listOf(topic))
            var emptyPolls = 0
            while (emptyPolls < 3) {
                val records = consumer.poll(Duration.ofSeconds(1))
                if (records.isEmpty) {
                    emptyPolls++
                    continue
                }
                records.forEach { record ->
                    val original =
                        record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)?.value()?.let { String(it) }
                            ?: return@forEach
                    template.send(original, record.key(), record.value()).get()
                    replayed++
                }
                consumer.commitSync()
            }
        }
        return mapOf("processor" to processor, "replayed" to replayed, "partition" to TopicPartition(topic, 0).toString())
    }

    @PostMapping("/reconciliation/run")
    fun reconcile(): Map<String, Int> = mapOf("mismatches" to reconciliation.reconcile())
}
