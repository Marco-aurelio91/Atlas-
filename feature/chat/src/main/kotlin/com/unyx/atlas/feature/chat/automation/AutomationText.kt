package com.unyx.atlas.feature.chat.automation

/** Tool and UI share these strict parsers so an edited routine has the same accepted syntax as a new one. */
fun parseAutomationTrigger(raw: String): AutomationTrigger? {
    if (raw.startsWith("horario:")) {
        val parts = raw.removePrefix("horario:").split(":", limit = 3)
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        val days = parts.getOrNull(2)?.split(",")?.mapNotNull(::parseWeekday)?.toSet().orEmpty()
        val hasInvalidDay = parts.getOrNull(2)?.split(",")?.any { parseWeekday(it) == null } == true
        return if (hour != null && hour in 0..23 && minute != null && minute in 0..59 && !hasInvalidDay) {
            AutomationTrigger.DailyTime(hour, minute, days)
        } else null
    }
    return when (raw) {
        "carregador_conectado" -> AutomationTrigger.ChargerConnected(true)
        "carregador_desconectado" -> AutomationTrigger.ChargerConnected(false)
        "fone_conectado" -> AutomationTrigger.HeadphonesConnected(true)
        "fone_desconectado" -> AutomationTrigger.HeadphonesConnected(false)
        "bateria_baixa" -> AutomationTrigger.BatteryLow
        else -> null
    }
}

fun parseWeekday(raw: String): Int? = when (raw.trim().lowercase()) {
    "seg", "segunda", "segunda-feira" -> 1
    "ter", "terca", "terça", "terca-feira", "terça-feira" -> 2
    "qua", "quarta", "quarta-feira" -> 3
    "qui", "quinta", "quinta-feira" -> 4
    "sex", "sexta", "sexta-feira" -> 5
    "sab", "sábado", "sabado" -> 6
    "dom", "domingo" -> 7
    else -> null
}

fun parseAutomationAction(raw: String): AutomationAction? {
    val parts = raw.split(":")
    return when (parts.getOrNull(0)) {
        "abrir_app" -> parts.getOrNull(1)?.ifBlank { null }?.let { AutomationAction.OpenApp(it) }
        "volume" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let { level ->
            val stream = parts.getOrNull(2) ?: "media"
            if (stream !in setOf("media", "alarme", "chamada", "toque")) null else AutomationAction.SetVolume(level, stream)
        }
        "brilho" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let(AutomationAction::SetBrightness)
        "notificar" -> raw.removePrefix("notificar:").ifBlank { null }?.let { AutomationAction.ShowNotification(it) }
        else -> null
    }
}

fun automationTriggerText(trigger: AutomationTrigger): String = when (trigger) {
    is AutomationTrigger.ChargerConnected -> if (trigger.connected) "conectar o carregador" else "desconectar o carregador"
    is AutomationTrigger.HeadphonesConnected -> if (trigger.connected) "conectar o fone" else "desconectar o fone"
    AutomationTrigger.BatteryLow -> "a bateria entrar no nível baixo do Android"
    is AutomationTrigger.DailyTime -> {
        val schedule = "às %02d:%02d".format(trigger.hour, trigger.minute)
        if (trigger.daysOfWeek.isEmpty()) "todos os dias $schedule"
        else "${trigger.daysOfWeek.sorted().joinToString(", ") { WEEKDAY_NAMES[it - 1] }} $schedule"
    }
    is AutomationTrigger.Geofence -> "${if (trigger.entering) "chegar" else "sair"} de ${trigger.placeName} (raio de ${trigger.radiusMeters.toInt()} m)"
}

fun automationActionText(action: AutomationAction): String = when (action) {
    is AutomationAction.OpenApp -> "abrir ${action.appLabel}"
    is AutomationAction.SetVolume -> "ajustar o volume de ${action.stream} para ${action.level}%"
    is AutomationAction.SetBrightness -> "ajustar o brilho para ${action.level}%"
    is AutomationAction.ShowNotification -> "mostrar a notificação ‘${action.text}’"
}

fun automationSummary(automation: Automation): String = buildString {
    append("Quando ${automationTriggerText(automation.trigger)}")
    automation.condition?.let { condition ->
        when {
            condition.afterHour != null && condition.beforeHour != null -> append(" entre ${condition.afterHour}h e ${condition.beforeHour}h")
            condition.afterHour != null -> append(" depois das ${condition.afterHour}h")
            condition.beforeHour != null -> append(" antes das ${condition.beforeHour}h")
        }
    }
    append(", ${automationActionText(automation.action)}.")
}

fun automationTriggerRaw(trigger: AutomationTrigger): String = when (trigger) {
    is AutomationTrigger.ChargerConnected -> if (trigger.connected) "carregador_conectado" else "carregador_desconectado"
    is AutomationTrigger.HeadphonesConnected -> if (trigger.connected) "fone_conectado" else "fone_desconectado"
    AutomationTrigger.BatteryLow -> "bateria_baixa"
    is AutomationTrigger.DailyTime -> "horario:%02d:%02d".format(trigger.hour, trigger.minute) +
        trigger.daysOfWeek.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(prefix = ":", separator = ",") { WEEKDAY_CODES[it - 1] }.orEmpty()
    is AutomationTrigger.Geofence -> "localizacao:${trigger.placeName}:${if (trigger.entering) "chegada" else "saida"}"
}

fun automationActionRaw(action: AutomationAction): String = when (action) {
    is AutomationAction.OpenApp -> "abrir_app:${action.appLabel}"
    is AutomationAction.SetVolume -> "volume:${action.level}:${action.stream}"
    is AutomationAction.SetBrightness -> "brilho:${action.level}"
    is AutomationAction.ShowNotification -> "notificar:${action.text}"
}

private val WEEKDAY_CODES = listOf("seg", "ter", "qua", "qui", "sex", "sab", "dom")
private val WEEKDAY_NAMES = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
