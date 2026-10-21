package com.unyx.atlas.data.ai

import com.unyx.atlas.data.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Emergency fallback for [AtlasChatClient] — used only when Claude itself fails outright (out of
 * API credit, an outage). It streams text and supports tool-calling (see
 * [MAX_TOOL_ROUNDS]): callers pass the exact same [ClaudeTool] list and `onToolCall` dispatcher they
 * give [ClaudeClient], so whatever executes a tool call (feature:chat's AtlasToolExecutor, today)
 * never needs to know which backend is calling it. A live device test found the earlier text-only
 * version correctly refused to claim it had acted (the old "IMPORTANTE: você não tem ferramentas"
 * system-prompt addendum did its job), but that meant a save_fact/create_task/etc. request landing
 * while Claude was down just failed softly instead of actually happening. Gemini's function-calling
 * API shape differs enough
 * from Anthropic's (role "function" with a `functionResponse` part instead of a `tool_result`
 * content block; `functionDeclarations`' parameter schema needs uppercase `type` values — Anthropic
 * and Gemini both use an OpenAPI/JSON-Schema-shaped `object/properties/required`, Gemini's own Schema
 * type just needs uppercase enum values — see [toGeminiSchema]) that this needed its own loop rather
 * than reusing [ClaudeClient]'s. A different Google model from core:memory's EmbeddingClient (that
 * one generates vectors; this one generates text); provider credentials remain in the server-side
 * Supabase Edge Function.
 */
