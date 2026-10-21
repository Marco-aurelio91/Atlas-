package com.unyx.atlas.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal Gemini embeddings client — same lightweight OkHttp + org.json style as
 * [SupabaseRestClient] and core:data's ClaudeClient, rather than a full SDK for one endpoint.
 * Separate provider/vendor from chat itself (Anthropic): Anthropic has no embeddings endpoint of
 * its own. Requests `outputDimensionality = 1536` specifically so gemini-embedding-001's output
 * (natively larger, via Matryoshka representation learning) drops straight into
 * `memory_embeddings.embedding` (a `vector(1536)`) with no schema change — this was OpenAI's
 * text-embedding-3-small at first, switched here when that account ran out of credit.
 */
class EmbeddingClient(
    private val http: OkHttpClient,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /**
     * Embeds [text], truncated defensively to stay under the model's 2048-token input limit — a
     * rough character cap (no tokenizer dependency here), generous enough that legitimate memory
     * content (a fact, an episode summary, a note) never gets near it in practice.
     */
    suspend fun embed(text: String, accessToken: String): EmbeddingResult = withContext(Dispatchers.IO) {
        require(isConfigured) { "O gateway de IA do Supabase não está configurado neste app." }
        require(accessToken.isNotBlank()) { "Entre na sua conta para usar a busca semântica do Atlas." }
        val input = text.take(MAX_INPUT_CHARS)
        val body = JSONObject()
            .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", input))))
            .put("outputDimensionality", 1536)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/atlas-ai/gemini-embedding")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = runCatching { resp.body?.string() }.getOrNull()?.take(300).orEmpty()
                throw IOException("Gemini embeddings HTTP ${resp.code}${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val model = resp.header("X-Atlas-Embedding-Model")
                ?: throw IOException("O gateway não informou o modelo de embeddings usado")
            val json = JSONObject(resp.body?.string().orEmpty())
            val vector = json.getJSONObject("embedding").getJSONArray("values")
            EmbeddingResult(
                model = model,
                vector = List(vector.length()) { vector.getDouble(it).toFloat() },
            )
        }
    }

    private companion object {
        const val MAX_INPUT_CHARS = 6_000
    }
}

data class EmbeddingResult(val model: String, val vector: List<Float>)

/**
 * Postgres's `vector` type parses its own bracketed-comma-separated text format (`[0.1,0.2,...]`)
 * — sending it as a JSON *string* in that shape (rather than a JSON array) is what lets PostgREST
 * hand it to Postgres as text and let `vector_in` do the parsing, sidestepping any ambiguity about
 * whether a bare JSON array gets implicitly cast to `vector` on the way through.
 */
fun List<Float>.toVectorLiteral(): String = joinToString(prefix = "[", postfix = "]", separator = ",")
