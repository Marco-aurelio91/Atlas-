package com.unyx.atlas.memory.athena

import com.unyx.atlas.memory.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * ATLAS's own gateway to ATHENA (docs/ATHENA_PLANO_CRIACAO_BACKEND_SUPABASE.md §49-51). ATLAS
 * knows this interface; it never knows Open-Meteo, INMET, or any other provider directly — that
 * stays behind `athena-query` (§79, "contrato estável"). Only `query()` is implemented so far
 * (the M1 mock, §60); subscribe/unsubscribe land with Fase 11+ (Queue/Worker/subscriptions).
 */
data class WorldQuery(
    val intent: String,
    val query: String? = null,
    val location: Pair<Double, Double>? = null, // lat, lon
    val fields: List<String> = emptyList(),
    val requestId: String = UUID.randomUUID().toString(),
)

data class WorldAnswer(
    val requestId: String,
    val status: String,
    val confidenceLevel: String,
    val freshness: String,
    val generatedAt: String,
    /** The raw JSON body, for the debug screen to show verbatim — no need to model every field of
     * facts/events/entities/evidence on the Android side until something actually consumes them. */
    val raw: String,
)

interface AthenaGateway {
    suspend fun query(query: WorldQuery): WorldAnswer
}

/**
 * Calls the `athena-query` Edge Function over HTTPS, authenticated with the same Supabase session
 * [AuthRepository] already manages (§33 — identity is for access control/quota only; ATHENA never
 * receives it as a field, and this class never attaches userId/email to the request body).
 */
class AthenaGatewayImpl(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val auth: AuthRepository,
) : AthenaGateway {
    override suspend fun query(query: WorldQuery): WorldAnswer = withContext(Dispatchers.IO) {
        val token = auth.currentAccessToken() ?: apiKey // falls back to the anon key if logged out
        val body = JSONObject().apply {
            put("requestId", query.requestId)
            put("intent", query.intent)
            query.query?.let { put("query", it) }
            query.location?.let { (lat, lon) -> put("location", JSONObject().put("lat", lat).put("lon", lon)) }
            if (query.fields.isNotEmpty()) put("fields", query.fields)
        }
        val req = Request.Builder()
            .url("$baseUrl/functions/v1/athena-query")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("ATHENA HTTP ${resp.code}: $text")
            val json = JSONObject(text)
            WorldAnswer(
                requestId = json.optString("requestId", query.requestId),
                status = json.optString("status", "UNKNOWN"),
                confidenceLevel = json.optJSONObject("confidence")?.optString("level") ?: "UNKNOWN",
                freshness = json.optString("freshness", "UNKNOWN"),
                generatedAt = json.optString("generatedAt"),
                raw = json.toString(2),
            )
        }
    }
}
