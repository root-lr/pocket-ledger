package io.pocketledger.ledger.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.pocketledger.ledger.application.AccountService
import io.pocketledger.ledger.application.AggregateRef
import io.pocketledger.ledger.application.AmendTransactionCommand
import io.pocketledger.ledger.application.CsvMapping
import io.pocketledger.ledger.application.ImportService
import io.pocketledger.ledger.application.ImportStatus
import io.pocketledger.ledger.application.LedgerWriteIndex
import io.pocketledger.ledger.application.OpenAccountCommand
import io.pocketledger.ledger.application.PartInput
import io.pocketledger.ledger.application.PendingDuplicate
import io.pocketledger.ledger.application.RecordTransactionCommand
import io.pocketledger.ledger.application.TransactionRef
import io.pocketledger.ledger.application.TransactionService
import io.pocketledger.ledger.application.TransferCommand
import io.pocketledger.ledger.application.TransferResult
import io.pocketledger.ledger.application.TransferSaga
import io.pocketledger.ledger.application.UpdateAccountCommand
import io.pocketledger.ledger.query.AccountDto
import io.pocketledger.ledger.query.LedgerQueries
import io.pocketledger.ledger.query.TransactionDto
import io.pocketledger.ledger.query.TransactionFilter
import io.pocketledger.ledger.query.TransactionVersionDto
import io.pocketledger.ledger.query.TransferDto
import io.pocketledger.platform.processing.ProcessorRunner
import io.pocketledger.platform.web.CurrentUser
import io.pocketledger.platform.web.Page
import io.pocketledger.platform.web.awaitVersion
import io.pocketledger.platform.web.clampLimit
import io.pocketledger.shared.ValidationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/** ETag = version (de l'agrégat ou révision de la transaction). If-Match obligatoire sur PATCH. */
internal fun parseIfMatch(header: String?, required: Boolean): Int? {
    if (header == null) {
        if (required) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "En-tête If-Match requis")
        return null
    }
    return header.removePrefix("W/").trim('"').toIntOrNull()
        ?: throw ValidationException("invalid-if-match", "If-Match invalide : $header")
}

