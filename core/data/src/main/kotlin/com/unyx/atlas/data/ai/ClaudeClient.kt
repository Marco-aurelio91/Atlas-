package com.unyx.atlas.data.ai

import com.unyx.atlas.data.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One tool call Claude actually made, kept as structured data (not baked into display text) so the
 * UI can render a proper Action Card / memory caption per [toolName] rather than a hardcoded string
 * — see feature:chat's ChatScreen.kt's actionCardSpec()/memoryPhrase(). [failed] is derived once, at
 * persist time, from whether [resultSummary] (the same text Claude saw as its tool_result) started
 * with "Não consegui" — every tool's failure message must keep that prefix for this to classify
 * correctly. */
data class PersistedAction(val toolName: String, val resultSummary: String, val failed: Boolean)

/** role is "user" or "assistant" — matches the Anthropic Messages API's own vocabulary directly.
 * [usedMemory] is UI-only metadata (not sent to the API) marking that this reply's system prompt
 * included memory context, so the chat can show a small "memory used" indicator on it.
 * [modelText] is what Claude itself actually generated, kept separate from [content] (which used to
 * also carry an appended "✓ ..." confirmation line for display, before [actions] existed as its own
 * structured field). Replaying our own injected confirmation text back to the model as if Claude had
 * said it teaches Claude, by imitation, to reproduce that surface pattern in later turns as plain
 * text *without* actually calling the tool again — so history sent back to the API uses [modelText],
 * never [content], for assistant turns. Null for user turns and for messages persisted before this
 * field existed. [actions] is empty for user turns and for messages persisted before it existed
 * (their old confirmation text, if any, is still baked into [content] from that older scheme).
 * [memoryLabels] are short, user-facing names for whatever was in the memory digest this reply's
 * system prompt included (e.g. "mora_em", a person's name) — shown when the user taps
 * [usedMemory]'s "🧠 Memória utilizada" caption, so that claim isn't just words with nothing behind
 * it; empty whenever [usedMemory] is false or for messages persisted before this field existed.
 * [imageBase64] is a user-attached photo (JPEG, pre-downscaled — see feature:chat's ChatViewModel
 * image picker), sent to Claude as a real vision content block alongside the text, not just
 * described in words; always null for assistant turns. [pdfBase64]/[pdfFileName] are a user-attached
 * PDF (raw bytes, no re-encoding possible the way a photo gets compressed) sent as Claude's own
 * `document` content block — Claude reads the actual PDF content (text, layout, embedded images),
 * not just a filename; [pdfFileName] is display-only (shown in the attachment chip), never sent to
 * the API. Always null for assistant turns, same as [imageBase64]. [answeredByFallback] marks a
 * reply that came from [AtlasChatClient]'s Gemini fallback (Claude itself failed outright — out of
 * credit, an outage) rather than Claude. */
data class ChatMessage(
    val role: String,
    val content: String,
    val usedMemory: Boolean = false,
    val modelText: String? = null,
    val actions: List<PersistedAction> = emptyList(),
    val memoryLabels: List<String> = emptyList(),
    val imageBase64: String? = null,
    val pdfBase64: String? = null,
    val pdfFileName: String? = null,
    val answeredByFallback: Boolean = false,
)

/** A function Claude can call mid-conversation — see [ClaudeClient.send]'s `tools` parameter.
 * [serverType] marks an Anthropic-native *server* tool (e.g. "web_search_20250305") that Anthropic
 * executes itself within the same request — no [description]/[inputSchema] sent for it, and no
 * round trip through [ClaudeClient.send]'s `onToolCall` (see the `server_tool_use`/
 * `web_search_tool_result` handling in [ClaudeClient.requestOnceStreaming]). [maxUses] caps how many
 * times a server tool may fire per request — Anthropic bills web search per use, so this bounds a
 * single verbose question from triggering an unbounded number of searches. */
data class ClaudeTool(
    val name: String,
    val description: String = "",
    val inputSchema: JSONObject = JSONObject(),
    val serverType: String? = null,
    val maxUses: Int? = null,
)

