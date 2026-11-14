package io.pocketledger.platform.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/**
 * Idempotence des écritures (§6.2 du PRD) :
 * - même clé + même corps → réponse d'origine rejouée, en-tête `Idempotent-Replayed: true` ;
 * - même clé + corps différent → 422 ;
 * - requête encore en cours avec la même clé → 409 (verrou Redis 30 s).
 * La requête s'exécute dans une transaction qui enregistre aussi la réponse : événements et
 * enregistrement d'idempotence sont validés ensemble, ce qui survit à une perte de Redis.
 */
@Component
@Order(10)
class IdempotencyFilter(
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val independentTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    private val replays = meterRegistry.counter("pocketledger.idempotency.replays")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in WRITE_METHODS ||
            !request.requestURI.startsWith("/api/v1/") ||
            request.requestURI.startsWith("/api/v1/auth/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val userId = authenticatedName()
        if (userId == null) {
            chain.doFilter(request, response)
            return
        }
        val key = request.getHeader(HEADER)
        if (key.isNullOrBlank() || key.length > 100) {
            writeProblem(response, HttpStatus.BAD_REQUEST, "idempotency-key-required", "En-tête Idempotency-Key requis (100 caractères max)")
            return
        }
        val cached = CachedBodyRequest(request)
        val hash = sha256(request.method + " " + request.requestURI + "?" + (request.queryString ?: "") + "\n", cached.body)

        findRecord(userId, key)?.let { record ->
            if (record.requestHash != hash) {
                writeProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused", "Clé déjà utilisée pour une autre requête")
            } else {
                replays.increment()
                replay(response, record)
            }
            return
        }

        val lockKey = "idem:lock:$userId:$key"
        if (!acquireLock(lockKey)) {
            writeProblem(response, HttpStatus.CONFLICT, "idempotency-request-in-progress", "Une requête avec cette clé est en cours")
            return
        }
        try {
            // Une requête concurrente a pu terminer entre la première lecture et la prise du verrou.
            findRecord(userId, key)?.let { record ->
                if (record.requestHash == hash) {
                    replay(response, record)
                } else {
                    writeProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused", "Clé déjà utilisée")
                }
                return
            }
            val wrapped = ContentCachingResponseWrapper(response)
            tx.executeWithoutResult { status ->
                chain.doFilter(cached, wrapped)
                val record = Record(hash, wrapped.status, wrapped.contentType, String(wrapped.contentAsByteArray))
                // Une erreur annule les écritures. Rollback local : pas d'UnexpectedRollbackException (donc pas de 500)
                // quand un service @Transactional a marqué la transaction après une erreur métier.
                if (wrapped.status >= 400 || status.isRollbackOnly) status.setRollbackOnly() else saveRecord(userId, key, record)
            }
            val record = Record(hash, wrapped.status, wrapped.contentType, String(wrapped.contentAsByteArray))
            if (wrapped.status in 400..499) independentTx.executeWithoutResult { saveRecord(userId, key, record) }
            if (wrapped.status < 500) cache(userId, key, record)
            wrapped.copyBodyToResponse()
        } finally {
            runCatching { redis.delete(lockKey) }
        }
    }

    private fun findRecord(userId: String, key: String): Record? {
        val fromRedis =
            runCatching { redis.opsForValue().get(cacheKey(userId, key)) }
                .onFailure { log.warn("Redis indisponible, idempotence via PostgreSQL") }
                .getOrNull()
        if (fromRedis != null) return mapper.readValue(fromRedis, Record::class.java)
        return jdbc
            .sql(
                "SELECT request_hash, status, content_type, body FROM idempotency_record " +
                    "WHERE user_id = :u AND idem_key = :k AND created_at > now() - interval '24 hours'",
            ).param("u", UUID.fromString(userId))
            .param("k", key)
            .query { rs, _ -> Record(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4) ?: "") }
            .optional()
            .orElse(null)
    }

    private fun saveRecord(userId: String, key: String, record: Record) {
        jdbc
            .sql(
                """
                INSERT INTO idempotency_record (user_id, idem_key, request_hash, status, content_type, body, created_at)
                VALUES (:u, :k, :h, :s, :ct, :b, now()) ON CONFLICT (user_id, idem_key) DO NOTHING
                """.trimIndent(),
            ).param("u", UUID.fromString(userId))
            .param("k", key)
            .param("h", record.requestHash)
            .param("s", record.status)
            .param("ct", record.contentType)
            .param("b", record.body)
            .update()
    }

    private fun cache(userId: String, key: String, record: Record) {
        runCatching { redis.opsForValue().set(cacheKey(userId, key), mapper.writeValueAsString(record), TTL) }
    }

    /** Sans Redis on continue : la contrainte unique en base reste le dernier rempart. */
    private fun acquireLock(lockKey: String): Boolean =
        runCatching { redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL) ?: false }
            .getOrElse { true }

    private fun replay(response: HttpServletResponse, record: Record) {
        response.status = record.status
        record.contentType?.let { response.contentType = it }
        response.setHeader("Idempotent-Replayed", "true")
        response.writer.write(record.body)
    }

    private fun writeProblem(response: HttpServletResponse, status: HttpStatus, code: String, detail: String) {
        response.status = status.value()
        response.contentType = "application/problem+json"
        response.writer.write(mapper.writeValueAsString(ProblemHandler.problem(status, code, detail)))
    }

    @Scheduled(cron = "0 17 * * * *")
    fun purgeExpired() {
        jdbc.sql("DELETE FROM idempotency_record WHERE created_at < now() - interval '24 hours'").update()
    }

    data class Record(val requestHash: String, val status: Int, val contentType: String?, val body: String)

    private fun cacheKey(userId: String, key: String) = "idem:resp:$userId:$key"

    private fun sha256(prefix: String, body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(prefix.toByteArray())
        digest.update(body)
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val HEADER = "Idempotency-Key"
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val TTL: Duration = Duration.ofHours(24)
        val LOCK_TTL: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Requête dont le corps est lu une fois, pour le hacher puis le relire dans le contrôleur.
 * En multipart, les parts sont lues via l'API servlet (mises en cache par le conteneur) et le flux reste intact.
 */
class CachedBodyRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private val multipart = request.contentType?.startsWith("multipart/") == true

    val body: ByteArray =
        if (multipart) {
            val out = java.io.ByteArrayOutputStream()
            request.parts.sortedBy { it.name }.forEach { part ->
                out.write(part.name.toByteArray())
                part.inputStream.use { it.copyTo(out) }
            }
            out.toByteArray()
        } else {
            request.inputStream.readAllBytes()
        }

    override fun getInputStream(): ServletInputStream {
        if (multipart) return super.getInputStream()
        val source = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = source.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)

            override fun isFinished(): Boolean = source.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
        }
    }

    override fun getReader(): BufferedReader =
        if (multipart) super.getReader() else BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
}
