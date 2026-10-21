package com.unyx.atlas.chatstorage

import com.unyx.atlas.chatcrypto.ChatEnvelope
import com.unyx.atlas.chatcrypto.ChatSessionSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal fun ChatSessionSnapshot.toJson(): JSONObject = JSONObject()
    .put("conversation_id", conversationId)
    .put("local_device_id", localDeviceId)
    .put("remote_device_id", remoteDeviceId)
    .put("sending_chain_key_b64", encode(sendingChainKey))
    .put("sending_next_seq", sendingNextSeq)
    .put("receiving_chain_key_b64", encode(receivingChainKey))
    .put("receiving_next_seq", receivingNextSeq)
    .put("highest_processed_seq", highestProcessedSeq)
    .put(
        "skipped_keys",
        JSONArray().apply {
            skippedKeys.forEach { (seq, key) -> put(JSONObject().put("seq", seq).put("key_b64", encode(key))) }
        },
    )

internal fun JSONObject.toSessionSnapshot(): ChatSessionSnapshot {
    val skippedArray = getJSONArray("skipped_keys")
    val skipped = buildMap {
        for (i in 0 until skippedArray.length()) {
            val entry = skippedArray.getJSONObject(i)
            put(entry.getLong("seq"), decode(entry.getString("key_b64")))
        }
    }
    return ChatSessionSnapshot(
        conversationId = getString("conversation_id"),
        localDeviceId = getString("local_device_id"),
        remoteDeviceId = getString("remote_device_id"),
        sendingChainKey = decode(getString("sending_chain_key_b64")),
        sendingNextSeq = getLong("sending_next_seq"),
        receivingChainKey = decode(getString("receiving_chain_key_b64")),
        receivingNextSeq = getLong("receiving_next_seq"),
        highestProcessedSeq = getLong("highest_processed_seq"),
        skippedKeys = skipped,
    )
}

internal fun ChatEnvelope.toJson(): JSONObject = JSONObject()
    .put("version", version)
    .put("conversation_id", conversationId)
    .put("sender_device_id", senderDeviceId)
    .put("sequence", sequence)
    .put("nonce_b64", encode(nonce))
    .put("ciphertext_b64", encode(ciphertextAndTag))

internal fun JSONObject.toEnvelope(): ChatEnvelope = ChatEnvelope(
    version = getInt("version"),
    conversationId = getString("conversation_id"),
    senderDeviceId = getString("sender_device_id"),
    sequence = getLong("sequence"),
    nonce = decode(getString("nonce_b64")),
    ciphertextAndTag = decode(getString("ciphertext_b64")),
)

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
