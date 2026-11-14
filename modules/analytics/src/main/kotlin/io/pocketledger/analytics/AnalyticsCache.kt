package io.pocketledger.analytics

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Cache Redis des réponses analytics (TTL 5 min), invalidé par événement pour l'utilisateur concerné.
 * Redis indisponible : on calcule directement (dégradation, pas d'erreur).
 */
@Component
class AnalyticsCache(private val redis: StringRedisTemplate, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofMinutes(5)

    fun <T> getOrCompute(userId: UUID, key: String, type: TypeReference<T>, compute: () -> T): T {
        val redisKey = "analytics:$userId:$key"
        runCatching { redis.opsForValue().get(redisKey) }
            .getOrNull()
            ?.let { cached -> return mapper.readValue(cached, type) }
        val value = compute()
        runCatching {
            redis.opsForValue().set(redisKey, mapper.writeValueAsString(value), ttl)
            redis.opsForSet().add(indexKey(userId), redisKey)
            redis.expire(indexKey(userId), ttl)
        }.onFailure { log.debug("Cache analytics indisponible : {}", it.message) }
        return value
    }

    fun invalidateUser(userId: UUID) {
        runCatching {
            val keys = redis.opsForSet().members(indexKey(userId)).orEmpty()
            if (keys.isNotEmpty()) redis.delete(keys)
            redis.delete(indexKey(userId))
        }
    }

    fun invalidateAll() {
        runCatching {
            val keys = redis.keys("analytics:*").orEmpty()
            if (keys.isNotEmpty()) redis.delete(keys)
        }
    }

    private fun indexKey(userId: UUID) = "analytics-index:$userId"
}
