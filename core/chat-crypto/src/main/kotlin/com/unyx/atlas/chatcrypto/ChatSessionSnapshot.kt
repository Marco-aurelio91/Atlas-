package com.unyx.atlas.chatcrypto

import java.util.Arrays

/** Everything [ChatSession.restore] needs to resume a conversation across a process restart —
 *  see [ChatSession.snapshot]'s doc. Deliberately a plain data holder with no serialization or
 *  storage logic of its own: that belongs to `chat:storage`, which knows how this project encrypts
 *  things at rest (the same Keystore-wrapped pattern as the account root key, not plain prefs).
 *
 *  Every chain/message key here is live, sensitive key material — callers must treat an instance
 *  of this class itself as a secret for as long as they hold it, and [zeroize] it once persisted
 *  or once a restored [ChatSession] has taken its own copies. */
data class ChatSessionSnapshot(
    val conversationId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
    val sendingChainKey: ByteArray,
    val sendingNextSeq: Long,
    val receivingChainKey: ByteArray,
    val receivingNextSeq: Long,
    val highestProcessedSeq: Long,
    val skippedKeys: Map<Long, ByteArray>,
) {
    fun zeroize() {
        Arrays.fill(sendingChainKey, 0)
        Arrays.fill(receivingChainKey, 0)
        skippedKeys.values.forEach { Arrays.fill(it, 0) }
    }

    override fun equals(other: Any?): Boolean =
        other is ChatSessionSnapshot && conversationId == other.conversationId &&
            localDeviceId == other.localDeviceId && remoteDeviceId == other.remoteDeviceId &&
            sendingChainKey.contentEquals(other.sendingChainKey) && sendingNextSeq == other.sendingNextSeq &&
            receivingChainKey.contentEquals(other.receivingChainKey) && receivingNextSeq == other.receivingNextSeq &&
            highestProcessedSeq == other.highestProcessedSeq &&
            skippedKeys.keys == other.skippedKeys.keys &&
            skippedKeys.all { (seq, key) -> other.skippedKeys[seq]?.contentEquals(key) == true }

    override fun hashCode(): Int {
        var result = conversationId.hashCode()
        result = 31 * result + localDeviceId.hashCode()
        result = 31 * result + remoteDeviceId.hashCode()
        result = 31 * result + sendingChainKey.contentHashCode()
        result = 31 * result + sendingNextSeq.hashCode()
        result = 31 * result + receivingChainKey.contentHashCode()
        result = 31 * result + receivingNextSeq.hashCode()
        result = 31 * result + highestProcessedSeq.hashCode()
        return result
    }
}
