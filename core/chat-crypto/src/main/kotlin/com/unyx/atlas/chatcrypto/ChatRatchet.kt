package com.unyx.atlas.chatcrypto

import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private const val KEY_BYTES = 32

/** HKDF-SHA256 (RFC 5869) — extract-then-expand, for deriving the session root key from the raw
 *  X25519 shared secret (which isn't uniformly random the way a chain key already is, so the
 *  extract step earns its keep here, unlike in [ChatRatchet.step]). Same construction already
 *  used in `core/security`'s `AtlasRecoveryEnvelopeCrypto.hkdf` — kept separate rather than
 *  shared across modules since chat identity is a deliberately distinct trust domain (see
 *  `ChatIdentityKeyPair`'s doc). */
internal fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, outputBytes: Int = KEY_BYTES): ByteArray {
    val mac = Mac.getInstance(HMAC)
    mac.init(SecretKeySpec(salt, HMAC))
    val pseudoRandomKey = mac.doFinal(inputKeyMaterial)
    try {
        mac.init(SecretKeySpec(pseudoRandomKey, HMAC))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(outputBytes)
    } finally {
        Arrays.fill(pseudoRandomKey, 0)
    }
}

/** One direction's symmetric-ratchet chain key, advanced with plain HMAC (not full HKDF — the
 *  chain key already has full entropy, so there's nothing for an extract step to concentrate;
 *  this is the exact two-label construction the Signal Double Ratchet spec uses for its own
 *  symmetric-key ratchet, cited in `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §5 — reimplemented
 *  from that public description, no Signal code involved):
 *
 *  ```
 *  messageKey   = HMAC-SHA256(chainKey, 0x01)
 *  chainKey'    = HMAC-SHA256(chainKey, 0x02)
 *  ```
 *
 *  Not thread-safe by design — a conversation's two chains (send/receive) belong to one
 *  [ChatSession], itself meant to be used from one coroutine/thread at a time by its caller. */
internal class RatchetChain private constructor(private var chainKey: ByteArray) {
    /** Advances the chain by one step, returning the message key for *this* step. The old chain
     *  key is zeroed; forward secrecy depends on never keeping a copy around past this call. */
    fun advance(): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(chainKey, HMAC))
        val messageKey = mac.doFinal(byteArrayOf(0x01))
        mac.init(SecretKeySpec(chainKey, HMAC))
        val nextChainKey = mac.doFinal(byteArrayOf(0x02))
        Arrays.fill(chainKey, 0)
        chainKey = nextChainKey
        return messageKey
    }

    fun zeroize() {
        Arrays.fill(chainKey, 0)
    }

    /** A copy of the current chain key, for persistence (see `ChatSession.snapshot`) — doesn't
     *  advance the chain or affect forward secrecy by itself; what matters is that the persisted
     *  copy is encrypted at rest (the storage layer's job) and that this method is never used to
     *  "peek" a message key without going through [advance]. */
    fun currentKeyCopy(): ByteArray = chainKey.copyOf()

    companion object {
        fun from(initialChainKey: ByteArray): RatchetChain = RatchetChain(initialChainKey.copyOf())
    }
}
