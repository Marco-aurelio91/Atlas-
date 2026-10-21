package com.unyx.atlas.chatremote

/** A tiny in-memory stand-in for the `atlas_chat_{send,fetch,ack}_envelope` RPCs — just enough
 *  behavior to drive [ChatMessagingRepository] through send/receive/ack in a JVM test, including
 *  the one server-side invariant that matters: a conflicting ciphertext for an already-stored
 *  (conversation, sender device, sequence) is rejected rather than silently overwritten (mirrors
 *  the real migration's own check). Not a general RLS/relationship simulator — unlike the real
 *  RPC, this fake doesn't derive the recipient from a pairing relationship; the test tells it via
 *  [registerConversation] instead, same simplification [FakeChatPairingRemoteClient] makes. */
class FakeChatMessageRemoteClient : ChatMessageRemoteClient {
    private data class Stored(
        val id: String,
        val conversationId: String,
        val senderUserId: String,
        val senderDeviceId: String,
        val recipientDeviceId: String,
        val sequence: Long,
        val nonceB64: String,
        val ciphertextB64: String,
        var acked: Boolean,
    )

    private val envelopes = mutableListOf<Stored>()
    private val conversationDevices = mutableMapOf<String, Pair<String, String>>()
    private var nextId = 0

    /** Test setup for one pairing — the two device ids involved, same role the pinned
     *  `device_low_id`/`device_high_id` play in the real `atlas_chat_contact_relationships` row. */
    fun registerConversation(conversationId: String, deviceA: String, deviceB: String) {
        conversationDevices[conversationId] = deviceA to deviceB
    }

    /** Lets a test simulate a dropped network call without the repository ever knowing the send
     *  "succeeded" at the transport layer — the next call to this conversation+device throws. */
    var failNextSendFor: Pair<String, String>? = null

    override suspend fun sendEnvelope(conversationId: String, sequence: Long, nonceB64: String, ciphertextB64: String, accessToken: String) {
        val senderDeviceId = accessToken
        if (failNextSendFor == conversationId to senderDeviceId) {
            failNextSendFor = null
            throw IllegalStateException("simulated network failure")
        }
        val (deviceA, deviceB) = conversationDevices[conversationId] ?: error("conversation not registered in fake")
        val recipientDeviceId = when (senderDeviceId) {
            deviceA -> deviceB
            deviceB -> deviceA
            else -> error("sender device is not part of this conversation")
        }
        val existing = envelopes.find { it.conversationId == conversationId && it.senderDeviceId == senderDeviceId && it.sequence == sequence }
        if (existing != null) {
            check(existing.ciphertextB64 == ciphertextB64) { "conflicting envelope already exists for this sequence" }
            return
        }
        envelopes += Stored(
            id = "env-${nextId++}", conversationId = conversationId, senderUserId = accessToken,
            senderDeviceId = senderDeviceId, recipientDeviceId = recipientDeviceId, sequence = sequence,
            nonceB64 = nonceB64, ciphertextB64 = ciphertextB64, acked = false,
        )
    }

    override suspend fun fetchPendingEnvelopes(accessToken: String): List<RemoteEnvelope> {
        val myDeviceId = accessToken
        return envelopes.filter { it.recipientDeviceId == myDeviceId && !it.acked }
            .sortedBy { it.sequence }
            .map { RemoteEnvelope(it.id, it.conversationId, it.senderUserId, it.senderDeviceId, it.sequence, it.nonceB64, it.ciphertextB64) }
    }

    override suspend fun ackEnvelope(envelopeId: String, accessToken: String) {
        envelopes.find { it.id == envelopeId }?.let { it.acked = true }
    }
}
