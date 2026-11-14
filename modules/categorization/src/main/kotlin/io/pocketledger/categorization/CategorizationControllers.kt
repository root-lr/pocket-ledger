package io.pocketledger.categorization

import io.pocketledger.platform.web.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(private val categories: CategoryService) {
    data class CreateRequest(@field:NotBlank val name: String, val parentId: String? = null)

    data class RenameRequest(@field:NotBlank val name: String)

    @GetMapping
    fun tree(): List<CategoryDto> = categories.tree(CurrentUser.id())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateRequest,
    ): CategoryDto = categories.create(CurrentUser.id(), request.name, request.parentId)

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun rename(
        @PathVariable id: String,
        @Valid @RequestBody request: RenameRequest,
    ) = categories.rename(CurrentUser.id(), id, request.name)
}

@RestController
@RequestMapping("/api/v1/rules")
class RuleController(private val rules: RuleService) {
    data class CreateRequest(@field:NotBlank val keyword: String, @field:NotBlank val categoryId: String)

    @GetMapping
    fun list(): List<RuleDto> = rules.list(CurrentUser.id())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateRequest,
    ): RuleDto = rules.create(CurrentUser.id(), request.keyword, request.categoryId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) = rules.delete(CurrentUser.id(), id)
}