/** Anthropic's native web-search server tool — Claude decides on its own when a question needs
 * current/external information it wasn't trained on and issues the search itself; the result comes
 * back as `web_search_tool_result` content in the very same response, no client dispatch involved.
 * Shared the same way [com.unyx.atlas.feature.chat.agent.ATLAS_TOOLS] is: both the full chat and the
 * lightweight Assistant screen append this to their tool list. */
val WEB_SEARCH_TOOL = ClaudeTool(name = "web_search", serverType = "web_search_20250305", maxUses = 3)

/** One tool call Claude actually made and got a result back from, for the caller to surface (see
 * [PersistedAction]) — Atlas *did* something, not just talked. */
data class ClaudeAction(val toolName: String, val resultSummary: String)

/** [answeredByFallback] — see [ChatMessage]'s own doc on the same flag; [AtlasChatClient] sets this
 * true when [text] came from its Gemini fallback instead of Claude. */
data class ClaudeReply(val text: String, val actions: List<ClaudeAction> = emptyList(), val answeredByFallback: Boolean = false)

/** Kept short on purpose — a longer system prompt is billed as input tokens on every single
 * request, and asking for concise replies also cuts the (more expensive) output-token cost. Public
 * (not internal) so any module calling [ClaudeClient.send] can reuse it instead of duplicating the
 * instruction text — feature:chat prepends it to its memory context, other one-shot callers (e.g.
 * feature:notes' AI actions) pass their own dedicated [ClaudeClient.send] systemPrompt instead.
 *
 * The context-resolution paragraph (2026-09-20) targets a real, live-reproduced failure: full
 * conversation history is already sent on every call (see [send]'s `history` param — this was never
 * a "missing context" bug), but a short vague follow-up like "o que recomenda" right after "estou
 * saindo do trabalho" still got a generic "recomendo o quê exatamente?" instead of the model using
 * the turn right above it. This is a cheap, scoped instruction fix — not the full multi-stage
 * context/reference-resolution pipeline a since-shelved design doc proposed — tried first because
 * the raw material (history) was already there; only build that heavier machinery if this alone
 * proves insufficient in practice. */
const val DEFAULT_SYSTEM_PROMPT =
    "Você é o Atlas, o assistente pessoal do launcher Android do usuário. Responda de forma direta e concisa. Não repita a pergunta nem resuma o que já foi dito antes de responder. " +
        "Antes de pedir esclarecimento numa mensagem curta ou vaga (ex: 'o que recomenda', 'e agora', 'faz isso'), primeiro tente resolvê-la usando as mensagens anteriores desta mesma conversa — o assunto quase sempre está ali. Se conseguir inferir com razoável confiança, responda direto assumindo essa interpretação, sem pedir confirmação antes. Só pergunte quando realmente não houver nenhuma pista nas mensagens recentes — e nesse caso pergunte algo específico e curto, nunca um genérico 'o quê exatamente?'. " +
        "Você tem acesso a busca na web — use-a quando a pergunta depender de algo atual (notícias, preços, eventos recentes) ou que você não tem certeza de saber; não diga que não tem acesso à internet."

/** How many recent turns get sent per request. A chat API is stateless — without a cap, every
 * single turn re-sends and re-bills the *entire* transcript so far, so cost grows without bound as
 * a conversation goes on. Older turns beyond this are simply dropped from what's sent to the model
 * (still shown in the UI, since the caller keeps the full list for display). */
private const val MAX_HISTORY_MESSAGES = 20

/** Safety cap on how many tool-call round trips a single [ClaudeClient.send] will chase before
 * giving up — guards against a model stuck calling tools back-to-back without ever answering. */
private const val MAX_TOOL_ROUNDS = 4
private const val MAX_NETWORK_TIME_MILLIS = 55_000L

