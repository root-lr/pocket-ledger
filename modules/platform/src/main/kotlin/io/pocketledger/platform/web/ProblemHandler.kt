package io.pocketledger.platform.web

import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.CurrencyMismatchException
import io.pocketledger.shared.DomainException
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.StaleVersionException
import io.pocketledger.shared.UnauthorizedException
import io.pocketledger.shared.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/** Erreurs au format RFC 9457 (application/problem+json). */
@RestControllerAdvice
class ProblemHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ProblemDetail {
        val status =
            when (e) {
                is NotFoundException -> HttpStatus.NOT_FOUND
                is UnauthorizedException -> HttpStatus.UNAUTHORIZED
                is StaleVersionException -> HttpStatus.PRECONDITION_FAILED
                is ConflictException -> HttpStatus.CONFLICT
                is ValidationException, is CurrencyMismatchException -> HttpStatus.UNPROCESSABLE_ENTITY
                else -> HttpStatus.UNPROCESSABLE_ENTITY
            }
        return problem(status, e.code, e.message ?: e.code)
    }

    @ExceptionHandler(AccessDeniedException::class)
    @Suppress("UnusedParameter")
    fun denied(e: AccessDeniedException): ProblemDetail = problem(HttpStatus.FORBIDDEN, "forbidden", "Accès refusé")

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegal(e: IllegalArgumentException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "bad-request", e.message ?: "Requête invalide")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("Erreur inattendue", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erreur interne")
    }

    companion object {
        fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                type = URI.create("https://pocketledger.dev/problems/$code")
                title = code
            }
    }
}
