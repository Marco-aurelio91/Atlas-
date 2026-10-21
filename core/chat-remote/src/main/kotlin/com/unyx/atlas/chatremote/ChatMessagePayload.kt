package com.unyx.atlas.chatremote

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * What actually travels inside a [com.unyx.atlas.chatcrypto.ChatEnvelope]'s plaintext. `chat:
 * crypto` only knows about opaque bytes; this is the one layer above it that gives those bytes
 * meaning, so a text message and an E2EE delivery receipt (plan §12.3) can share the exact same
 * ratchet/transport instead of needing a second parallel channel.
 *
 * A receipt's [DeliveryReceipt.forSequence] is the SENDER's own outgoing sequence being
 * acknowledged, not the acker's. That is unambiguous on both ends because the two ratchet chains
 * of one conversation are the same physical sequence space viewed from opposite ends — sequence 5
 * on A's sending chain is exactly sequence 5 on B's receiving chain (see [ChatSession]'s own doc
 * on `sendingChain`/`receivingChain`).
 */
sealed interface ChatMessagePayload {
    /** [replyToMessageId] — plan §11 — is this message's own SENDER's local
     *  `"${direction}-${sequence}"` id for the message it replies to (null = not a reply). It is
     *  NOT yet valid as a lookup key on the RECEIVING side: that side's own SENT/RECEIVED labels
     *  for the exact same logical chain are flipped relative to the sender's (see [ChatSession]'s
     *  own `sendingChain`/`receivingChain` doc) — `ChatMessagingRepository.fetchAndDecryptNew`
     *  flips it before a caller ever sees it, so only that one seam needs to know this rule. */
    data class Text(val text: String, val replyToMessageId: String? = null) : ChatMessagePayload
    data class DeliveryReceipt(val forSequence: Long) : ChatMessagePayload

    /** Plan §5.1 — nome/recado/bio/interesses enviados em "mensagens de controle E2EE... não são
     *  mensagens visíveis na linha do tempo". No avatar reference field here — plan §5.2 describes
     *  avatar bytes traveling separately from the profile text update (see [AvatarUpdate]). */
    data class ProfileUpdate(
        val displayName: String,
        val statusMessage: String,
        val bio: String = "",
        val interesses: List<String> = emptyList(),
    ) : ChatMessagePayload

    /** Plan §4.3/§5.2 — the processed avatar bytes themselves (already square, ≤512×512, ≤256
     *  KiB JPEG, EXIF stripped by the re-encode — see `feature:connect`'s avatar pipeline),
     *  reusing this SAME E2EE envelope channel instead of a separate backend upload endpoint:
     *  `atlas_chat_message_envelopes.ciphertext_b64` is an unbounded Postgres `text` column, so
     *  there is nothing to add server-side for this to work. [avatarBytes] null means "removed"
     *  (plan §4.3: "Gerar atualização de perfil com avatar ausente"), not "unchanged" — there is
     *  no partial-update concept here, every [AvatarUpdate] replaces whatever the receiver had. */
    data class AvatarUpdate(val avatarBytes: ByteArray?) : ChatMessagePayload {
        override fun equals(other: Any?): Boolean {
            if (other !is AvatarUpdate) return false
            val mine = avatarBytes
            val theirs = other.avatarBytes
            return if (mine == null || theirs == null) mine == null && theirs == null else mine.contentEquals(theirs)
        }

        override fun hashCode(): Int = avatarBytes?.contentHashCode() ?: 0
    }

    companion object {
        private const val TYPE_TEXT: Byte = 0
        private const val TYPE_DELIVERY_RECEIPT: Byte = 1
        private const val TYPE_PROFILE_UPDATE: Byte = 2
        private const val TYPE_AVATAR_UPDATE: Byte = 3
        private const val INTERESSE_SEPARATOR = ""

        fun encode(payload: ChatMessagePayload): ByteArray = when (payload) {
            is Text -> {
                // Same 2-byte length-prefix idea as ProfileUpdate below: the reply id (if any) is
                // variable-length and sits before the text, which just runs to the end of the
                // buffer. An id this app ever produces (direction name + "-" + a Long) is nowhere
                // near the 65535 this allows.
                val replyIdBytes = payload.replyToMessageId?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
                val textBytes = payload.text.toByteArray(StandardCharsets.UTF_8)
                ByteBuffer.allocate(1 + 2 + replyIdBytes.size + textBytes.size)
                    .put(TYPE_TEXT)
                    .putShort(replyIdBytes.size.toShort())
                    .put(replyIdBytes)
                    .put(textBytes)
                    .array()
            }
            is DeliveryReceipt -> ByteBuffer.allocate(1 + java.lang.Long.BYTES).put(TYPE_DELIVERY_RECEIPT).putLong(payload.forSequence).array()
            is ProfileUpdate -> {
                // A 2-byte length prefix on each variable-length field except the last is what
                // lets several of them share one payload without a delimiter byte that could
                // collide with real text — plan §2 caps displayName at 60 perceived characters,
                // nowhere near the 65535 this allows. interesses are joined with U+001F (a
                // control character no on-screen keyboard can type), since that field runs to the
                // end of the buffer and needs its own internal separator instead of a length
                // prefix — a plain comma was rejected because a user's own interesse could contain
                // one.
                val nameBytes = payload.displayName.toByteArray(StandardCharsets.UTF_8)
                val statusBytes = payload.statusMessage.toByteArray(StandardCharsets.UTF_8)
                val bioBytes = payload.bio.toByteArray(StandardCharsets.UTF_8)
                val interessesBytes = payload.interesses.joinToString(INTERESSE_SEPARATOR).toByteArray(StandardCharsets.UTF_8)
                ByteBuffer.allocate(1 + 2 + nameBytes.size + 2 + statusBytes.size + 2 + bioBytes.size + interessesBytes.size)
                    .put(TYPE_PROFILE_UPDATE)
                    .putShort(nameBytes.size.toShort())
                    .put(nameBytes)
                    .putShort(statusBytes.size.toShort())
                    .put(statusBytes)
                    .putShort(bioBytes.size.toShort())
                    .put(bioBytes)
                    .put(interessesBytes)
                    .array()
            }
            is AvatarUpdate -> {
                // No length prefix needed — unlike Text/ProfileUpdate, this is the only field in
                // the payload, so it simply runs to the end of the buffer. Empty (zero remaining
                // bytes after the type byte) IS the "removed" signal, not a 0-byte image.
                val bytes = payload.avatarBytes ?: ByteArray(0)
                ByteBuffer.allocate(1 + bytes.size).put(TYPE_AVATAR_UPDATE).put(bytes).array()
            }
        }

        /** Null for anything that doesn't parse as a known payload — a decrypted-but-malformed
         *  envelope (wrong version, corrupted framing) is handled by the caller the same way a
         *  [com.unyx.atlas.chatcrypto.DecryptResult.Rejected] envelope is: acked and dropped, not
         *  crashed on. */
        fun decode(bytes: ByteArray): ChatMessagePayload? {
            if (bytes.isEmpty()) return null
            val buffer = ByteBuffer.wrap(bytes)
            return when (buffer.get()) {
                TYPE_TEXT -> {
                    if (buffer.remaining() < 2) return null
                    val replyIdLength = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() < replyIdLength) return null
                    val replyIdBytes = ByteArray(replyIdLength)
                    buffer.get(replyIdBytes)
                    val textBytes = ByteArray(buffer.remaining())
                    buffer.get(textBytes)
                    val replyToMessageId = if (replyIdLength == 0) null else String(replyIdBytes, StandardCharsets.UTF_8)
                    Text(String(textBytes, StandardCharsets.UTF_8), replyToMessageId)
                }
                TYPE_DELIVERY_RECEIPT -> {
                    if (buffer.remaining() < java.lang.Long.BYTES) return null
                    DeliveryReceipt(buffer.long)
                }
                TYPE_PROFILE_UPDATE -> {
                    if (buffer.remaining() < 2) return null
                    val nameLength = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() < nameLength) return null
                    val nameBytes = ByteArray(nameLength)
                    buffer.get(nameBytes)
                    if (buffer.remaining() < 2) return null
                    val statusLength = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() < statusLength) return null
                    val statusBytes = ByteArray(statusLength)
                    buffer.get(statusBytes)
                    if (buffer.remaining() < 2) return null
                    val bioLength = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() < bioLength) return null
                    val bioBytes = ByteArray(bioLength)
                    buffer.get(bioBytes)
                    val interessesBytes = ByteArray(buffer.remaining())
                    buffer.get(interessesBytes)
                    val interessesText = String(interessesBytes, StandardCharsets.UTF_8)
                    val interesses = if (interessesText.isEmpty()) emptyList() else interessesText.split(INTERESSE_SEPARATOR)
                    ProfileUpdate(
                        displayName = String(nameBytes, StandardCharsets.UTF_8),
                        statusMessage = String(statusBytes, StandardCharsets.UTF_8),
                        bio = String(bioBytes, StandardCharsets.UTF_8),
                        interesses = interesses,
                    )
                }
                TYPE_AVATAR_UPDATE -> {
                    val remaining = buffer.remaining()
                    AvatarUpdate(if (remaining == 0) null else ByteArray(remaining).also { buffer.get(it) })
                }
                else -> null
            }
        }
    }
}
