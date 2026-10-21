package com.unyx.atlas.chatstorage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ChatMessageDirection { SENT, RECEIVED }

/** One already-decrypted message, kept for local display — never re-derivable from
 *  [ChatConversationStore] alone, which only tracks ratchet state and not-yet-acked outbound
 *  envelopes, not a readable transcript. [sequence] is scoped to [direction]: the sending and
 *  receiving chains each have their own independent counter (see
 *  `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §5), so `(direction, sequence)` — not `sequence`
 *  alone — is what uniquely identifies a message within a conversation. A sent message's current
 *  delivery state is deliberately NOT stored here — it's derived by checking whether
 *  [sequence] is still in [ChatConversationStore.pendingOutbox] (still queued) or not (server
 *  accepted it), so the two facts can never drift out of sync with each other. */
data class ChatMessageRecord(
    val sequence: Long,
    val direction: ChatMessageDirection,
    val text: String,
    val timestampEpochSeconds: Long,
    /** Plan §11 "Responder a uma mensagem" — the `"${direction}-${sequence}"` id (same format
     *  [direction]/[sequence] already form) of the message this one replies to, IN THIS DEVICE'S
     *  OWN addressing scheme. Null for a message that isn't a reply. The far side's addressing is
     *  flipped (its SENT is this device's RECEIVED and vice versa) before a received reply
     *  reference ever reaches this store — see `ChatMessagingRepository`'s own doc on why, so by
     *  the time it lands here it's already a valid local lookup key, same as this device's own
     *  outgoing replies always were. */
    val replyToMessageId: String? = null,
)

/**
 * Local, readable transcript per conversation — what a real conversation screen renders from.
 * Plaintext at rest by necessity (it has to be, to display without re-decrypting on every open),
 * so [preferences] carries the exact same Keystore-encrypted requirement as
 * [ChatConversationStore]'s own (see that class's doc) — this is as sensitive as the messages
 * themselves, not merely as sensitive as routing metadata.
 *
 * The whole history for a conversation is stored as one JSON array under one key, read-modify-
 * write on every [append] — the same trade-off `MemoryNote.checklist` already makes elsewhere in
 * this codebase ("small and always read/written whole, so there's no query that needs it
 * normalized"). Fine for a prototype; a long-running conversation would eventually want
 * pagination or per-message keys instead of rewriting the whole array on every message.
 */
class ChatMessageHistoryStore(private val preferences: SharedPreferences) {

    /** Idempotent by `(direction, sequence)` — appending the same message twice (e.g. a retry
     *  calling this again after [ChatConversationStore.persistAfterReceive] but before the
     *  caller finished its own bookkeeping) is a no-op, not a duplicate row. */
    fun append(conversationId: String, record: ChatMessageRecord) {
        val existing = loadAll(conversationId)
        if (existing.any { it.direction == record.direction && it.sequence == record.sequence }) return
        val updated = (existing + record).sortedBy { it.timestampEpochSeconds }
        writeAll(conversationId, updated)
    }

    fun loadAll(conversationId: String): List<ChatMessageRecord> {
        val raw = preferences.getString(historyKey(conversationId), null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toRecord() }.getOrNull()
        }
    }

    /** The most recent message, if any — what a conversation list row's preview needs, without
     *  pulling the whole transcript just to show one line. */
    fun lastMessage(conversationId: String): ChatMessageRecord? = loadAll(conversationId).maxByOrNull { it.timestampEpochSeconds }

    /** Plan §12 "Excluir do meu histórico" — removes just this device's local copy of one
     *  message. No separate tombstone row: a genuine network redelivery of the same envelope is
     *  already deduped before it ever reaches this store (the server stops returning an envelope
     *  once [com.unyx.atlas.chatremote.ChatMessageRemoteClient.ackEnvelope] confirms it, and the
     *  ratchet itself rejects a replay as [com.unyx.atlas.chatcrypto.DecryptResult.Duplicate]) —
     *  there is no path in this codebase by which deleting this record could let the same message
     *  silently reappear, so a minimal-retention tombstone would guard against a redelivery that
     *  can't actually happen here. A no-op if the message is already gone. */
    fun deleteOne(conversationId: String, direction: ChatMessageDirection, sequence: Long) {
        val remaining = loadAll(conversationId).filterNot { it.direction == direction && it.sequence == sequence }
        writeAll(conversationId, remaining)
    }

    fun deleteAll(conversationId: String) {
        preferences.edit().remove(historyKey(conversationId)).apply()
    }

    private fun writeAll(conversationId: String, records: List<ChatMessageRecord>) {
        val json = JSONArray(records.map { it.toJson() })
        val committed = preferences.edit().putString(historyKey(conversationId), json.toString()).commit()
        check(committed) { "Could not persist chat history for $conversationId" }
    }

    private fun historyKey(conversationId: String) = "history:$conversationId"

    private fun ChatMessageRecord.toJson(): JSONObject {
        val json = JSONObject()
            .put("sequence", sequence)
            .put("direction", direction.name)
            .put("text", text)
            .put("timestamp", timestampEpochSeconds)
        replyToMessageId?.let { json.put("reply_to", it) }
        return json
    }

    private fun JSONObject.toRecord(): ChatMessageRecord = ChatMessageRecord(
        sequence = getLong("sequence"),
        direction = ChatMessageDirection.valueOf(getString("direction")),
        text = getString("text"),
        timestampEpochSeconds = getLong("timestamp"),
        replyToMessageId = if (has("reply_to")) getString("reply_to") else null,
    )
}