class GeminiChatClient(
    private val http: OkHttpClient,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    private companion object {
        const val ATTEMPTS = 3
        const val MAX_NETWORK_TIME_MILLIS = 45_000L

        // Same safety purpose as ClaudeClient's own MAX_TOOL_ROUNDS (a separate constant — that
        // one's file-private, and the two loops are independent anyway).
        const val MAX_TOOL_ROUNDS = 4
    }

    private class GeminiTransientException(message: String) : IOException(message)

    /** [tools]/[onToolCall]/[onActivity] mirror [ClaudeClient.send]'s own — same [ClaudeTool] list,
     * same dispatcher, so [AtlasToolExecutor] never needs to know which backend is calling it.
     * Claude-native server tools ([ClaudeTool.serverType] non-null, e.g. web search) have no Gemini
     * equivalent through this path and are silently skipped when building the function-declarations
     * list — asking Gemini to call one would just produce an `onToolCall` lookup miss. */
    suspend fun send(
        history: List<ChatMessage>,
        accessToken: String,
        systemPrompt: String?,
        maxTokens: Int = 1024,
        tools: List<ClaudeTool> = emptyList(),
        onToolCall: suspend (name: String, input: JSONObject) -> String = { _, _ -> "" },
        onActivity: (toolName: String?) -> Unit = {},
        onTextUpdate: (text: String) -> Unit = {},
    ): ClaudeReply {
        require(isConfigured) { "O gateway de IA do Supabase não está configurado neste app." }
        require(accessToken.isNotBlank()) { "Entre na sua conta para usar a IA do Atlas." }
        // Gemini requires contents to start with a "user" turn and strictly alternate — a history
        // slice that (after Claude's own MAX_HISTORY_MESSAGES cap) happens to start mid-conversation
        // on an assistant turn would otherwise make this call fail outright, defeating the one job
        // this fallback has.
        val trimmed = history.dropWhile { it.role != "user" }
        val contents = JSONArray()
        trimmed.forEach { message ->
            val role = if (message.role == "assistant") "model" else "user"
            val text = message.modelText ?: message.content
            contents.put(
                JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
        val functionDeclarations = buildFunctionDeclarations(tools)

        val actions = mutableListOf<ClaudeAction>()
        val networkDeadline = System.nanoTime() + MAX_NETWORK_TIME_MILLIS * 1_000_000L
        repeat(MAX_TOOL_ROUNDS) {
            onActivity(null)
            onTextUpdate("")
            val remainingMillis = (networkDeadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0L) throw IOException("O modelo reserva excedeu o tempo limite de comunicação.")
            val parts = withTimeoutOrNull(remainingMillis) {
                requestOnceWithRetry(contents, accessToken, systemPrompt, maxTokens, functionDeclarations, onTextUpdate)
            } ?: throw IOException("O modelo reserva excedeu o tempo limite de comunicação.")
            // Echo the model's own turn back into the running transcript exactly as received —
            // required for the next round (and for a later functionResponse to line up with the
            // right call), same reasoning as ClaudeClient's equivalent echo.
            contents.put(JSONObject().put("role", "model").put("parts", parts))

            val textParts = StringBuilder()
            val calls = mutableListOf<JSONObject>()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("text")) textParts.append(part.getString("text"))
                part.optJSONObject("functionCall")?.let { calls.add(it) }
            }

            if (calls.isEmpty()) {
                return ClaudeReply(text = textParts.toString(), actions = actions)
            }

            val responseParts = JSONArray()
            for (call in calls) {
                val name = call.getString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                onActivity(name)
                val resultText = try {
                    onToolCall(name, args)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    "Não consegui executar: ${failure.message ?: "erro inesperado"}"
                }
                actions.add(ClaudeAction(toolName = name, resultSummary = resultText))
                responseParts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", name).put("response", JSONObject().put("result", resultText)),
                    ),
                )
            }
            onActivity(null)
            // NOT role "function" — confirmed live: Gemini's generateContent rejects it outright
            // ("Role 'function' is not supported... USER, ... MODEL, USER"). A functionResponse part
            // rides in a "user" turn, the same way Anthropic's tool_result rides in a "user" turn —
            // the two APIs agree on this despite otherwise-different tool-calling shapes.
            contents.put(JSONObject().put("role", "user").put("parts", responseParts))
        }
        error("Atlas (modelo reserva) chamou ferramentas demais em sequência sem responder")
    }

    private fun buildFunctionDeclarations(tools: List<ClaudeTool>): JSONArray {
        val declarations = JSONArray()
        tools.forEach { tool ->
            if (tool.serverType != null) return@forEach
            declarations.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", tool.inputSchema.toGeminiSchema()),
            )
        }
        return declarations
    }

    /** Anthropic's `input_schema` and Gemini's function-parameter `Schema` are both
     * object/properties/required JSON Schema shapes, with one real difference: Gemini's `type` is an
     * uppercase enum ("OBJECT", "STRING", "INTEGER"...) where Anthropic (and plain JSON Schema) use
     * lowercase. Recurses into `properties` (each value is itself a schema) and `items` (array
     * element schema); every other key (`description`, `required`, ...) is copied through as-is. */
    private fun JSONObject.toGeminiSchema(): JSONObject {
        val out = JSONObject()
        keys().forEach { key ->
            when (key) {
                "type" -> out.put("type", getString("type").uppercase())
                "properties" -> {
                    val props = getJSONObject("properties")
                    val converted = JSONObject()
                    props.keys().forEach { propKey -> converted.put(propKey, props.getJSONObject(propKey).toGeminiSchema()) }
                    out.put("properties", converted)
                }
                "items" -> out.put("items", getJSONObject("items").toGeminiSchema())
                else -> out.put(key, get(key))
            }
        }
        return out
    }

    /** Retries on transient server-side flakiness — confirmed live, repeatedly, two DIFFERENT
     * failure modes from this exact model in the same session: "This model is currently
     * experiencing high demand" (503), and a plain HTTP 200 with a blank/missing response body (no
     * error at all, just nothing to say) right after a real user's simple, unambiguous message.
     * Both are the server being unreliable, not this request being wrong, so both get retried — see
     * [GeminiTransientException]. A real 4xx (bad key, malformed request) is NOT retried: retrying
     * won't fix a request that's actually broken. This fallback is only ever reached when Claude
     * ALSO just failed, so letting transient flakiness compound into a dead end is worse than a few
     * extra seconds of latency — each retry waits longer than the last. */
    private suspend fun requestOnceWithRetry(
        contents: JSONArray,
        accessToken: String,
        systemPrompt: String?,
        maxTokens: Int,
        functionDeclarations: JSONArray,
        onTextUpdate: (text: String) -> Unit,
    ): JSONArray {
        repeat(ATTEMPTS) { attempt ->
            val result = runCatching {
                requestOnce(contents, accessToken, systemPrompt, maxTokens, functionDeclarations, onTextUpdate)
            }
            result.onSuccess { return it }
            val error = result.exceptionOrNull()
            if (attempt == ATTEMPTS - 1 || error !is GeminiTransientException) throw error!!
            delay(1_500L * (attempt + 1))
        }
        error("unreachable") // repeat() above always returns or throws before exhausting its range
    }

    private suspend fun requestOnce(
        contents: JSONArray,
        accessToken: String,
        systemPrompt: String?,
        maxTokens: Int,
        functionDeclarations: JSONArray,
        onTextUpdate: (text: String) -> Unit,
    ): JSONArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("contents", contents).put("stream", true).apply {
            if (!systemPrompt.isNullOrBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
                )
            }
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", maxTokens)
                    // Gemini 3.8 uses thinkingLevel. LOW keeps simple chat turns responsive while
                    // retaining some reasoning for selecting device tools.
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW")),
            )
            if (functionDeclarations.length() > 0) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)))
            }
        }
        val req = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/atlas-ai/gemini-chat")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // A raw IOException here (connect/read timeout, DNS failure — no HTTP response at all) is
        // just as transient as a 5xx: the server never even got a chance to say it was overloaded.
        // Confirmed live: the tool-calling loop's extra round trips made this path far more likely
        // to hit than the old single-call version ever did, and an unwrapped SocketTimeoutException
        // skipped retries entirely (not a GeminiTransientException) — one flaky connection killed
        // the whole multi-round exchange instead of getting the same retry a 503 gets.
        val response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw GeminiTransientException(e.message ?: "Falha de rede ao chamar o Gemini")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                val fullMessage = "Gemini API HTTP ${resp.code}${if (!message.isNullOrBlank()) ": $message" else ""}"
                // 5xx is the server's own fault (overloaded, internal error) — retryable. 4xx (bad
                // key, malformed request) is this request's fault — retrying changes nothing.
                if (resp.code >= 500) throw GeminiTransientException(fullMessage) else throw IOException(fullMessage)
            }
            val source = resp.body?.source() ?: throw GeminiTransientException("Resposta vazia do Gemini")
            val partsByIndex = sortedMapOf<Int, JSONObject>()
            val visibleText = StringBuilder()
            var sawCandidate = false
            var finishReason = "desconhecido"
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                sawCandidate = true
                candidate.optString("finishReason").takeIf { it.isNotBlank() }?.let { finishReason = it }
                val chunkParts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (index in 0 until chunkParts.length()) {
                    val part = chunkParts.optJSONObject(index) ?: continue
                    val accumulated = partsByIndex.getOrPut(index) { JSONObject() }
                    val keys = part.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (key) {
                            "text" -> {
                                val text = part.optString("text")
                                accumulated.put("text", accumulated.optString("text") + text)
                                if (!part.optBoolean("thought") && text.isNotEmpty()) {
                                    visibleText.append(text)
                                    onTextUpdate(visibleText.toString())
                                }
                            }
                            // GenerateContent emits function calls as complete parts. Preserve their
                            // metadata and thought signatures for the next tool-call round.
                            else -> accumulated.put(key, part.get(key))
                        }
                    }
                }
            }
            if (!sawCandidate || partsByIndex.isEmpty()) {
                throw GeminiTransientException("Resposta vazia do Gemini (finishReason: $finishReason)")
            }
            val parts = JSONArray()
            partsByIndex.values.forEach { parts.put(it) }
            val hasContent = partsByIndex.values.any { part ->
                part.has("functionCall") || part.optString("text").isNotBlank()
            }
            if (!hasContent) throw GeminiTransientException("Resposta vazia do Gemini")
            parts
        }
    }
}
