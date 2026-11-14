package io.pocketledger.platform.eventstore

import org.slf4j.MDC
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Métadonnées à attacher aux événements écrits : depuis la requête HTTP en cours,
 * ou depuis l'événement en cours de traitement (causation) côté consommateur.
 */
object EventContext {
    private val override = ThreadLocal<EventMetadata?>()

    fun current(): EventMetadata {
        override.get()?.let { return it }
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return EventMetadata(
            correlationId = MDC.get("correlationId"),
            causationId = null,
            idempotencyKey = request?.getHeader("Idempotency-Key"),
            deviceId = request?.getHeader("X-Device-Id"),
        )
    }

    fun <T> causedBy(cause: StoredEvent, block: () -> T): T {
        val previous = override.get()
        override.set(
            EventMetadata(
                correlationId = cause.metadata.correlationId,
                causationId = cause.eventId.toString(),
                idempotencyKey = null,
                deviceId = null,
            ),
        )
        return try {
            block()
        } finally {
            override.set(previous)
        }
    }
}
