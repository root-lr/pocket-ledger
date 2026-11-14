package io.pocketledger.identity

import com.github.benmanes.caffeine.cache.Caffeine
import io.pocketledger.events.AlertTypes
import io.pocketledger.events.Context
import io.pocketledger.events.ReferenceCurrencyChanged
import io.pocketledger.platform.eventstore.CommandExecutor
import io.pocketledger.platform.eventstore.EventContext
import io.pocketledger.platform.eventstore.EventStore
import io.pocketledger.platform.web.MoneyFormat
import io.pocketledger.shared.CurrencyCode
import io.pocketledger.shared.NotFoundException
import io.pocketledger.shared.ValidationException
import io.pocketledger.shared.ports.UserPreferencesQuery
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

data class Preferences(
    val referenceCurrency: String,
    val timezone: String,
    val budgetMonthStartDay: Int,
    val language: String,
    val disabledAlerts: List<String>,
)

data class PreferencesPatch(
    val referenceCurrency: String? = null,
    val timezone: String? = null,
    val budgetMonthStartDay: Int? = null,
    val language: String? = null,
    val disabledAlerts: List<String>? = null,
)

@Service
class PreferencesService(
    private val jdbc: JdbcClient,
    private val money: MoneyFormat,
    private val eventStore: EventStore,
    private val commands: CommandExecutor,
) : UserPreferencesQuery {
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(50_000)
            .build<UUID, Preferences>()

    fun createDefaults(userId: UUID, referenceCurrency: String?, timezone: String?) {
        val currency = money.currency(referenceCurrency ?: "EUR").value
        val zone = timezone?.let(::validZone) ?: "UTC"
        jdbc
            .sql(
                "INSERT INTO user_preferences (user_id, reference_currency, timezone, budget_month_start_day, language, disabled_alerts) " +
                    "VALUES (:u, :c, :tz, 1, 'fr', '{}')",
            ).param("u", userId)
            .param("c", currency)
            .param("tz", zone)
            .update()
    }

    fun get(userId: UUID): Preferences =
        cache.get(userId) {
            jdbc
                .sql(
                    "SELECT reference_currency, timezone, budget_month_start_day, language, disabled_alerts " +
                        "FROM user_preferences WHERE user_id = :u",
                ).param("u", userId)
                .query { rs, _ ->
                    @Suppress("UNCHECKED_CAST")
                    Preferences(
                        rs.getString("reference_currency").trim(),
                        rs.getString("timezone"),
                        rs.getInt("budget_month_start_day"),
                        rs.getString("language"),
                        (rs.getArray("disabled_alerts").array as Array<String>).toList(),
                    )
                }.optional()
                .orElseThrow { NotFoundException("Préférences", userId) }
        }!!

    @Transactional
    fun update(userId: UUID, patch: PreferencesPatch): Preferences {
        val current = get(userId)
        val next =
            current.copy(
                referenceCurrency = patch.referenceCurrency?.let { money.currency(it).value } ?: current.referenceCurrency,
                timezone = patch.timezone?.let(::validZone) ?: current.timezone,
                budgetMonthStartDay =
                    patch.budgetMonthStartDay?.also {
                        if (it !in 1..28) throw ValidationException("invalid-start-day", "Jour de début entre 1 et 28")
                    } ?: current.budgetMonthStartDay,
                language = patch.language?.take(5) ?: current.language,
                disabledAlerts =
                    patch.disabledAlerts?.also { list ->
                        list.firstOrNull { it !in AlertTypes.all }?.let {
                            throw ValidationException("unknown-alert-type", "Type d'alerte inconnu : $it")
                        }
                    } ?: current.disabledAlerts,
            )
        jdbc
            .sql(
                """
                UPDATE user_preferences SET reference_currency = :c, timezone = :tz, budget_month_start_day = :d,
                       language = :l, disabled_alerts = :a WHERE user_id = :u
                """.trimIndent(),
            ).param("c", next.referenceCurrency)
            .param("tz", next.timezone)
            .param("d", next.budgetMonthStartDay)
            .param("l", next.language)
            .param("a", next.disabledAlerts.toTypedArray())
            .param("u", userId)
            .update()
        cache.invalidate(userId)

        if (next.referenceCurrency != current.referenceCurrency) {
            val aggregateId = UUID.nameUUIDFromBytes("preferences:$userId".toByteArray())
            commands.execute {
                eventStore.append(
                    "UserPreferences", Context.IDENTITY, aggregateId, eventStore.currentVersion(aggregateId), userId,
                    listOf(ReferenceCurrencyChanged(current.referenceCurrency, next.referenceCurrency)), EventContext.current(),
                )
            }
        }
        return next
    }

    fun evict(userId: UUID) = cache.invalidate(userId)

    override fun referenceCurrency(userId: UUID): CurrencyCode = CurrencyCode(get(userId).referenceCurrency)

    override fun budgetMonthStartDay(userId: UUID): Int = runCatching { get(userId).budgetMonthStartDay }.getOrDefault(1)

    override fun isAlertEnabled(userId: UUID, alertType: String): Boolean =
        runCatching { alertType !in get(userId).disabledAlerts }.getOrDefault(false)

    private fun validZone(zone: String): String =
        runCatching { ZoneId.of(zone).id }.getOrElse { throw ValidationException("invalid-timezone", "Fuseau horaire invalide : $zone") }
}
