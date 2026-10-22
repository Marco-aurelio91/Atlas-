package com.unyx.atlas.data.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Atlas's two chat backends: [ClaudeClient] is the real one — vision, streaming, web
 * search — and [GeminiChatClient] is the emergency fallback for when Claude itself fails outright
 * (out of API credit, an outage, anything beyond ClaudeClient's own retry). Both now support
 * tool-calling with the same [ClaudeTool] list and `onToolCall` dispatcher — the fallback just
 * streams text as it is generated, so the chat remains responsive if Claude fails and Gemini is
 * used as the fallback.
 *
 * Both chat surfaces (ChatViewModel, AssistantViewModel) call this exactly how they called
 * [ClaudeClient] directly before — same `send(...)` shape — so swapping which one they inject was
 * the only change either needed.
 */
@Singleton
class AtlasChatClient @Inject constructor(
    private val claudeClient: ClaudeClient,
    private val geminiChatClient: GeminiChatClient,
) {
    val isConfigured: Boolean get() = claudeClient.isConfigured || geminiChatClient.isConfigured

    suspend fun send(
        history: List<ChatMessage>,
        accessToken: String,
        systemPrompt: String? = DEFAULT_SYSTEM_PROMPT,
        maxTokens: Int = 1024,
        tools: List<ClaudeTool> = emptyList(),
        onToolCall: suspend (name: String, input: JSONObject) -> String = { _, _ -> "" },
        onActivity: (toolName: String?) -> Unit = {},
        onTextUpdate: (text: String) -> Unit = {},
        // Per-request context sent after the cacheable system prompt (see ClaudeClient.buildSystemField).
        volatileContext: String? = null,
    ): ClaudeReply {
        var localToolExecuted = false
        val dispatchedActions = mutableListOf<ClaudeAction>()
        val guardedToolCall: suspend (String, JSONObject) -> String = { name, input ->
            localToolExecuted = true
            val result = try {
                onToolCall(name, input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Não consegui executar: ${failure.message ?: "erro inesperado"}"
            }
            dispatchedActions += ClaudeAction(name, result)
            result
        }
        if (claudeClient.isConfigured) {
            val result = try {
                Result.success(
                    claudeClient.send(
                        history, accessToken, systemPrompt, maxTokens, tools, guardedToolCall, onActivity, onTextUpdate,
                        volatileContext = volatileContext,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            result.onSuccess { return it }
            // Claude failed outright — a tool itself failing is already caught inside ClaudeClient's
            // own loop and reported back as ordinary tool_result text, never thrown this far up.
            if (!geminiChatClient.isConfigured) {
                if (dispatchedActions.isNotEmpty()) return partialToolReply(dispatchedActions)
                throw result.exceptionOrNull()!!
            }
            // A failed Claude stream may have emitted partial text. Clear it before switching to
            // Gemini so its answer starts as a fresh message.
            if (localToolExecuted) return partialToolReply(dispatchedActions)
            onTextUpdate("")
        } else if (!geminiChatClient.isConfigured) {
            error("O gateway de IA do Atlas não está configurado neste app.")
        }
        onActivity(null)
        // Only when there's genuinely nothing to call does the fallback need to say so — with
        // [tools] non-empty it has the exact same dispatch Claude does, so telling it to deny having
        // any ability to act would now just be false and contradict its own tool results. Without
        // this caveat (back when the fallback truly was text-only), a real device bug was traced to
        // exactly this prompt being missing: Gemini confidently claimed to have lowered the volume —
        // in character, continuing the conversation's own pattern — while actually doing nothing at
        // all (confirmed live: the system volume overlay never even appeared). Explicit, blunt, and
        // last in the prompt so it can't get lost above a long memory digest.
        val fallbackSystemPrompt = buildString {
            if (!systemPrompt.isNullOrBlank()) {
                append(systemPrompt)
                append("\n\n")
            }
            if (!volatileContext.isNullOrBlank()) {
                append(volatileContext)
                append("\n\n")
            }
            if (tools.isEmpty()) {
                append(
                    "IMPORTANTE: agora você está respondendo em modo reserva, sem acesso a nenhuma ferramenta " +
                        "do dispositivo (não pode abrir apps, ajustar volume, brilho, criar alarmes, lembretes ou " +
                        "qualquer outra ação real, mesmo que o histórico mostre você tendo feito isso antes). " +
                        "Nunca diga que executou ou ajustou algo. Se o pedido exigir uma ação real, explique que " +
                        "não consegue fazer isso agora (o assistente principal está indisponível) em vez de fingir " +
                        "que fez.",
                )
            }
            if (tools.any { it.serverType != null }) {
                append(
                    "\n\nNo modo reserva você não tem acesso à busca na web. Não afirme que pesquisou nem " +
                        "apresente informações atuais como verificadas; informe essa limitação quando relevante.",
                )
            }
        }
        val reply = try {
            geminiChatClient.send(
                history,
                accessToken,
                fallbackSystemPrompt,
                maxTokens,
                tools,
                guardedToolCall,
                onActivity,
                onTextUpdate,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (dispatchedActions.isEmpty()) throw failure
            return partialToolReply(dispatchedActions)
        }
        onTextUpdate(reply.text)
        return reply.copy(answeredByFallback = true)
    }

    private fun partialToolReply(actions: List<ClaudeAction>) = ClaudeReply(
        text = "Uma ou mais ferramentas foram chamadas, mas a resposta foi interrompida. Confira os resultados exibidos antes de tentar novamente.",
        actions = actions,
    )
}
