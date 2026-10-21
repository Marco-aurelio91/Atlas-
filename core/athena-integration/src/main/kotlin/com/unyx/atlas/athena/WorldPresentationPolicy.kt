package com.unyx.atlas.athena

import com.unyx.athena.core.*
import java.time.Instant

data class WorldPresentationContext(
    val enabled: Boolean, val quiet: Boolean, val watched: Set<WorldSelector>, val dismissed: Set<String> = emptySet(),
)
data class WorldCard(val id: String, val title: String, val detail: String, val expiresAt: Instant)

/** Personal choice stays in ATLAS. No event severity directly executes or authorizes an action. */
object WorldPresentationPolicy {
    fun decide(event: WorldEvent, context: WorldPresentationContext, now: Instant): WorldCard? {
        if (!context.enabled || context.quiet || event.selector !in context.watched ||
            event.id in context.dismissed || suppressionKey(event) in context.dismissed || !event.fresh(now)) return null
        val text = when (val p = event.payload) {
            is WorldPayload.Service -> when (p.condition) {
                ServiceCondition.OPERATIONAL -> return null
                ServiceCondition.DEGRADED -> "O serviço informa desempenho degradado."
                ServiceCondition.MAJOR_OUTAGE -> "O serviço informa indisponibilidade parcial."
                ServiceCondition.CRITICAL_OUTAGE -> "O serviço informa indisponibilidade importante."
            }
            is WorldPayload.Weather -> when {
                p.conditions.weatherCode in setOf(65, 67, 75, 82, 95, 96, 99) -> "Condições atuais de chuva, neve forte ou trovoada na região consultada."
                p.conditions.temperatureC <= 0 -> "Temperatura atual de ${p.conditions.temperatureC.toInt()} °C na região consultada."
                p.conditions.temperatureC >= 35 -> "Temperatura atual de ${p.conditions.temperatureC.toInt()} °C na região consultada."
                else -> return null
            }
            is WorldPayload.SustainedCold -> "Duas observações recentes indicam temperatura de até 0 °C. Não é previsão nem alerta oficial."
        }
        val title = if (event.selector is WorldSelector.Service) event.source.provider else "Clima · ${event.source.provider}"
        return WorldCard(event.id, title, text, event.expiresAt)
    }

    fun suppressionKey(event: WorldEvent): String {
        val category = when (val p = event.payload) {
            is WorldPayload.Service -> p.condition.name
            is WorldPayload.SustainedCold -> "cold"
            is WorldPayload.Weather -> when {
                p.conditions.weatherCode in setOf(65, 67, 75, 82, 95, 96, 99) -> "precipitation"
                p.conditions.temperatureC <= 0 -> "cold"
                p.conditions.temperatureC >= 35 -> "heat"
                else -> "normal"
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest("${event.source.connectorId}|${event.selector}|$category".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
