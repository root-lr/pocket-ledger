package io.pocketledger.shared.ports

import io.pocketledger.shared.CurrencyCode
import java.time.LocalDate
import java.util.UUID

/*
 * API internes explicites entre modules. Un module ne lit jamais les tables d'un autre :
 * il passe par ces interfaces (implémentées par le module propriétaire) ou par les événements Kafka.
 */

/** Chiffrement de champs avec la clé de l'utilisateur (module crypto). */
interface FieldCipher {
    fun encrypt(userId: UUID, plaintext: String): String

    /** null si la clé de l'utilisateur a été détruite (crypto-shredding). */
    fun decrypt(userId: UUID, ciphertext: String): String?

    fun encryptBytes(userId: UUID, plaintext: ByteArray): ByteArray

    fun decryptBytes(userId: UUID, ciphertext: ByteArray): ByteArray?

    /** Index aveugle HMAC propre à l'utilisateur, pour grouper ou rechercher sans texte clair. */
    fun blindIndex(userId: UUID, value: String): String

    /** Index aveugles des mots normalisés d'un texte (recherche plein texte FR-25). */
    fun blindTokens(userId: UUID, text: String): List<String>
}

/** Référentiel des devises ISO 4217 (module fx). */
interface CurrencyCatalog {
    fun isSupported(code: CurrencyCode): Boolean

    fun minorDigits(code: CurrencyCode): Int

    fun all(): List<CurrencyInfo>
}

data class CurrencyInfo(val code: String, val minorDigits: Int, val name: String)

/** Conversion au taux d'une date (module fx). null = taux indisponible, jamais un montant faux. */
interface FxConverter {
    fun convert(amountMinor: Long, from: CurrencyCode, to: CurrencyCode, date: LocalDate): Long?
}

/** Préférences utilisateur (module identity). */
interface UserPreferencesQuery {
    fun referenceCurrency(userId: UUID): CurrencyCode

    fun isAlertEnabled(userId: UUID, alertType: String): Boolean

    /** Jour de début du mois budgétaire (1 à 28). */
    fun budgetMonthStartDay(userId: UUID): Int
}

/** Suggestion de catégorie à partir du libellé en clair (module categorization). */
interface CategorySuggester {
    fun suggest(userId: UUID, label: String, merchant: String?): String?
}

/** Vérification qu'une catégorie existe pour l'utilisateur (module categorization). */
interface CategoryDirectory {
    fun exists(userId: UUID, categoryId: String): Boolean
}

/** Chaque module exporte les données d'un utilisateur, déchiffrées (FR-80). */
interface UserDataExporter {
    val section: String

    fun export(userId: UUID): Any
}

/** Chaque module fournit ses changements depuis un curseur pour la sync mobile (FR-26). */
interface SyncSource {
    val section: String

    fun changesSince(userId: UUID, position: Long, limit: Int): List<SyncItem>
}

data class SyncItem(val id: String, val position: Long, val deleted: Boolean, val data: Any?)

/** Jetons push des appareils actifs d'un utilisateur (module identity). */
interface PushTokenDirectory {
    fun tokens(userId: UUID): List<String>
}
