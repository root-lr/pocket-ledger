package io.pocketledger.platform.eventstore

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.pocketledger.events.DomainEvent
import io.pocketledger.events.EventTypes
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Transforme un payload d'une version de schéma à la suivante (v1 → v2), appliqué à la lecture. */
interface Upcaster {
    val eventType: String
    val fromVersion: Int

    fun upcast(payload: ObjectNode): ObjectNode
}

@Component
class EventSerializer(private val mapper: ObjectMapper, upcasters: List<Upcaster>) {
    private val upcastersByKey = upcasters.associateBy { it.eventType to it.fromVersion }

    fun payloadJson(event: DomainEvent): String = mapper.writeValueAsString(event)

    fun metadataJson(metadata: EventMetadata): String = mapper.writeValueAsString(metadata)

    fun readPayload(eventType: String, schemaVersion: Int, json: String): DomainEvent {
        var node = mapper.readTree(json) as ObjectNode
        var version = schemaVersion
        val current = EventTypes.schemaVersions[eventType] ?: 1
        while (version < current) {
            val upcaster =
                upcastersByKey[eventType to version]
                    ?: error("Aucun upcaster pour $eventType v$version")
            node = upcaster.upcast(node)
            version++
        }
        return mapper.treeToValue(node, EventTypes.classOf(eventType).java)
    }

    fun readMetadata(json: String?): EventMetadata = json?.let { mapper.readValue(it, EventMetadata::class.java) } ?: EventMetadata()

    /** Enveloppe complète écrite dans l'outbox et lue par les consommateurs Kafka. */
    fun envelope(e: StoredEvent): String {
        val root = mapper.createObjectNode()
        root.put("globalPosition", e.globalPosition)
        root.put("eventId", e.eventId.toString())
        root.put("eventType", e.eventType)
        root.put("schemaVersion", e.schemaVersion)
        root.put("aggregateType", e.aggregateType)
        root.put("aggregateId", e.aggregateId.toString())
        root.put("version", e.version)
        root.put("userId", e.userId.toString())
        root.put("occurredAt", e.occurredAt.toString())
        root.set<ObjectNode>("metadata", mapper.valueToTree(e.metadata))
        root.set<ObjectNode>("payload", mapper.valueToTree(e.event))
        return mapper.writeValueAsString(root)
    }

    fun parseEnvelope(json: String): StoredEvent {
        val root = mapper.readTree(json)
        val eventType = root.path("eventType").asText()
        val schemaVersion = root.path("schemaVersion").asInt(1)
        return StoredEvent(
            globalPosition = root.path("globalPosition").asLong(),
            eventId = UUID.fromString(root.path("eventId").asText()),
            eventType = eventType,
            schemaVersion = schemaVersion,
            aggregateType = root.path("aggregateType").asText(),
            aggregateId = UUID.fromString(root.path("aggregateId").asText()),
            version = root.path("version").asInt(),
            userId = UUID.fromString(root.path("userId").asText()),
            occurredAt = Instant.parse(root.path("occurredAt").asText()),
            metadata = mapper.treeToValue(root.path("metadata"), EventMetadata::class.java) ?: EventMetadata(),
            event = readPayload(eventType, schemaVersion, mapper.writeValueAsString(root.path("payload"))),
        )
    }
}
