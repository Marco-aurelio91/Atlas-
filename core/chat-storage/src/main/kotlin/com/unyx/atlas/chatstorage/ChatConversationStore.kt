package com.unyx.atlas.chatstorage

import android.content.SharedPreferences
import com.unyx.atlas.chatcrypto.ChatEnvelope
import com.unyx.atlas.chatcrypto.ChatSessionSnapshot
import org.json.JSONObject

/**
 * Durable, crash-safe storage for one conversation's ratchet state and its pending outbound
 * envelopes — the `chat:storage` layer from
 * `docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` §5.1/§10, scoped to what's needed to
 * actually exercise T-10/T-11 (see `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §8) instead of just
 * claiming they're covered. Deliberately *not* the full `LocalMessage`/outbox state machine
 * (§9.2) — that's `chat:domain`'s job once conversations/contacts exist; this only has to answer
 * "what does the ratchet look like, and what haven't I sent/acked yet."
 *
 * [preferences] must be Keystore-encrypted at rest (the same pattern `core/security`'s
 * `AtlasAccountKeyManager` already uses for the account root key) — this class has no opinion on
 * *how* that's arranged, only that chain keys are exactly as sensitive as that root key and must
 * never land in plain `SharedPreferences`. Production wiring opens one via
 * `AtlasSecurePreferences.open(context, "atlas_chat_sessions_secure").preferences`; tests inject
 * an in-memory fake (see `FakeSharedPreferences` in this module's own test sources) instead of
 * pulling in Robolectric just to get a real encrypted store.
 */
class ChatConversationStore(private val preferences: SharedPreferences) {

    /** Persists a session that has no envelope to go with it yet — right after
     *  [com.unyx.atlas.chatcrypto.ChatSession.establish], before the first message is sent or
     *  received. Neither [persistAfterEncrypt] nor [persistAfterReceive] apply at that point:
     *  there is no envelope, and nothing has been "received" either. */
    fun persistSession(snapshot: ChatSessionSnapshot) {
        val committed = preferences.edit().putString(sessionKey(snapshot.conversationId), snapshot.toJson().toString()).commit()
        check(committed) { "Could not persist chat session state for ${snapshot.conversationId}" }
    }

    fun loadSession(conversationId: String): ChatSessionSnapshot? =
        preferences.getString(sessionKey(conversationId), null)
            ?.let { runCatching { JSONObject(it).toSessionSnapshot() }.getOrNull() }

    /** The one write that matters for T-10 ("crash após criptografar e antes de enviar"): the
     *  ratchet's new state (after [ChatSession.encrypt] advanced it) and the envelope that
     *  advance produced land in the *same* [SharedPreferences.Editor.commit] call — a single
     *  synchronous, atomic file write. A process death at any point before this call returns
     *  leaves the *previous* consistent state untouched (nothing partially advanced); a process
     *  death any point after it returns has both pieces durable, so the pending envelope can be
     *  found in [pendingOutbox] and resent after restart without re-deriving a new key for the
     *  same logical message. */
    fun persistAfterEncrypt(snapshot: ChatSessionSnapshot, envelope: ChatEnvelope) {
        val committed = preferences.edit()
            .putString(sessionKey(snapshot.conversationId), snapshot.toJson().toString())
            .putString(outboxKey(envelope.conversationId, envelope.sequence), envelope.toJson().toString())
            .commit()
        check(committed) { "Could not persist chat session + outbox entry for ${snapshot.conversationId}" }
    }

    /** Called once the server has durably accepted the envelope — see the plan's §12.1 step 8.
     *  Not part of the atomic encrypt-time write on purpose: losing this specific update to a
     *  crash just means the (harmless, already-idempotent-by-sequence) envelope gets resent and
     *  the server's own idempotency handles the rest — unlike losing the encrypt-time write, this
     *  can't desync the ratchet or drop a message. */
    fun markSent(conversationId: String, sequence: Long) {
        preferences.edit().remove(outboxKey(conversationId, sequence)).apply()
    }

    /** Envelopes for [conversationId] that were persisted by [persistAfterEncrypt] but never
     *  confirmed via [markSent] — what a restart needs to resend. Ordered by sequence so resends
     *  go out in the order they were originally composed. */
    fun pendingOutbox(conversationId: String): List<ChatEnvelope> {
        val prefix = outboxKeyPrefix(conversationId)
        return preferences.all.entries
            .filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (_, value) -> (value as? String)?.let { runCatching { JSONObject(it).toEnvelope() }.getOrNull() } }
            .sortedBy { it.sequence }
    }

    /** The write that matters for T-11 ("crash após receber e antes de ACK"): persisting the
     *  receiving-side ratchet state — which already reflects this message as processed, via
     *  [ChatSessionSnapshot.highestProcessedSeq] — right after a successful decrypt and before
     *  attempting to send the E2EE receipt. If the process dies before the receipt goes out, the
     *  restarted session still recognizes a redelivery of the same envelope as
     *  [com.unyx.atlas.chatcrypto.DecryptResult.Duplicate] instead of showing it twice. */
    fun persistAfterReceive(snapshot: ChatSessionSnapshot) {
        val committed = preferences.edit()
            .putString(sessionKey(snapshot.conversationId), snapshot.toJson().toString())
            .commit()
        check(committed) { "Could not persist chat session state for ${snapshot.conversationId}" }
    }

    /** Every conversation with a locally stored session — what a real "Conexões"/conversation
     *  list has to start from, since nothing else in this module tracks "which conversations
     *  exist" (sessions are only ever looked up by an already-known [conversationId] otherwise). */
    fun listConversationIds(): List<String> {
        val prefix = "session:"
        return preferences.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /** Marks a SENT sequence as confirmed by the other side's E2EE delivery receipt (plan
     *  §12.3). This is the only honest source for a "Entregue" state: [pendingOutbox] absent
     *  (i.e. [markSent] already called) only proves the server accepted the envelope, not that
     *  the recipient ever decrypted it. Same existence-is-the-fact key pattern as the outbox. */
    fun markDelivered(conversationId: String, sequence: Long) {
        val committed = preferences.edit().putString(deliveredKey(conversationId, sequence), "1").commit()
        check(committed) { "Could not persist delivery receipt for $conversationId/$sequence" }
    }

    fun deliveredSentSequences(conversationId: String): Set<Long> {
        val prefix = deliveredKeyPrefix(conversationId)
        return preferences.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
            .toSet()
    }

    fun deleteConversation(conversationId: String) {
        val editor = preferences.edit().remove(sessionKey(conversationId))
        preferences.all.keys
            .filter { it.startsWith(outboxKeyPrefix(conversationId)) || it.startsWith(deliveredKeyPrefix(conversationId)) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun sessionKey(conversationId: String) = "session:$conversationId"
    private fun outboxKeyPrefix(conversationId: String) = "outbox:$conversationId:"
    private fun outboxKey(conversationId: String, sequence: Long) = "${outboxKeyPrefix(conversationId)}$sequence"
    private fun deliveredKeyPrefix(conversationId: String) = "delivered:$conversationId:"
    private fun deliveredKey(conversationId: String, sequence: Long) = "${deliveredKeyPrefix(conversationId)}$sequence"
}
