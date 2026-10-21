package com.unyx.atlas.chatcrypto

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import java.util.LinkedHashMap

/** How many not-yet-arrived message keys a session will derive ahead of the next in-order
 *  sequence before giving up — bounds memory/CPU against a malicious or malformed huge sequence
 *  jump (plan's T-23: "Erro controlado sem OOM ou loop"). Matches the window Signal itself uses. */
private const val MAX_SKIPPED_KEYS = 1000

sealed interface DecryptResult {
    data class Delivered(val plaintext: ByteArray) : DecryptResult
    /** Same envelope (or an old, already-consumed sequence) seen again — the plan's T-07 requires
     *  this read as "one message visible", i.e. a silent no-op, not an error. */
    data object Duplicate : DecryptResult
    /** GCM auth failed — tampered ciphertext, or the envelope's claimed conversation/sender/sequence
     *  doesn't match what it was decrypted against (T-09). The session itself stays usable for the
     *  next legitimate message; only this one envelope is lost. */
    data class Rejected(val cause: GeneralSecurityException) : DecryptResult
}

/** One side's live view of one conversation's ratchet — two independent chains (this device's
 *  outgoing messages, and the other device's, which arrive as this device's incoming) derived
 *  from a single X25519 agreement at pairing time. See
 *  `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §4-5 for the full design and its stated limits
 *  (no automatic post-compromise recovery — that needs a fresh pairing).
 *
 *  Not safe to share across coroutines without external synchronization — one conversation's
 *  session is meant to be owned by one serialized caller, the same way the rest of this codebase
 *  already serializes per-conversation crypto operations (see the ADR in the plan, §12.1 step 3). */
