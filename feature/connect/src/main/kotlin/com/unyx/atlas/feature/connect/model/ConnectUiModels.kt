package com.unyx.atlas.feature.connect.model

/**
 * Presentation-only models for Atlas Connect's components — per
 * `docs/ATLAS_CHAT_Plano_Arquitetura_Layouts_e_Telas.md` §15: "Componentes recebem modelos de
 * apresentação, não entidades com segredos ou clientes de rede." No [ChatSession], no
 * [ChatPairingRepository], no raw timestamps — the ViewModel formats everything before a
 * component ever sees it.
 */
data class ConversationRowModel(
    val conversationId: String,
    val displayName: String,
    val photoUri: String?,
    val isFavorite: Boolean = false,
    /** Already formatted for display (e.g. "14:32", "Ontem") — never a raw epoch/Instant; the
     *  plan's §9 rule against mixing presentation and domain concerns. */
    val lastEventTimeLabel: String?,
    /** A generic label — "Nova mensagem", "Conversa privada", "Envio pendente",
     *  "Verificação necessária" — per the plan's §9: "Não mostrar texto da mensagem por padrão." */
    val previewLabel: String,
    val unreadCount: Int,
    /** Plan §4 — the contact's own avatar, already decrypted (see `ChatAvatarStore`); null means
     *  no avatar was ever received, not "still loading" — this model has no concept of loading
     *  states. Trailing with a default so every existing positional construction of this model
     *  (previews included) keeps compiling unchanged. */
    val photoBytes: ByteArray? = null,
)

enum class MessageDirection { SENT, RECEIVED }

/** Mirrors the plan's §10 message-state table — intentionally distinct states for "sent" vs.
 *  "delivered" vs. "no proof of delivery" so a component can never collapse them into one vague
 *  checkmark. Only meaningful for [MessageDirection.SENT]; a received message has no outbound
 *  delivery state of its own. */
enum class MessageDeliveryState {
    PENDING_LOCAL,
    WAITING_NETWORK,
    SERVER_ACCEPTED,
    DELIVERED,
    RETRYING,
    FAILED,
    DELIVERY_UNCONFIRMED,
}

/** Plan §11 "Responder a uma mensagem" — [snippet] is null when the referenced message no longer
 *  resolves locally (deleted — plan §12 — or never arrived), in which case the component must
 *  show "Mensagem indisponível" per the plan's own wording, never recover the text from anywhere
 *  else. [authorLabel] is "Você" for a reply to this device's own earlier message, otherwise the
 *  contact's display name — resolved by the ViewModel, not this model. */
data class ReplyPreview(val authorLabel: String, val snippet: String?)

data class MessageBubbleModel(
    val messageId: String,
    val text: String,
    val direction: MessageDirection,
    val timeLabel: String,
    val deliveryState: MessageDeliveryState?,
    val replyPreview: ReplyPreview? = null,
)
