package com.unyx.atlas.feature.chat.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real half of the master plan's "Arquitetura de Subagentes" (section 9.3): a genuine Kotlin
 * `SharedFlow` event bus — the exact technology the plan names ("barramento de eventos local
 * implementado com Kotlin Channel e SharedFlow... nenhuma chamada direta entre agentes"). Every
 * tool call publishes one [AgentEvent] here after it runs; independent subscribers (episodic
 * logging, routine inference — see ChatViewModel's `subscribeToAgentEvents`) react to it without
 * calling each other or the publisher directly.
 *
 * Update (2026-09-30): the tool-execution dispatcher itself *was* extracted, into
 * [AtlasToolExecutor] — not for its own sake (that split was explicitly rejected earlier as
 * low-value pure-reorg, see git history), but because the lightweight Assistant screen (invoked by
 * the OS assist gesture) needed to run the exact same tools as the full chat without duplicating
 * ~700 lines of dispatcher. [TOOL_AGENT] still gives every tool a real specialized-agent
 * classification, and this bus still gives real pub/sub decoupling for the side effects (episode
 * logging, routine tracking) — unaffected by which screen actually called the tool.
 */
@Singleton
class AgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun publish(event: AgentEvent) {
        _events.emit(event)
    }
}