class ChatSession private constructor(
    val conversationId: String,
    private val localDeviceId: String,
    private val remoteDeviceId: String,
    private val sendingChain: RatchetChain,
    private var sendingNextSeq: Long,
    private val receivingChain: RatchetChain,
    private var receivingNextSeq: Long,
    private var highestProcessedSeq: Long,
    private val skippedKeys: LinkedHashMap<Long, ByteArray>,
) {
    /** Encrypts [plaintext] as the next message in this device's outgoing chain. */
    fun encrypt(plaintext: ByteArray): ChatEnvelope {
        val messageKey = sendingChain.advance()
        val sequence = sendingNextSeq
        sendingNextSeq += 1
        return try {
            encryptEnvelope(messageKey, plaintext, conversationId, localDeviceId, sequence)
        } finally {
            Arrays.fill(messageKey, 0)
        }
    }

    /** Decrypts an envelope claimed to be from [remoteDeviceId] in this conversation. Handles
     *  in-order delivery, a message arriving ahead of ones still in flight (derives and banks the
     *  skipped keys for later), a message arriving late (pulls its already-derived key back out
     *  of the bank), and a duplicate/replay of something already consumed. */
    fun decrypt(envelope: ChatEnvelope): DecryptResult {
        require(envelope.conversationId == conversationId) { "Envelope belongs to a different conversation" }
        require(envelope.senderDeviceId == remoteDeviceId) { "Envelope claims a sender this session doesn't recognize" }
        val sequence = envelope.sequence

        skippedKeys[sequence]?.let { bankedKey ->
            return try {
                val plaintext = decryptEnvelope(bankedKey, envelope)
                skippedKeys.remove(sequence)
                Arrays.fill(bankedKey, 0)
                highestProcessedSeq = maxOf(highestProcessedSeq, sequence)
                DecryptResult.Delivered(plaintext)
            } catch (failure: GeneralSecurityException) {
                DecryptResult.Rejected(failure)
            }
        }

        if (sequence <= highestProcessedSeq || sequence < receivingNextSeq) {
            // Already consumed (normal path) or previously skipped-then-evicted — either way the
            // key is gone; this envelope can only be a replay of something already delivered.
            return DecryptResult.Duplicate
        }

        val gap = sequence - receivingNextSeq
        require(gap <= MAX_SKIPPED_KEYS) { "Sequence jumped too far ahead ($gap); refusing to derive that many keys at once" }

        var pending = receivingNextSeq
        while (pending < sequence) {
            val skipped = receivingChain.advance()
            skippedKeys[pending] = skipped
            if (skippedKeys.size > MAX_SKIPPED_KEYS) {
                val oldestSeq = skippedKeys.keys.first()
                skippedKeys.remove(oldestSeq)?.let { Arrays.fill(it, 0) }
            }
            pending += 1
        }
        val messageKey = receivingChain.advance()
        receivingNextSeq = sequence + 1

        return try {
            val plaintext = decryptEnvelope(messageKey, envelope)
            highestProcessedSeq = sequence
            DecryptResult.Delivered(plaintext)
        } catch (failure: GeneralSecurityException) {
            // The chain already moved forward — correct and intentional (see the class doc's
            // "session stays usable" note). This specific sequence simply can't be recovered.
            DecryptResult.Rejected(failure)
        } finally {
            Arrays.fill(messageKey, 0)
        }
    }

    fun zeroize() {
        sendingChain.zeroize()
        receivingChain.zeroize()
        skippedKeys.values.forEach { Arrays.fill(it, 0) }
        skippedKeys.clear()
    }

    /** A durable copy of everything needed to resume this session after the process dies —
     *  nothing here advances any chain or otherwise changes this session's own state. The caller
     *  (`chat:storage`) owns encrypting it at rest the same way the account root key already is
     *  (Keystore-wrapped `AtlasSecurePreferences`, not plain `SharedPreferences`); this class has
     *  no opinion on storage, only on what the snapshot needs to contain to round-trip correctly. */
    fun snapshot(): ChatSessionSnapshot = ChatSessionSnapshot(
        conversationId = conversationId,
        localDeviceId = localDeviceId,
        remoteDeviceId = remoteDeviceId,
        sendingChainKey = sendingChain.currentKeyCopy(),
        sendingNextSeq = sendingNextSeq,
        receivingChainKey = receivingChain.currentKeyCopy(),
        receivingNextSeq = receivingNextSeq,
        highestProcessedSeq = highestProcessedSeq,
        skippedKeys = skippedKeys.mapValues { (_, key) -> key.copyOf() },
    )

    companion object {
        private const val INFO_SESSION_ROOT = "atlas-chat-session-root-v1"
        private const val INFO_CHAIN_LOW_TO_HIGH = "atlas-chat-chain-low-to-high-v1"
        private const val INFO_CHAIN_HIGH_TO_LOW = "atlas-chat-chain-high-to-low-v1"

        /** Derives a fresh [ChatSession] from one side's identity key pair and the other side's
         *  public key — see §4's pairing flow. [localUserId]/[remoteUserId] and [localDeviceId]/
         *  [remoteDeviceId] bind the derived keys to this exact pairing (a shared secret reused
         *  for a different pair of users/devices would derive different, unrelated chain keys).
         *  Both sides call this with their own role's arguments; the low/high canonical ordering
         *  (by device id, same pattern as the plan's `user_low`/`user_high` schema, §13) is what
         *  makes them land on the same two chain keys with opposite send/receive roles. */
        fun establish(
            local: ChatIdentityKeyPair,
            localUserId: String,
            localDeviceId: String,
            remotePublicKeyBytes: ByteArray,
            remoteUserId: String,
            remoteDeviceId: String,
            conversationId: String,
        ): ChatSession {
            val sharedSecret = local.agree(remotePublicKeyBytes)
            val rootKey = try {
                val salt = canonicalInfo(localUserId, remoteUserId, conversationId)
                hkdfSha256(sharedSecret, salt, INFO_SESSION_ROOT.toByteArray(StandardCharsets.UTF_8))
            } finally {
                Arrays.fill(sharedSecret, 0)
            }
            val lowToHigh = hkdfSha256(rootKey, rootKey, INFO_CHAIN_LOW_TO_HIGH.toByteArray(StandardCharsets.UTF_8))
            val highToLow = hkdfSha256(rootKey, rootKey, INFO_CHAIN_HIGH_TO_LOW.toByteArray(StandardCharsets.UTF_8))
            Arrays.fill(rootKey, 0)

            val localIsLow = localDeviceId < remoteDeviceId
            val sendingInitial = if (localIsLow) lowToHigh else highToLow
            val receivingInitial = if (localIsLow) highToLow else lowToHigh
            return ChatSession(
                conversationId = conversationId,
                localDeviceId = localDeviceId,
                remoteDeviceId = remoteDeviceId,
                sendingChain = RatchetChain.from(sendingInitial),
                sendingNextSeq = 0,
                receivingChain = RatchetChain.from(receivingInitial),
                receivingNextSeq = 0,
                highestProcessedSeq = -1,
                skippedKeys = LinkedHashMap(),
            ).also {
                Arrays.fill(lowToHigh, 0)
                Arrays.fill(highToLow, 0)
            }
        }

        /** Rebuilds a [ChatSession] from a [ChatSessionSnapshot] previously returned by
         *  [ChatSession.snapshot] — no X25519 agreement happens here, this resumes exactly where
         *  the session left off rather than re-pairing. The snapshot's own byte arrays are copied,
         *  not adopted, so the caller remains responsible for zeroing what it read from storage. */
        fun restore(snapshot: ChatSessionSnapshot): ChatSession = ChatSession(
            conversationId = snapshot.conversationId,
            localDeviceId = snapshot.localDeviceId,
            remoteDeviceId = snapshot.remoteDeviceId,
            sendingChain = RatchetChain.from(snapshot.sendingChainKey),
            sendingNextSeq = snapshot.sendingNextSeq,
            receivingChain = RatchetChain.from(snapshot.receivingChainKey),
            receivingNextSeq = snapshot.receivingNextSeq,
            highestProcessedSeq = snapshot.highestProcessedSeq,
            skippedKeys = LinkedHashMap(snapshot.skippedKeys.mapValues { (_, key) -> key.copyOf() }),
        )

        private fun canonicalInfo(localUserId: String, remoteUserId: String, conversationId: String): ByteArray {
            val (low, high) = if (localUserId < remoteUserId) localUserId to remoteUserId else remoteUserId to localUserId
            return "$low|$high|$conversationId".toByteArray(StandardCharsets.UTF_8)
        }
    }
}
