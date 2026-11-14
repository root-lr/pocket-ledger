package io.pocketledger.budgeting

import io.pocketledger.platform.web.CurrentUser
import io.pocketledger.shared.ports.UserDataExporter
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/budgets")
class BudgetController(private val service: BudgetService, private val queries: BudgetQueries) {
    data class CreateRequest(
        @field:NotBlank val categoryId: String,
        @field:NotBlank val limit: String,
        val rollover: Boolean = false,
        val startMonth: YearMonth? = null,
    )

    data class ChangeLimitRequest(@field:NotBlank val limit: String, val effectiveMonth: YearMonth? = null)

    data class CloseRequest(val effectiveMonth: YearMonth? = null)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody r: CreateRequest,
    ): BudgetRef = service.create(CurrentUser.id(), CreateBudgetCommand(r.categoryId, r.limit, r.rollover, r.startMonth))

    @GetMapping
    fun list(): List<BudgetDto> = queries.list(CurrentUser.id())

    @GetMapping("/status")
    fun status(
        @RequestParam(required = false) month: YearMonth?,
    ): List<BudgetStatusDto> = queries.status(CurrentUser.id(), month)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): BudgetDto = queries.get(CurrentUser.id(), id)

    @PatchMapping("/{id}")
    fun changeLimit(
        @PathVariable id: UUID,
        @Valid @RequestBody r: ChangeLimitRequest,
    ): BudgetRef = service.changeLimit(CurrentUser.id(), id, r.limit, r.effectiveMonth)

    @PostMapping("/{id}/close")
    fun close(
        @PathVariable id: UUID,
        @RequestBody(required = false) r: CloseRequest?,
    ): BudgetRef = service.close(CurrentUser.id(), id, r?.effectiveMonth)
}

@Component
class BudgetExporter(private val queries: BudgetQueries) : UserDataExporter {
    override val section = "budgets"

    override fun export(userId: UUID): Any = queries.list(userId)
}
