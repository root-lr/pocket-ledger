package io.pocketledger.platform.monitoring

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Surveille le slot de réplication Debezium : un slot non consommé retient le WAL.
 * Expose `pocketledger.debezium.slot.lag.bytes` et `pocketledger.debezium.slot.active`.
 */
@Component
class DebeziumSlotMonitor(
    private val jdbc: JdbcClient,
    meterRegistry: MeterRegistry,
    @Value("\${pocketledger.debezium.slot-name:pocketledger_outbox}") private val slotName: String,
    @Value("\${pocketledger.debezium.lag-alert-bytes:1073741824}") private val alertBytes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lagBytes = AtomicLong(0)
    private val active = AtomicLong(0)

    init {
        Gauge.builder("pocketledger.debezium.slot.lag.bytes") { lagBytes.get().toDouble() }.register(meterRegistry)
        Gauge.builder("pocketledger.debezium.slot.active") { active.get().toDouble() }.register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${pocketledger.debezium.monitor-interval:30000}")
    fun refresh() {
        runCatching {
            jdbc
                .sql(
                    """
                    SELECT COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn), 0)::bigint AS lag,
                           CASE WHEN active THEN 1 ELSE 0 END AS active
                    FROM pg_replication_slots WHERE slot_name = :slot
                    """.trimIndent(),
                ).param("slot", slotName)
                .query { rs, _ -> rs.getLong("lag") to rs.getLong("active") }
                .optional()
        }.onSuccess { result ->
            result.ifPresentOrElse({ (lag, isActive) ->
                lagBytes.set(lag)
                active.set(isActive)
                if (lag > alertBytes) log.error("Slot Debezium {} : {} octets de WAL retenus", slotName, lag)
            }, {
                active.set(0)
            })
        }.onFailure { log.warn("Lecture du slot de réplication impossible : {}", it.message) }
    }
}
