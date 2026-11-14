package io.pocketledger.ledger.application

import io.pocketledger.shared.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Ligne de relevé normalisée, quel que soit le format d'origine. */
data class StatementRow(val rowNumber: Int, val date: LocalDate, val amount: String, val label: String, val merchant: String?)

data class RowError(val rowNumber: Int, val message: String)

data class ParseResult(val rows: List<StatementRow>, val errors: List<RowError>)

/**
 * Correspondance des colonnes CSV, sauvegardable par compte (FR-30).
 * Les colonnes se désignent par nom d'en-tête ou par index (0, 1, …).
 * Montant : une colonne signée, ou deux colonnes débit / crédit.
 */
data class CsvMapping(
    val date: String = "date",
    val amount: String? = "amount",
    val debit: String? = null,
    val credit: String? = null,
    val label: String = "label",
    val merchant: String? = null,
    val dateFormat: String = "yyyy-MM-dd",
    val delimiter: Char = ',',
    val decimalSeparator: Char = '.',
    val hasHeader: Boolean = true,
)

object CsvStatementParser {
    fun parse(content: String, mapping: CsvMapping): ParseResult {
        val lines = splitRecords(content.removePrefix("﻿"), mapping.delimiter).filter { r -> r.any { it.isNotBlank() } }
        if (lines.isEmpty()) return ParseResult(emptyList(), emptyList())
        val header = if (mapping.hasHeader) lines.first().map { it.trim().lowercase() } else emptyList()
        fun column(name: String?): Int? =
            name?.let { n -> n.toIntOrNull() ?: header.indexOf(n.lowercase()).takeIf { it >= 0 } }
        val dateCol = column(mapping.date) ?: throw ValidationException("csv-mapping", "Colonne date introuvable : ${mapping.date}")
        val labelCol = column(mapping.label) ?: throw ValidationException("csv-mapping", "Colonne libellé introuvable : ${mapping.label}")
        val amountCol = column(mapping.amount)
        val debitCol = column(mapping.debit)
        val creditCol = column(mapping.credit)
        if (amountCol == null && (debitCol == null || creditCol == null)) {
            throw ValidationException("csv-mapping", "Indiquer une colonne montant, ou les colonnes débit et crédit")
        }
        val merchantCol = column(mapping.merchant)
        val formatter = DateTimeFormatter.ofPattern(mapping.dateFormat)

        val rows = mutableListOf<StatementRow>()
        val errors = mutableListOf<RowError>()
        val body = if (mapping.hasHeader) lines.drop(1) else lines
        body.forEachIndexed { i, cells ->
            val rowNumber = i + 1
            try {
                val amount =
                    if (amountCol != null) {
                        normalizeAmount(cells[amountCol], mapping.decimalSeparator)
                    } else {
                        val debit = cells.getOrNull(debitCol!!)?.takeIf { it.isNotBlank() }
                        val credit = cells.getOrNull(creditCol!!)?.takeIf { it.isNotBlank() }
                        when {
                            debit != null -> "-" + normalizeAmount(debit, mapping.decimalSeparator).removePrefix("-")
                            credit != null -> normalizeAmount(credit, mapping.decimalSeparator)
                            else -> throw IllegalArgumentException("ni débit ni crédit")
                        }
                    }
                rows +=
                    StatementRow(
                        rowNumber,
                        LocalDate.parse(cells[dateCol].trim(), formatter),
                        amount,
                        cells[labelCol].trim(),
                        merchantCol?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) },
                    )
            } catch (e: Exception) {
                errors += RowError(rowNumber, e.message ?: "ligne illisible")
            }
        }
        return ParseResult(rows, errors)
    }

    private fun normalizeAmount(raw: String, decimalSeparator: Char): String {
        val cleaned = raw.trim().replace(" ", "").replace(" ", "")
        return if (decimalSeparator == ',') cleaned.replace(".", "").replace(',', '.') else cleaned.replace(",", "")
    }

    /** RFC 4180 : guillemets, guillemets doublés, retours à la ligne dans un champ. */
    fun splitRecords(content: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                inQuotes && c == '"' && content.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> {
                    record.add(field.toString())
                    field.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && content.getOrNull(i + 1) == '\n') i++
                    record.add(field.toString())
                    field.clear()
                    records.add(record)
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record.add(field.toString())
            records.add(record)
        }
        return records
    }
}

/** OFX 1.x (SGML, balises fermantes optionnelles) et 2.x (XML) : blocs <STMTTRN>. */
object OfxStatementParser {
    private val transaction = Regex("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String): ParseResult {
        val rows = mutableListOf<StatementRow>()
        val errors = mutableListOf<RowError>()
        transaction.findAll(content).forEachIndexed { i, match ->
            val block = match.groupValues[1]
            val rowNumber = i + 1
            try {
                val posted = tag(block, "DTPOSTED") ?: throw IllegalArgumentException("DTPOSTED manquant")
                val amount = tag(block, "TRNAMT") ?: throw IllegalArgumentException("TRNAMT manquant")
                val name = tag(block, "NAME")
                val memo = tag(block, "MEMO")
                rows +=
                    StatementRow(
                        rowNumber,
                        LocalDate.parse(posted.take(8), DateTimeFormatter.BASIC_ISO_DATE),
                        amount.replace(',', '.'),
                        listOfNotNull(name, memo).joinToString(" — ").ifBlank { "Opération" },
                        name,
                    )
            } catch (e: Exception) {
                errors += RowError(rowNumber, e.message ?: "transaction OFX illisible")
            }
        }
        return ParseResult(rows, errors)
    }

    private fun tag(block: String, name: String): String? =
        Regex("<$name>([^<\\r\\n]*)").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