internal fun <T> created(body: T, version: Int): ResponseEntity<T> =
    ResponseEntity
        .status(HttpStatus.CREATED)
        .header("X-Aggregate-Version", version.toString())
        .body(body)

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val service: AccountService, private val queries: LedgerQueries) {
    data class OpenAccountRequest(
        @field:NotBlank val name: String,
        @field:NotBlank val type: String,
        @field:NotBlank val currency: String,
        val openingBalance: String = "0",
        val openedOn: LocalDate? = null,
    )

    data class UpdateAccountRequest(val name: String? = null, val lowBalanceThreshold: String? = null, val clearLowBalanceThreshold: Boolean = false)

    @PostMapping
    fun open(
        @Valid @RequestBody request: OpenAccountRequest,
    ): ResponseEntity<AggregateRef> {
        val ref =
            service.open(
                CurrentUser.id(),
                OpenAccountCommand(request.name, request.type, request.currency, request.openingBalance, request.openedOn),
            )
        return created(ref, ref.version)
    }

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "true") includeArchived: Boolean,
    ): List<AccountDto> = queries.accounts(CurrentUser.id(), includeArchived)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        @RequestParam(required = false) minVersion: Int?,
    ): ResponseEntity<AccountDto> {
        awaitVersion(minVersion) { queries.accountViewVersion(id) }
        val account = queries.account(CurrentUser.id(), id)
        return ResponseEntity.ok().eTag("\"${account.version}\"").body(account)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody request: UpdateAccountRequest,
    ): AggregateRef =
        service.update(
            CurrentUser.id(), id, parseIfMatch(ifMatch, required = true)!!,
            UpdateAccountCommand(request.name, request.lowBalanceThreshold, request.clearLowBalanceThreshold),
        )

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: UUID,
    ): AggregateRef = service.archive(CurrentUser.id(), id)

    @PostMapping("/{id}/reactivate")
    fun reactivate(
        @PathVariable id: UUID,
    ): AggregateRef = service.reactivate(CurrentUser.id(), id)

    @GetMapping("/{id}/balance")
    fun balance(
        @PathVariable id: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) at: LocalDate?,
    ): Map<String, Any?> = mapOf("accountId" to id, "at" to at, "balance" to queries.balanceAt(CurrentUser.id(), id, at))
}

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val service: TransactionService,
    private val queries: LedgerQueries,
    private val index: LedgerWriteIndex,
    private val runner: ProcessorRunner,
) {
    data class RecordRequest(
        @field:NotNull val accountId: UUID,
        @field:NotBlank val amount: String,
        val currency: String? = null,
        @field:NotNull val valueDate: LocalDate,
        @field:NotBlank val label: String,
        val merchant: String? = null,
        val categoryId: String? = null,
        val tags: List<String> = emptyList(),
        val note: String? = null,
        val parts: List<PartInput>? = null,
    )

    data class VoidRequest(val reason: String? = null)

    data class SplitRequest(val parts: List<PartInput>)

    @PostMapping
    fun record(
        @Valid @RequestBody r: RecordRequest,
    ): ResponseEntity<TransactionRef> {
        val ref =
            service.record(
                CurrentUser.id(),
                RecordTransactionCommand(r.accountId, r.amount, r.currency, r.valueDate, r.label, r.merchant, r.categoryId, r.tags, r.note, r.parts),
            )
        return created(ref, ref.aggregateVersion)
    }

    @GetMapping
    @Suppress("LongParameterList") // un paramètre par filtre de recherche
    fun search(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) categoryId: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) minAmount: String?,
        @RequestParam(required = false) maxAmount: String?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "false") includeVoided: Boolean,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): Page<TransactionDto> =
        queries.transactions(
            CurrentUser.id(),
            TransactionFilter(accountId, from, to, categoryId, tag, minAmount, maxAmount, currency, q, includeVoided),
            cursor,
            clampLimit(limit),
        )

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        @RequestParam(required = false) minVersion: Int?,
    ): ResponseEntity<TransactionDto> {
        val userId = CurrentUser.id()
        if (minVersion != null) {
            val accountId = index.accountOf(id, userId)
            awaitVersion(minVersion) { runner.lastVersion("ledger-transaction-view", accountId) }
        }
        val tx = queries.transaction(userId, id)
        return ResponseEntity.ok().eTag("\"${tx.revision}\"").body(tx)
    }

    @PatchMapping("/{id}")
    fun amend(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody request: AmendTransactionCommand,
    ): TransactionRef = service.amend(CurrentUser.id(), id, parseIfMatch(ifMatch, required = true), request)

    @GetMapping("/{id}/history")
    fun history(
        @PathVariable id: UUID,
    ): List<TransactionVersionDto> = queries.history(CurrentUser.id(), id)

    @PostMapping("/{id}/void")
    fun void(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody(required = false) request: VoidRequest?,
    ): TransactionRef = service.void(CurrentUser.id(), id, parseIfMatch(ifMatch, required = false), request?.reason)

    @PostMapping("/{id}/split")
    fun split(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody request: SplitRequest,
    ): TransactionRef = service.split(CurrentUser.id(), id, parseIfMatch(ifMatch, required = false), request.parts)
}

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(private val saga: TransferSaga, private val queries: LedgerQueries, private val index: LedgerWriteIndex) {
    data class TransferRequest(
        @field:NotNull val fromAccountId: UUID,
        @field:NotNull val toAccountId: UUID,
        @field:NotBlank val amount: String,
        val creditAmount: String? = null,
        val fee: String? = null,
        @field:NotNull val valueDate: LocalDate,
        val label: String = "Virement interne",
    )

    @PostMapping
    fun transfer(
        @Valid @RequestBody r: TransferRequest,
    ): ResponseEntity<TransferResult> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                saga.transfer(
                    CurrentUser.id(),
                    TransferCommand(r.fromAccountId, r.toAccountId, r.amount, r.creditAmount, r.fee, r.valueDate, r.label),
                ),
            )

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): TransferDto {
        index.requireTransferOwner(id, CurrentUser.id())
        return queries.transfer(CurrentUser.id(), id)
    }
}

@RestController
@RequestMapping("/api/v1/imports")
class ImportController(private val service: ImportService, private val mapper: ObjectMapper) {
    @PostMapping(consumes = ["multipart/form-data"])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestParam accountId: UUID,
        @RequestParam(required = false) format: String?,
        @RequestParam(required = false) mapping: String?,
    ): ResponseEntity<ImportStatus> {
        val csvMapping = mapping?.let { mapper.readValue(it, CsvMapping::class.java) }
        val status = service.start(CurrentUser.id(), accountId, file.originalFilename ?: "import", file.bytes, format, csvMapping)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status)
    }

    @GetMapping("/{id}")
    fun status(
        @PathVariable id: UUID,
    ): ImportStatus = service.status(CurrentUser.id(), id)

    @GetMapping("/{id}/duplicates")
    fun duplicates(
        @PathVariable id: UUID,
    ): List<PendingDuplicate> = service.duplicates(CurrentUser.id(), id)

    @PostMapping("/{id}/duplicates/{row}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @PathVariable row: Int,
    ): TransactionRef = service.confirmDuplicate(CurrentUser.id(), id, row)
}
