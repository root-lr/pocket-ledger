package io.pocketledger.alerting

import io.pocketledger.events.AlertRaised
import io.pocketledger.events.AlertTypes
import io.pocketledger.events.Context
import io.pocketledger.platform.eventstore.StoredEvent
import io.pocketledger.platform.processing.EventProcessor
import io.pocketledger.shared.ports.PushTokenDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class PushMessage(val title: String, val body: String, val data: Map<String, String>)

interface PushSender {
    fun send(token: String, message: PushMessage)
}

/**
 * Adaptateur push par défaut : journalise sans contenu sensible.
 * Un adaptateur FCM (HTTP v1 + compte de service) se branche en implémentant [PushSender].
 */
@Component
class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(token: String, message: PushMessage) {
        log.info("[push simulé] jeton={}… titre={}", token.take(8), message.title)
    }
}

/** Consomme le topic des alertes et notifie chaque appareil actif de l'utilisateur (FR-75). */
@Component
class NotificationProcessor(
    private val tokens: PushTokenDirectory,
    private val sender: PushSender,
) : EventProcessor {
    override val name = "notifications"
    override val contexts = setOf(Context.ALERTS)
    override val rebuildable = false

    override fun handles(eventType: String) = eventType == "AlertRaised"

    override fun process(event: StoredEvent) {
        val alert = event.event as AlertRaised
        val message = PushMessage(title(alert.alertType), body(alert), mapOf("alertId" to alert.alertId.toString(), "type" to alert.alertType))
        tokens.tokens(event.userId).forEach { sender.send(it, message) }
    }

    private fun title(type: String): String =
        when (type) {
            AlertTypes.BUDGET_THRESHOLD -> "Budget bientôt atteint"
            AlertTypes.UNUSUAL_SPENDING -> "Dépense inhabituelle"
            AlertTypes.LOW_BALANCE -> "Solde bas"
            AlertTypes.RECURRING_PAYMENT -> "Abonnement"
            AlertTypes.PROBABLE_DUPLICATE -> "Doublon probable"
            else -> "PocketLedger"
        }

    // Le corps ne contient jamais le libellé ou le marchand : un push transite par des serveurs tiers.
    private fun body(alert: AlertRaised): String {
        val d = alert.details
        return when (alert.alertType) {
            AlertTypes.BUDGET_THRESHOLD -> "${d["thresholdPercent"]} % du budget ${d["categoryId"]} consommé"
            AlertTypes.UNUSUAL_SPENDING -> "Dépense de ${d["amount"]} ${d["currency"]}, bien au-dessus de vos habitudes"
            AlertTypes.LOW_BALANCE -> "Solde passé sous ${d["threshold"]} ${d["currency"]}"
            AlertTypes.RECURRING_PAYMENT ->
                if (d["kind"] == "increased") {
                    "Un abonnement passe de ${d["previous"]} à ${d["amount"]} ${d["currency"]}"
                } else {
                    "Nouvel abonnement détecté : ${d["amount"]} ${d["currency"]}"
                }
            AlertTypes.PROBABLE_DUPLICATE -> "Deux dépenses identiques de ${d["amount"]} ${d["currency"]} en moins de 24 h"
            else -> "Nouvelle alerte"
        }
    }
}
