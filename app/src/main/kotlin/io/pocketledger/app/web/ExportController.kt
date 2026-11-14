package io.pocketledger.app.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.crypto.AesGcm
import io.pocketledger.identity.SecurityAudit
import io.pocketledger.platform.web.CurrentUser
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.UserDataExporter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

data class ExportStatus(val exportId: UUID, val status: String, val downloadUrl: String?, val expiresAt: OffsetDateTime?)

/**
 * Export RGPD (FR-80) : JSON complet produit en asynchrone, stocké chiffré avec la clé de l'utilisateur,
 * téléchargeable 24 h via un lien signé (HMAC) qui ne nécessite pas de jeton d'accès.
 */
@Service
class ExportService(
    private val exporters: List<UserDataExporter>,
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    private val mapper: ObjectMapper,
    private val audit: SecurityAudit,
    private val clock: Clock,
    @Qualifier("applicationTaskExecutor") private val executor: TaskExecutor,
    @Value("\${pocketledger.exports.dir:\${java.io.tmpdir}/pocketledger-exports}") private val dir: String,
    @Value("\${pocketledger.crypto.blind-index-key}") linkKeyBase64: String,
    @Value("\${pocketledger.public-url:http://localhost:8080}") private val publicUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val linkKey = Base64.getDecoder().decode(linkKeyBase64.trim())
    private val ttl = Duration.ofHours(24)

    fun request(userId: UUID): ExportStatus {
        val exportId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO data_export (id, user_id, status, created_at) VALUES (:id, :u, 'PENDING', now())")
            .param("id", exportId)
            .param("u", userId)
            .update()
        audit.record(userId, "EXPORT_REQUESTED", mapOf("exportId" to exportId))
        // Après validation : sinon le thread asynchrone ne voit pas encore la ligne data_export.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = executor.execute { build(userId, exportId) }
                },
            )
        } else {
            executor.execute { build(userId, exportId) }
        }
        return status(userId, exportId)
    }

    private fun build(userId: UUID, exportId: UUID) {
        try {
            val document =
                mapOf(
                    "exportId" to exportId,
                    "generatedAt" to OffsetDateTime.now(clock),
                    "sections" to exporters.associate { it.section to it.export(userId) },
                )
            val path = Path.of(dir).also { Files.createDirectories(it) }.resolve("$exportId.json.enc")
            Files.write(path, cipher.encryptBytes(userId, mapper.writeValueAsBytes(document)))
            jdbc
                .sql("UPDATE data_export SET status = 'READY', file_path = :p, ready_at = now() WHERE id = :id")
                .param("p", path.toString())
                .param("id", exportId)
                .update()
        } catch (e: Exception) {
            log.error("Export {} en échec", exportId, e)
            jdbc.sql("UPDATE data_export SET status = 'FAILED' WHERE id = :id").param("id", exportId).update()
        }
    }

    fun status(userId: UUID, exportId: UUID): ExportStatus {
        val (status, readyAt) =
            jdbc
                .sql("SELECT status, ready_at FROM data_export WHERE id = :id AND user_id = :u")
                .param("id", exportId)
                .param("u", userId)
                .query { rs, _ -> rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java) }
                .optional()
                .orElseThrow { NotFoundException("Export", exportId) }
        val expires = readyAt?.plus(ttl)
        val url = if (status == "READY" && expires != null) "$publicUrl/api/v1/exports/${token(userId, exportId, expires)}" else null
        return ExportStatus(exportId, status, url, expires)
    }

    fun download(token: String): ByteArray {
        val (userId, exportId, expires) = parse(token) ?: throw ValidationException("invalid-link", "Lien invalide ou expiré")
        if (expires.isBefore(OffsetDateTime.now(clock))) throw ValidationException("invalid-link", "Lien expiré")
        val path =
            jdbc
                .sql("SELECT file_path FROM data_export WHERE id = :id AND user_id = :u AND status = 'READY'")
                .param("id", exportId)
                .param("u", userId)
                .query(String::class.java)
                .optional()
                .orElseThrow { NotFoundException("Export", exportId) }
        audit.record(userId, "EXPORT_DOWNLOADED", mapOf("exportId" to exportId))
        return cipher.decryptBytes(userId, Files.readAllBytes(Path.of(path)))
            ?: throw NotFoundException("Export", exportId)
    }

    private fun token(userId: UUID, exportId: UUID, expires: OffsetDateTime): String {
        val payload = "$userId.$exportId.${expires.toEpochSecond()}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + "." + sign(payload)
    }

    private fun parse(token: String): Triple<UUID, UUID, OffsetDateTime>? =
        runCatching {
            val (encoded, signature) = token.split(".", limit = 2)
            val payload = String(Base64.getUrlDecoder().decode(encoded))
            if (sign(payload) != signature) return null
            val (user, export, epoch) = payload.split(".")
            Triple(
                UUID.fromString(user),
                UUID.fromString(export),
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch.toLong()), java.time.ZoneOffset.UTC),
            )
        }.getOrNull()

    private fun sign(payload: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(AesGcm.hmac(linkKey, "export:$payload".toByteArray()))
}

@RestController
class ExportController(private val exports: ExportService) {
    @PostMapping("/api/v1/me/export")
    fun request(): ResponseEntity<ExportStatus> = ResponseEntity.status(HttpStatus.ACCEPTED).body(exports.request(CurrentUser.id()))

    @GetMapping("/api/v1/me/export/{id}")
    fun status(
        @PathVariable id: UUID,
    ): ExportStatus = exports.status(CurrentUser.id(), id)

    @GetMapping("/api/v1/exports/{token}")
    fun download(
        @PathVariable token: String,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pocketledger-export.json\"")
            .body(exports.download(token))
}
