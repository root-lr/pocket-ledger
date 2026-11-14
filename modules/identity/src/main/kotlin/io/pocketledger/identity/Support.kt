package io.pocketledger.identity

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/** Journal d'audit sécurité append-only (login, révocation, export, suppression, admin). */
@Component
class SecurityAudit(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun record(userId: UUID?, action: String, details: Map<String, Any?> = emptyMap()) {
        jdbc
            .sql(
                "INSERT INTO security_audit (id, user_id, action, details, created_at) " +
                    "VALUES (:id, :u, :a, CAST(:d AS jsonb), now())",
            ).param("id", UUID.randomUUID())
            .param("u", userId)
            .param("a", action)
            .param("d", mapper.writeValueAsString(details))
            .update()
    }
}

interface EmailSender {
    fun send(to: String, subject: String, body: String)
}

/** Envoi d'email simulé : le lien apparaît dans les logs (pas de fournisseur SMTP en V1). */
@Component
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, body: String) {
        log.info("[email simulé] sujet={} corps={}", subject, body)
    }
}
