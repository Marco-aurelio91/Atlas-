package com.unyx.atlas.feature.chat.agent

/**
 * Policy Engine (ATLAS_PLANO_MELHORIAS_EVOLUCAO_COGNITIVA.md section 17), minimal slice: a single
 * centralized declaration of which tools are "high impact" — irreversible, or visible to someone
 * other than the user (reaches another person, not just this device) — so that judgment call lives
 * in one place instead of being re-decided ad hoc at every call site that might care about it.
 *
 * Deliberately NOT wired to any new confirmation dialog — the plan's "revisão humana antes de ações
 * de alto impacto" is a real UX decision (what it looks like, when it interrupts the user) that
 * belongs in its own pass, not smuggled in here. For now this only feeds
 * `ChatViewModel.logEpisode`'s importance calculation: a failed high-impact action (a WhatsApp
 * message that didn't send) is worth remembering more than a failed low-impact one (brightness).
 */
val HIGH_IMPACT_TOOLS: Set<String> = setOf(
    "send_whatsapp_message",
    "call_person",
)
