package com.unyx.atlas.memory

import com.unyx.atlas.memory.auth.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Why the user flagged an AI response — stored as [code] (matches the table's CHECK constraint). */
enum class AiReportReason(val code: String) {
    HARMFUL("harmful"),
    FALSE_INFO("false_info"),
    PRIVACY("privacy"),
    OTHER("other"),
}

/**
 * Files a user report about an AI response into `atlas_ai_reports` (Google Play's AI-generated
 * content policy requires an in-app way to flag offensive output). Only the response text the user
 * chose to report is sent, truncated to the column's limit; nothing is sent unless the user taps
 * "Enviar" in the dialog.
 */
class AiResponseReporter(
    private val rest: SupabaseRestClient,
    private val auth: AuthRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun report(reason: AiReportReason, responseText: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val token = auth.currentAccessToken() ?: error("Sem sessão ativa.")
            val userId = auth.currentUserId() ?: error("Sem sessão ativa.")
            val excerpt = responseText.trim().take(MAX_EXCERPT_CHARS)
            require(excerpt.isNotEmpty()) { "Nada para denunciar." }
            val row = JSONObject()
                .put("user_id", userId)
                .put("reason", reason.code)
                .put("response_excerpt", excerpt)
            rest.insert("atlas_ai_reports", row, token)
            Unit
        }
    }

    companion object {
        /** Mirrors the table's `char_length(response_excerpt) between 1 and 4000` check. */
        const val MAX_EXCERPT_CHARS = 4000
    }
}
