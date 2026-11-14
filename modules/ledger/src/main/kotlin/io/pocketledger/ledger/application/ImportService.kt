package io.pocketledger.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.ledger.domain.ImportJob
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.DomainException
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.Text
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.FieldCipher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID

data class ImportStatus(
    val importId: UUID,
    val accountId: UUID,
    val format: String,
    val status: String,
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val errors: List<RowError>,
    val createdAt: OffsetDateTime,
)

data class PendingDuplicate(val rowNumber: Int, val date: String, val amount: String, val label: String?)

/**
 * Import CSV / OFX asynchrone (FR-30 à FR-32) :
 * - un fichier déjà importé sur ce compte (même hash) ne crée rien ;
 * - chaque ligne a une empreinte (compte, date, montant, libellé normalisé) ; un doublon probable
 *   est mis de côté pour confirmation au lieu d'être enregistré.
 */
@Service
class ImportService(
    private val jdbc: JdbcClient,
    private val imports: ImportRepository,
    private val index: LedgerWriteIndex,
    private val transactions: TransactionService,
    private val cipher: FieldCipher,
    private val mapper: ObjectMapper,
    private val commands: CommandExecutor,
    @Qualifier("applicationTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun start(userId: UUID, accountId: UUID, fileName: String, content: ByteArray, format: String?, mapping: CsvMapping?): ImportStatus {
        index.requireOwner(accountId, userId)
        if (content.size > MAX_BYTES) throw ValidationException("file-too-large", "Fichier limité à 5 Mo")
        val resolvedFormat = (format ?: if (fileName.lowercase().endsWith(".ofx")) "OFX" else "CSV").uppercase()
        if (resolvedFormat !in setOf("CSV", "OFX")) throw ValidationException("invalid-format", "Format accepté : CSV ou OFX")
        val fileHash = sha256(content)

        existing(accountId, fileHash)?.let { return status(userId, it) } // FR-32 : même fichier → rien de nouveau

        val effectiveMapping = mapping ?: savedMapping(userId, accountId) ?: CsvMapping()
        if (mapping != null) saveMapping(userId, accountId, mapping)
        val importId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO import_job (id, user_id, account_id, file_hash, format, mapping, content_enc, status, created_at)
                VALUES (:id, :u, :a, :h, :f, CAST(:m AS jsonb), :c, 'PENDING', now())
                """.trimIndent(),
            ).param("id", importId)
            .param("u", userId)
            .param("a", accountId)
            .param("h", fileHash)
            .param("f", resolvedFormat)
            .param("m", mapper.writeValueAsString(effectiveMapping))
            .param("c", cipher.encryptBytes(userId, content))
            .update()
        commands.execute {
            val job = ImportJob(importId)
            job.start(accountId, cipher.encrypt(userId, fileName), fileHash, resolvedFormat)
            imports.save(job, userId)
        }
        // Traitement après validation de la transaction, sinon le thread asynchrone ne voit pas encore le job.
        afterCommit { taskExecutor.execute { process(userId, importId) } }
        return status(userId, importId)
    }

    fun process(userId: UUID, importId: UUID) {
        try {
            jdbc.sql("UPDATE import_job SET status = 'RUNNING' WHERE id = :id").param("id", importId).update()
            val job = loadJob(importId)
            val content = cipher.decryptBytes(userId, job.contentEnc) ?: throw ConflictException("user-key-missing", "Clé absente")
            val text = String(content, Charsets.UTF_8)
            val parsed =
                if (job.format == "OFX") {
                    OfxStatementParser.parse(text)
                } else {
                    CsvStatementParser.parse(text, mapper.readValue(job.mappingJson, CsvMapping::class.java))
                }
            var imported = 0
            var duplicates = 0
            val errors = parsed.errors.toMutableList()
            parsed.rows.forEach { row ->
                val fingerprint = fingerprint(job.accountId, row)
                when {
                    fingerprintExists(job.accountId, fingerprint) -> {
                        duplicates++
                        holdDuplicate(importId, row, fingerprint, userId)
                    }
                    else ->
                        try {
                            recordRow(userId, job.accountId, importId, row, fingerprint)
                            imported++
                        } catch (e: DomainException) {
                            errors += RowError(row.rowNumber, e.message ?: e.code)
                        }
                }
            }
            commands.execute {
                val aggregate = imports.get(importId)
                aggregate.complete(imported, duplicates, errors.size)
                imports.save(aggregate, userId)
                jdbc
                    .sql(
                        """
                        UPDATE import_job SET status = 'COMPLETED', imported = :i, duplicates = :d, failed = :f,
                               errors = CAST(:e AS jsonb), content_enc = NULL, completed_at = now() WHERE id = :id
                        """.trimIndent(),
                    ).param("i", imported)
                    .param("d", duplicates)
                    .param("f", errors.size)
                    .param("e", mapper.writeValueAsString(errors))
                    .param("id", importId)
                    .update()
            }
        } catch (e: Exception) {
            log.error("Import {} en échec", importId, e)
            jdbc
                .sql("UPDATE import_job SET status = 'FAILED', errors = CAST(:e AS jsonb) WHERE id = :id")
                .param("e", mapper.writeValueAsString(listOf(RowError(0, e.message ?: "erreur"))))
                .param("id", importId)
                .update()
        }
    }

    /** Confirme un doublon probable : la ligne est enregistrée malgré tout. */
    @Transactional
    fun confirmDuplicate(userId: UUID, importId: UUID, rowNumber: Int): TransactionRef {
        val job = loadJob(importId)
        if (job.userId != userId) throw NotFoundException("Import", importId)
        val held =
            jdbc
                .sql("SELECT row_data FROM import_duplicate WHERE import_id = :i AND row_number = :r AND resolved_at IS NULL")
                .param("i", importId)
                .param("r", rowNumber)
                .query(ByteArray::class.java)
                .optional()
                .orElseThrow { NotFoundException("Doublon", rowNumber) }
        val row =
            mapper.readValue(
                cipher.decryptBytes(userId, held) ?: throw ConflictException("user-key-missing", "Clé absente"),
                StatementRow::class.java,
            )
        val ref = recordRow(userId, job.accountId, importId, row, null, confirmed = true)
        jdbc
            .sql("UPDATE import_duplicate SET resolved_at = now() WHERE import_id = :i AND row_number = :r")
            .param("i", importId)
            .param("r", rowNumber)
            .update()
        return ref
    }

    fun status(userId: UUID, importId: UUID): ImportStatus =
        jdbc
            .sql(
                "SELECT id, account_id, format, status, imported, duplicates, failed, errors, created_at FROM import_job " +
                    "WHERE id = :id AND user_id = :u",
            ).param("id", importId)
            .param("u", userId)
            .query { rs, _ ->
                ImportStatus(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("account_id", UUID::class.java),
                    rs.getString("format"),
                    rs.getString("status"),
                    rs.getInt("imported"),
                    rs.getInt("duplicates"),
                    rs.getInt("failed"),
                    rs.getString("errors")?.let { mapper.readValue(it, Array<RowError>::class.java).toList() } ?: emptyList(),
                    rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }.optional()
            .orElseThrow { NotFoundException("Import", importId) }

    fun duplicates(userId: UUID, importId: UUID): List<PendingDuplicate> {
        status(userId, importId)
        return jdbc
            .sql("SELECT row_data FROM import_duplicate WHERE import_id = :i AND resolved_at IS NULL ORDER BY row_number")
            .param("i", importId)
            .query(ByteArray::class.java)
            .list()
            .mapNotNull { bytes -> cipher.decryptBytes(userId, bytes)?.let { mapper.readValue(it, StatementRow::class.java) } }
            .map { PendingDuplicate(it.rowNumber, it.date.toString(), it.amount, it.label) }
    }

    private fun recordRow(
        userId: UUID,
        accountId: UUID,
        importId: UUID,
        row: StatementRow,
        fingerprint: String?,
        confirmed: Boolean = false,
    ): TransactionRef {
        val transactionId = UUID.nameUUIDFromBytes("import:$importId:${row.rowNumber}".toByteArray())
        val ref =
            transactions.record(
                userId,
                RecordTransactionCommand(
                    accountId = accountId, amount = row.amount, currency = null, valueDate = row.date, label = row.label,
                    merchant = row.merchant, tags = if (confirmed) listOf("import", "doublon-confirmé") else listOf("import"),
                    transactionId = transactionId, importId = importId,
                ),
            )
        fingerprint?.let {
            jdbc
                .sql("INSERT INTO import_fingerprint (account_id, fingerprint, transaction_id) VALUES (:a, :f, :t) ON CONFLICT DO NOTHING")
                .param("a", accountId)
                .param("f", it)
                .param("t", transactionId)
                .update()
        }
        return ref
    }

    private fun holdDuplicate(importId: UUID, row: StatementRow, fingerprint: String, userId: UUID) {
        jdbc
            .sql(
                "INSERT INTO import_duplicate (import_id, row_number, fingerprint, row_data) VALUES (:i, :r, :f, :d) " +
                    "ON CONFLICT DO NOTHING",
            ).param("i", importId)
            .param("r", row.rowNumber)
            .param("f", fingerprint)
            .param("d", cipher.encryptBytes(userId, mapper.writeValueAsBytes(row)))
            .update()
        commands.execute {
            val aggregate = imports.get(importId)
            aggregate.skipDuplicate(row.rowNumber, fingerprint)
            imports.save(aggregate, userId)
        }
    }

    private fun fingerprintExists(accountId: UUID, fingerprint: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM import_fingerprint WHERE account_id = :a AND fingerprint = :f)")
            .param("a", accountId)
            .param("f", fingerprint)
            .query(Boolean::class.javaObjectType)
            .single()

    private fun existing(accountId: UUID, fileHash: String): UUID? =
        jdbc
            .sql("SELECT id FROM import_job WHERE account_id = :a AND file_hash = :h AND status <> 'FAILED' LIMIT 1")
            .param("a", accountId)
            .param("h", fileHash)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun savedMapping(userId: UUID, accountId: UUID): CsvMapping? =
        jdbc
            .sql("SELECT mapping FROM import_mapping WHERE user_id = :u AND account_id = :a")
            .param("u", userId)
            .param("a", accountId)
            .query(String::class.java)
            .optional()
            .map { mapper.readValue(it, CsvMapping::class.java) }
            .orElse(null)

    private fun saveMapping(userId: UUID, accountId: UUID, mapping: CsvMapping) {
        jdbc
            .sql(
                "INSERT INTO import_mapping (user_id, account_id, mapping) VALUES (:u, :a, CAST(:m AS jsonb)) " +
                    "ON CONFLICT (user_id, account_id) DO UPDATE SET mapping = EXCLUDED.mapping",
            ).param("u", userId)
            .param("a", accountId)
            .param("m", mapper.writeValueAsString(mapping))
            .update()
    }

    private fun loadJob(importId: UUID): JobRow =
        jdbc
            .sql("SELECT user_id, account_id, format, mapping, content_enc FROM import_job WHERE id = :id")
            .param("id", importId)
            .query { rs, _ ->
                JobRow(
                    rs.getObject("user_id", UUID::class.java),
                    rs.getObject("account_id", UUID::class.java),
                    rs.getString("format"),
                    rs.getString("mapping"),
                    rs.getBytes("content_enc") ?: ByteArray(0),
                )
            }.optional()
            .orElseThrow { NotFoundException("Import", importId) }

    private class JobRow(val userId: UUID, val accountId: UUID, val format: String, val mappingJson: String, val contentEnc: ByteArray)

    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                },
            )
        } else {
            action()
        }
    }

    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024

        fun fingerprint(accountId: UUID, row: StatementRow): String =
            sha256("$accountId|${row.date}|${row.amount.toBigDecimal().stripTrailingZeros().toPlainString()}|${Text.normalize(row.label)}".toByteArray())

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