/**
 * Minimal Anthropic Messages API client — same lightweight OkHttp + org.json style used
 * elsewhere in this codebase rather than the full Anthropic SDK. Supports tool use (function
 * calling): when [tools] is non-empty and the model calls one, [onToolCall] runs it and the
 * result is fed back for another round, up to [MAX_TOOL_ROUNDS], before the loop returns whatever
 * final text the model settles on. The intermediate tool_use/tool_result exchange is NOT persisted
 * into [ChatMessage] history — only the final text (with a short "✓ ..." summary per action
 * appended by the caller) is, keeping the persisted transcript's shape simple. Streams the
 * response (`"stream": true`, Server-Sent Events) so [onTextUpdate] can show text as it's
 * generated rather than only once the full reply lands — see [requestOnceStreaming].
 *
 * Lives in core:data (not feature:chat) so every feature — chat's full conversational loop, or a
 * one-shot call with `tools = emptyList()` like feature:notes' ✨ menu — can issue Claude requests
 * without a feature-on-feature module dependency.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val usageMeter: AiUsageMeter? = null,
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    suspend fun send(
        history: List<ChatMessage>,
        accessToken: String,
        systemPrompt: String? = DEFAULT_SYSTEM_PROMPT,
        maxTokens: Int = 1024,
        tools: List<ClaudeTool> = emptyList(),
        onToolCall: suspend (name: String, input: JSONObject) -> String = { _, _ -> "" },
        // Reports which tool (if any) is actively being executed right now — null while waiting on
        // Claude itself (request in flight / generating). Lets the UI show a distinct "executing X"
        // state instead of one undifferentiated "thinking" spinner for the whole round trip — see
        // AtlasActivity in feature:chat's AtlasCore.kt.
        onActivity: (toolName: String?) -> Unit = {},
        // Fired with the *full* text generated so far in the current round (replace, not append —
        // simplest contract for a caller to just assign straight to a `streamingText` state). Reset
        // to empty implicitly at the start of every round (a fresh call into requestOnceStreaming),
        // which naturally clears stale text from an earlier round that ended up calling a tool
        // instead of answering (that round's text isn't part of the final reply either — see the
        // stop_reason check below, unchanged from the non-streaming version).
        onTextUpdate: (text: String) -> Unit = {},
    ): ClaudeReply {
        require(isConfigured) { "O gateway de IA do Supabase não está configurado neste app." }
        require(accessToken.isNotBlank()) { "Entre na sua conta para usar a IA do Atlas." }
        val capped = history.takeLast(MAX_HISTORY_MESSAGES)
        val messagesJson = JSONArray()
        capped.forEachIndexed { index, message ->
            // Marks the end of the "stable" prefix (everything except the newest turn) as
            // cacheable — Anthropic's prompt caching then reuses those tokens at a fraction of the
            // normal input price on the *next* call instead of re-billing the whole transcript
            // every turn. Harmless if unsupported: the field is just ignored.
            val cacheable = capped.size > 1 && index == capped.lastIndex - 1
            // modelText (Claude's own words, without our appended "✓ ..." confirmation) is what
            // gets replayed — see ChatMessage's doc for why sending the confirmation-decorated
            // text back would be a mistake.
            val text = message.modelText ?: message.content
            messagesJson.put(
                textTurn(
                    role = message.role,
                    text = text,
                    imageBase64 = message.imageBase64,
                    pdfBase64 = message.pdfBase64,
                    cacheable = cacheable,
                ),
            )
        }

        val actions = mutableListOf<ClaudeAction>()
        val networkDeadline = System.nanoTime() + MAX_NETWORK_TIME_MILLIS * 1_000_000L
        repeat(MAX_TOOL_ROUNDS) {
            onActivity(null)
            val remainingMillis = (networkDeadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0L) throw IOException("O Atlas excedeu o tempo limite de comunicação com o serviço de IA.")
            val responseJson = withTimeoutOrNull(remainingMillis) {
                withContext(Dispatchers.IO) {
                    requestOnceStreaming(messagesJson, accessToken, systemPrompt, maxTokens, tools, onTextUpdate)
                }
            } ?: throw IOException("O Atlas excedeu o tempo limite de comunicação com o serviço de IA.")
            val contentArray = responseJson.getJSONArray("content")
            // Echo the assistant's own turn back into the running transcript exactly as received
            // (text blocks and any tool_use blocks together) — required for the next round to make
            // sense to the API, and for a later round's tool_result to line up with the right call.
            messagesJson.put(JSONObject().put("role", "assistant").put("content", contentArray))

            val textParts = StringBuilder()
            val toolUses = mutableListOf<JSONObject>()
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> textParts.append(block.getString("text"))
                    "tool_use" -> toolUses.add(block)
                }
            }

            if (responseJson.optString("stop_reason") != "tool_use" || toolUses.isEmpty()) {
                return ClaudeReply(text = textParts.toString(), actions = actions)
            }

            val resultsContent = JSONArray()
            for (toolUse in toolUses) {
                val name = toolUse.getString("name")
                val input = toolUse.optJSONObject("input") ?: JSONObject()
                onActivity(name)
                val resultText = try {
                    onToolCall(name, input)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    "Não consegui executar: ${failure.message ?: "erro inesperado"}"
                }
                actions.add(ClaudeAction(toolName = name, resultSummary = resultText))
                resultsContent.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", toolUse.getString("id"))
                        .put("content", resultText),
                )
            }
            onActivity(null)
            messagesJson.put(JSONObject().put("role", "user").put("content", resultsContent))
        }
        error("Atlas chamou ferramentas demais em sequência sem responder")
    }

    private fun textTurn(role: String, text: String, imageBase64: String?, pdfBase64: String?, cacheable: Boolean): JSONObject {
        val content = JSONArray()
        // Attachment block(s) first, then text — the order Anthropic's own docs recommend for a
        // captioned attachment turn. JPEG only for images today (see ChatViewModel's image picker,
        // which always compresses to JPEG regardless of the source format); a user turn carries at
        // most one of image/pdf today (the picker UI offers either, not both at once).
        if (imageBase64 != null) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageBase64),
                    ),
            )
        }
        if (pdfBase64 != null) {
            // Claude reads the actual document (text, layout, embedded images) from this block —
            // not a filename or a text extraction done on our side.
            content.put(
                JSONObject()
                    .put("type", "document")
                    .put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", pdfBase64),
                    ),
            )
        }
        val textBlock = JSONObject().put("type", "text").put("text", text)
        if (cacheable) textBlock.put("cache_control", JSONObject().put("type", "ephemeral"))
        content.put(textBlock)
        return JSONObject().put("role", role).put("content", content)
    }

    /** Issues one request with `"stream": true` and reassembles the Server-Sent Events into the
     * same shape [ClaudeClient.send]'s tool-use loop already expects from the old non-streaming
     * call — `{"content": [...], "stop_reason": ...}` — so nothing above this function needed to
     * change. Anthropic's stream is a sequence of `data: {...}` lines: `content_block_start` opens
     * a block (text or tool_use) at an index, `content_block_delta` appends to it (`text_delta` for
     * prose, `input_json_delta` accumulating a tool's input as partial JSON text), and
     * `message_stop` ends the message. [onTextUpdate] fires on every text delta with the whole
     * text accumulated *this round* so far (see its doc on [send]). */
    private fun requestOnceStreaming(
        messages: JSONArray,
        accessToken: String,
        systemPrompt: String?,
        maxTokens: Int,
        tools: List<ClaudeTool>,
        onTextUpdate: (text: String) -> Unit,
    ): JSONObject {
        val body = JSONObject().apply {
            put("max_tokens", maxTokens)
            put("stream", true)
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", messages)
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray(
                        tools.map { tool ->
                            if (tool.serverType != null) {
                                JSONObject().put("type", tool.serverType).put("name", tool.name).apply {
                                    tool.maxUses?.let { put("max_uses", it) }
                                }
                            } else {
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("input_schema", tool.inputSchema)
                            }
                        },
                    ),
                )
            }
        }
        val req = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/atlas-ai/anthropic")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("Claude API HTTP ${resp.code}${if (!message.isNullOrBlank()) ": $message" else ""}")
            }
            val source = resp.body?.source() ?: throw IOException("Resposta vazia da API")

            // Content blocks arrive by index; a given index is either prose (accumulated as plain
            // text) or a tool call (accumulated as a partial-JSON string, parsed once complete).
            val textByIndex = mutableMapOf<Int, StringBuilder>()
            val toolByIndex = mutableMapOf<Int, JSONObject>()
            val toolInputJsonByIndex = mutableMapOf<Int, StringBuilder>()
            // Any block type besides text/tool_use/server_tool_use — today just
            // "web_search_tool_result", which (unlike a tool_use's input) arrives fully formed in
            // content_block_start with no deltas to accumulate, so it's kept as-is and replayed
            // verbatim into the reconstructed content array below.
            val rawByIndex = mutableMapOf<Int, JSONObject>()
            val blockIndexOrder = mutableListOf<Int>()
            val runningText = StringBuilder()
            var stopReason: String? = null
            // Provider-reported token usage for this one gateway call (see AiUsageMeter).
            var inputTokens = 0L
            var cacheReadTokens = 0L
            var cacheWriteTokens = 0L
            var outputTokens = 0L

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue

                when (event.optString("type")) {
                    "content_block_start" -> {
                        val index = event.getInt("index")
                        blockIndexOrder.add(index)
                        val block = event.getJSONObject("content_block")
                        when (val blockType = block.optString("type")) {
                            "text" -> textByIndex[index] = StringBuilder(block.optString("text"))
                            // server_tool_use (e.g. Claude's own web-search query) streams its input
                            // the same way a client-dispatched tool_use does — only its type differs,
                            // which [blockType] preserves so send()'s own block-type switch (matching
                            // literally "tool_use") correctly leaves it out of client dispatch.
                            "tool_use", "server_tool_use" -> {
                                toolByIndex[index] = JSONObject()
                                    .put("type", blockType)
                                    .put("id", block.getString("id"))
                                    .put("name", block.getString("name"))
                                toolInputJsonByIndex[index] = StringBuilder()
                            }
                            "web_search_tool_result" -> rawByIndex[index] = block
                            // Anything else — concretely, a "thinking" block the model emits
                            // alongside web_search (Claude deciding whether/how to search) — is
                            // dropped instead of echoed back. content_block_start fires with it
                            // EMPTY (the real text streams in afterward via "thinking_delta", which
                            // this parser doesn't accumulate, and a valid one also needs a
                            // "signature" field this code has no way to reconstruct); echoing that
                            // empty shell back into the next round's request is what produced a real
                            // "Claude API HTTP 400: ... each thinking block must contain thinking"
                            // failure in production. Dropping an optional block is always safe —
                            // echoing one back malformed is not.
                            else -> {}
                        }
                    }
                    "content_block_delta" -> {
                        val index = event.getInt("index")
                        val delta = event.getJSONObject("delta")
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val chunk = delta.getString("text")
                                textByIndex.getOrPut(index) { StringBuilder() }.append(chunk)
                                runningText.append(chunk)
                                onTextUpdate(runningText.toString())
                            }
                            "input_json_delta" -> {
                                toolInputJsonByIndex.getOrPut(index) { StringBuilder() }.append(delta.optString("partial_json"))
                            }
                        }
                    }
                    "message_start" -> {
                        event.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
                            inputTokens = usage.optLong("input_tokens")
                            cacheReadTokens = usage.optLong("cache_read_input_tokens")
                            cacheWriteTokens = usage.optLong("cache_creation_input_tokens")
                            outputTokens = maxOf(outputTokens, usage.optLong("output_tokens"))
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("delta")?.let { if (it.has("stop_reason")) stopReason = it.optString("stop_reason") }
                        // output_tokens here is cumulative for the message.
                        event.optJSONObject("usage")?.let { outputTokens = maxOf(outputTokens, it.optLong("output_tokens")) }
                    }
                    "message_stop" -> break
                    "error" -> {
                        val message = event.optJSONObject("error")?.optString("message") ?: "Erro desconhecido"
                        throw IOException("Claude API stream error: $message")
                    }
                    // "content_block_stop" and "ping" carry nothing this needs.
                }
            }

            usageMeter?.record(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens)

            val contentArray = JSONArray()
            for (index in blockIndexOrder.distinct().sorted()) {
                textByIndex[index]?.let { contentArray.put(JSONObject().put("type", "text").put("text", it.toString())) }
                toolByIndex[index]?.let { toolBlock ->
                    val inputJson = toolInputJsonByIndex[index]?.toString().orEmpty()
                        .ifBlank { "{}" }
                        .let { raw -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject()) }
                    contentArray.put(toolBlock.put("input", inputJson))
                }
                rawByIndex[index]?.let { contentArray.put(it) }
            }
            return JSONObject().put("content", contentArray).put("stop_reason", stopReason)
        }
    }
}
