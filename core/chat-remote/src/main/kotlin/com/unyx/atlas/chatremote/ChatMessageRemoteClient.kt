package com.unyx.atlas.chatremote

import com.unyx.atlas.memory.SupabaseRestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One envelope as the server returned it from `atlas_chat_fetch_pending_envelopes` — the exact
 *  fields needed to rebuild a [com.unyx.atlas.chatcrypto.ChatEnvelope] and decrypt it locally. */
data class RemoteEnvelope(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val sequence: Long,
    val nonceB64: String,
    val ciphertextB64: String,
)

/** The `atlas_chat_{send,fetch,ack}_envelope` RPC surface from
 * `supabase/migrations/20261017020000_atlas_chat_sync.sql` — see
 * `docs/ATLAS_PEOPLE_UI_STATUS.md` for what this does and does not cover (transport-level
 * send/fetch/ack only; no E2EE delivery receipt yet). As with [ChatPairingRemoteClient],
 * [accessToken] is always the caller's own — every RPC derives the acting identity from
 * `auth.uid()` server-side. */
interface ChatMessageRemoteClient {
    suspend fun sendEnvelope(conversationId: String, sequence: Long, nonceB64: String, ciphertextB64: String, accessToken: String)
    suspend fun fetchPendingEnvelopes(accessToken: String): List<RemoteEnvelope>
    suspend fun ackEnvelope(envelopeId: String, accessToken: String)
}

class SupabaseChatMessageRemoteClient(
    private val supabase: SupabaseRestClient,
) : ChatMessageRemoteClient {

    override suspend fun sendEnvelope(
        conversationId: String,
        sequence: Long,
        nonceB64: String,
        ciphertextB64: String,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        supabase.rpc(
            "atlas_chat_send_envelope",
            JSONObject()
                .put("p_conversation_id", conversationId)
                .put("p_sequence", sequence)
                .put("p_nonce_b64", nonceB64)
                .put("p_ciphertext_b64", ciphertextB64),
            accessToken,
        )
        Unit
    }

    override suspend fun fetchPendingEnvelopes(accessToken: String): List<RemoteEnvelope> =
        withContext(Dispatchers.IO) {
            val rows = supabase.rpc("atlas_chat_fetch_pending_envelopes", JSONObject(), accessToken)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                RemoteEnvelope(
                    id = row.getString("id"),
                    conversationId = row.getString("conversation_id"),
                    senderUserId = row.getString("sender_user_id"),
                    senderDeviceId = row.getString("sender_device_id"),
                    sequence = row.getLong("sequence"),
                    nonceB64 = row.getString("nonce_b64"),
                    ciphertextB64 = row.getString("ciphertext_b64"),
                )
            }
        }

    override suspend fun ackEnvelope(envelopeId: String, accessToken: String) = withContext(Dispatchers.IO) {
        supabase.rpc("atlas_chat_ack_envelope", JSONObject().put("p_envelope_id", envelopeId), accessToken)
        Unit
    }
}
