package io.pocketledger.categorization

import com.github.benmanes.caffeine.cache.Caffeine
import io.pocketledger.shared.ConflictException
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.Text
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.CategoryDirectory
import io.pocketledger.shared.ports.CategorySuggester
import io.pocketledger.shared.ports.FieldCipher
import io.pocketledger.shared.ports.UserDataExporter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

data class CategoryDto(val id: String, val name: String, val parentId: String?, val custom: Boolean, val children: List<CategoryDto> = emptyList())

data class RuleDto(val id: UUID, val keyword: String?, val categoryId: String)

/**
 * Arbre de catégories à deux niveaux : catégories par défaut (user_id NULL) + personnalisées.
 * Les identifiants sont hiérarchiques ("food.restaurants") : un budget sur "food" couvre ses enfants.
 */
@Service
class CategoryService(private val jdbc: JdbcClient) : CategoryDirectory {
    fun tree(userId: UUID): List<CategoryDto> {
        val all =
            jdbc
                .sql("SELECT id, name, parent_id, user_id FROM category WHERE user_id IS NULL OR user_id = :u ORDER BY name")
                .param("u", userId)
                .query { rs, _ ->
                    CategoryDto(rs.getString("id"), rs.getString("name"), rs.getString("parent_id"), rs.getObject("user_id") != null)
                }.list()
        val children = all.filter { it.parentId != null }.groupBy { it.parentId }
        return all.filter { it.parentId == null }.map { it.copy(children = children[it.id].orEmpty()) }
    }

    fun create(userId: UUID, name: String, parentId: String?): CategoryDto {
        if (name.isBlank() || name.length > 60) throw ValidationException("invalid-category-name", "Nom de catégorie : 1 à 60 caractères")
        parentId?.let { parent ->
            val parentRow =
                jdbc
                    .sql("SELECT parent_id FROM category WHERE id = :id AND (user_id IS NULL OR user_id = :u)")
                    .param("id", parent)
                    .param("u", userId)
                    .query { rs, _ -> rs.getString("parent_id") ?: "" }
                    .optional()
                    .orElseThrow { NotFoundException("Catégorie", parent) }
            if (parentRow.isNotEmpty()) throw ValidationException("category-depth", "Deux niveaux de catégories au maximum")
        }
        val slug = "c-" + UUID.randomUUID().toString().take(8)
        val id = parentId?.let { "$it.$slug" } ?: slug
        jdbc
            .sql("INSERT INTO category (id, user_id, parent_id, name, created_at) VALUES (:id, :u, :p, :n, now())")
            .param("id", id)
            .param("u", userId)
            .param("p", parentId)
            .param("n", name.trim())
            .update()
        return CategoryDto(id, name.trim(), parentId, true)
    }

    fun rename(userId: UUID, id: String, name: String) {
        val updated =
            jdbc
                .sql("UPDATE category SET name = :n WHERE id = :id AND user_id = :u")
                .param("n", name.trim())
                .param("id", id)
                .param("u", userId)
                .update()
        if (updated == 0) throw NotFoundException("Catégorie personnalisée", id)
    }

    override fun exists(userId: UUID, categoryId: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM category WHERE id = :id AND (user_id IS NULL OR user_id = :u))")
            .param("id", categoryId)
            .param("u", userId)
            .query(Boolean::class.javaObjectType)
            .single()
}

/** Règles « si le libellé contient X → catégorie Y » (FR-41) ; le mot-clé est chiffré au repos. */
@Service
class RuleService(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    private val categories: CategoryService,
) : CategorySuggester {
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build<UUID, List<Pair<String, String>>>()

    fun list(userId: UUID): List<RuleDto> =
        jdbc
            .sql("SELECT id, keyword_enc, category_id FROM category_rule WHERE user_id = :u ORDER BY created_at")
            .param("u", userId)
            .query { rs, _ ->
                RuleDto(rs.getObject("id", UUID::class.java), cipher.decrypt(userId, rs.getString("keyword_enc")), rs.getString("category_id"))
            }.list()

    fun create(userId: UUID, keyword: String, categoryId: String): RuleDto {
        val normalized = Text.normalize(keyword)
        if (normalized.length < 2) throw ValidationException("invalid-keyword", "Mot-clé de 2 caractères minimum")
        if (!categories.exists(userId, categoryId)) throw ValidationException("unknown-category", "Catégorie inconnue : $categoryId")
        if (list(userId).any { it.keyword?.let(Text::normalize) == normalized }) {
            throw ConflictException("rule-exists", "Une règle existe déjà pour ce mot-clé")
        }
        val id = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO category_rule (id, user_id, keyword_enc, category_id, created_at) VALUES (:id, :u, :k, :c, now())")
            .param("id", id)
            .param("u", userId)
            .param("k", cipher.encrypt(userId, keyword.trim()))
            .param("c", categoryId)
            .update()
        cache.invalidate(userId)
        return RuleDto(id, keyword.trim(), categoryId)
    }

    fun delete(userId: UUID, ruleId: UUID) {
        val deleted = jdbc.sql("DELETE FROM category_rule WHERE id = :id AND user_id = :u").param("id", ruleId).param("u", userId).update()
        if (deleted == 0) throw NotFoundException("Règle", ruleId)
        cache.invalidate(userId)
    }

    /** Le mot-clé le plus long l'emporte (plus spécifique). */
    override fun suggest(userId: UUID, label: String, merchant: String?): String? {
        val haystack = Text.normalize(listOfNotNull(label, merchant).joinToString(" "))
        val rules =
            cache.get(userId) {
                list(userId)
                    .mapNotNull { r -> r.keyword?.let { Text.normalize(it) to r.categoryId } }
                    .sortedByDescending { it.first.length }
            }!!
        return rules.firstOrNull { haystack.contains(it.first) }?.second
    }
}

@Component
class CategorizationExporter(private val categories: CategoryService, private val rules: RuleService) : UserDataExporter {
    override val section = "categorization"

    override fun export(userId: UUID): Any =
        mapOf(
            "customCategories" to categories.tree(userId).flatMap { listOf(it) + it.children }.filter { it.custom },
            "rules" to rules.list(userId),
        )
}
