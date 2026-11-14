package io.pocketledger.shared

/** Erreur métier : le code devient le `type` du problem+json (RFC 9457). */
open class DomainException(val code: String, message: String) : RuntimeException(message)

class ValidationException(code: String, message: String) : DomainException(code, message)

class NotFoundException(what: String, id: Any) : DomainException("not-found", "$what introuvable : $id")

/** Conflit fonctionnel (état incompatible), distinct du conflit de version. */
class ConflictException(code: String, message: String) : DomainException(code, message)

/** Authentification refusée ou jeton invalide (401). */
class UnauthorizedException(code: String, message: String) : DomainException(code, message)

/** Version attendue d'un agrégat ou révision d'une transaction obsolète (If-Match → 412). */
class StaleVersionException(message: String) : DomainException("stale-version", message)

/** Deux écritures concurrentes sur le même agrégat : rejouée par le CommandExecutor. */
class ConcurrencyException(val aggregateId: Any, val expectedVersion: Int) :
    RuntimeException("Conflit de version sur $aggregateId (attendue $expectedVersion)")
