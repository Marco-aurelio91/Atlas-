package com.unyx.atlas.feature.chat.agent

import org.json.JSONObject

/**
 * The specialized-agent categories from the original master plan's "Arquitetura de Subagentes"
 * (ATLAS_Plano_de_Desenvolvimento.md section 9.3), adapted onto this app's real architecture — see
 * [AgentEventBus]'s doc for what's genuinely different here vs. what wasn't rebuilt, and why.
 *
 * The plan's four agents (Produtividade, Comunicação, Contexto Ambiental, Memória) map cleanly onto
 * this app's existing tools; [DEVICE] is added for raw hardware toggles (open an app, adjust
 * volume/brightness) that don't fit any of the original four — the plan's Contexto Ambiental agent
 * is explicitly read-only ("nunca age diretamente — apenas informa"), so hardware actions needed
 * a real home.
 */
enum class Agent {
    /** Tarefas, compromissos, lembretes, projetos, automações — "o que precisa ser feito". */
    PRODUCTIVITY,
    /** Ligações, mensagens, pessoas e como elas se relacionam. */
    COMMUNICATION,
    /** Estado do mundo (clima) — só informa, nunca age (mirrors the plan's own description). */
    ENVIRONMENT,
    /** Conhecimento persistente sobre o usuário (fatos, preferências, decisões). */
    MEMORY,
    /** Controle direto de hardware/apps — not one of the plan's original four, see class doc. */
    DEVICE,
}

/** Which [Agent] owns each tool — every entry in ChatViewModel's `ATLAS_TOOLS` should have one
 * here; [find_related_people] and query-only tools are still classified (an agent can own a query
 * as well as an action). A tool with no entry falls back to [Agent.DEVICE] at the publish site. */
val TOOL_AGENT: Map<String, Agent> = mapOf(
    "create_task" to Agent.PRODUCTIVITY,
    "complete_task" to Agent.PRODUCTIVITY,
    "list_tasks" to Agent.PRODUCTIVITY,
    "create_project" to Agent.PRODUCTIVITY,
    "update_project" to Agent.PRODUCTIVITY,
    "create_reminder" to Agent.PRODUCTIVITY,
    "set_alarm" to Agent.PRODUCTIVITY,
    "update_working_memory" to Agent.PRODUCTIVITY,
    "create_automation" to Agent.PRODUCTIVITY,
    "list_automations" to Agent.PRODUCTIVITY,
    "delete_automation" to Agent.PRODUCTIVITY,
    "get_capabilities" to Agent.DEVICE,
    "open_internet_panel" to Agent.DEVICE,
    "send_whatsapp_message" to Agent.COMMUNICATION,
    "call_person" to Agent.COMMUNICATION,
    "save_person" to Agent.COMMUNICATION,
    "link_person" to Agent.COMMUNICATION,
    "find_related_people" to Agent.COMMUNICATION,
    "get_weather" to Agent.ENVIRONMENT,
    "save_fact" to Agent.MEMORY,
    "save_preference" to Agent.MEMORY,
    "save_place" to Agent.MEMORY,
    "save_decision" to Agent.MEMORY,
    "create_note" to Agent.MEMORY,
    "search_notes" to Agent.MEMORY,
    "update_note" to Agent.MEMORY,
    "open_app" to Agent.DEVICE,
    "set_volume" to Agent.DEVICE,
    "set_brightness" to Agent.DEVICE,
)

/** One tool call's outcome, as published to [AgentEventBus] — [input]/[result] are carried through
 * so subscribers (episodic logging, routine inference) can react without re-deriving them or
 * calling back into whatever produced the event. */
data class AgentEvent(
    val agent: Agent,
    val toolName: String,
    val input: JSONObject,
    val result: String,
    val success: Boolean,
)
