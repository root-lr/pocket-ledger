package io.pocketledger.platform.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Nom de l'utilisateur authentifié, en ignorant le jeton anonyme posé par Spring Security. */
fun authenticatedName(): String? =
    SecurityContextHolder
        .getContext()
        .authentication
        ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        ?.name

/** Identifiant de corrélation sur chaque requête, chaque ligne de log et chaque événement. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val correlationId = request.getHeader(HEADER)?.take(64) ?: UUID.randomUUID().toString()
        MDC.put("correlationId", correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("correlationId")
        }
    }

    private companion object {
        const val HEADER = "X-Correlation-Id"
    }
}

/**
 * Limite de débit par utilisateur (ou par IP sans authentification), fenêtre fixe d'une minute dans Redis.
 * Redis indisponible : la requête passe (dégradation contrôlée).
 */
@Component
@Order(5)
class RateLimitFilter(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${pocketledger.rate-limit.per-minute:100}") private val perMinute: Long,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val subject = authenticatedName() ?: ("ip:" + request.remoteAddr)
        val minute = Instant.now().epochSecond / 60
        val key = "rl:$subject:$minute"
        val count =
            runCatching {
                val value = redis.opsForValue().increment(key) ?: 0
                if (value == 1L) redis.expire(key, Duration.ofSeconds(60))
                value
            }.getOrDefault(0)
        if (count > perMinute) {
            val retryAfter = 60 - (Instant.now().epochSecond % 60)
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", retryAfter.toString())
            response.contentType = "application/problem+json"
            response.writer.write(
                mapper.writeValueAsString(ProblemHandler.problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Trop de requêtes")),
            )
            return
        }
        chain.doFilter(request, response)
    }
}
