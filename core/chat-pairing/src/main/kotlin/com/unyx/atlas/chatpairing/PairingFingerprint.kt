package com.unyx.atlas.chatpairing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The "safety number" / código de segurança the plan's §8.3 step 5-6 has both users compare
 *  presencially before a pairing is trusted: "A representação de verificação incorpora
 *  identidades e contexto". Deliberately *not* "a truncated short code made up for this app" —
 *  the plan explicitly warns against that ("Não implementar um código curto truncado
 *  arbitrariamente") — this is a full SHA-256 over both public keys plus the conversation context,
 *  only *presented* in short, readable groups; the full digest is what's actually being compared,
 *  the grouping is just for humans to read it out loud or glance-compare.
 *
 *  Symmetric by construction: both sides compute from the exact same two public keys sorted into
 *  a canonical order first, so it doesn't matter which side is "local" vs "remote" — A and B must
 *  get byte-identical output or the presencial comparison would never agree for a correct pairing,
 *  which would make the whole verification step worthless. */
object PairingFingerprint {
    private const val DOMAIN = "ATLAS-CHAT-PAIRING-FINGERPRINT-V1"

    /** [conversationId] binds the fingerprint to one specific pairing context — comparing it for
     *  conversation X must not accidentally validate a swapped-in session for conversation Y. */
    fun compute(localPublicKeyBytes: ByteArray, remotePublicKeyBytes: ByteArray, conversationId: String): String {
        val (first, second) = canonicalOrder(localPublicKeyBytes, remotePublicKeyBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(StandardCharsets.UTF_8))
        digest.update(first)
        digest.update(second)
        digest.update(conversationId.toByteArray(StandardCharsets.UTF_8))
        return format(digest.digest())
    }

    private fun canonicalOrder(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return if (diff < 0) a to b else b to a
        }
        return if (a.size <= b.size) a to b else b to a
    }

    /** Groups of 5 decimal digits, same readability idea Signal's own safety numbers use — easier
     *  to read aloud or glance-compare than a long hex string, without discarding any entropy
     *  (every byte of the digest still feeds the decimal expansion, nothing is truncated). */
    private fun format(digestBytes: ByteArray): String {
        val decimal = digestBytes.joinToString("") { (it.toInt() and 0xFF).toString().padStart(3, '0') }
        return decimal.chunked(5).joinToString(" ")
    }
}
