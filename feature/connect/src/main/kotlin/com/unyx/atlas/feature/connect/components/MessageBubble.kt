package com.unyx.atlas.feature.connect.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.connect.model.MessageBubbleModel
import com.unyx.atlas.feature.connect.model.MessageDeliveryState
import com.unyx.atlas.feature.connect.model.MessageDirection
import com.unyx.atlas.feature.connect.model.ReplyPreview

/** Renders one message — plan §10/§15's "Renderiza conteúdo inerte" rules its DISPLAY (no
 *  inferred read-receipts, no collapsing sent/delivered into one glyph); it does not forbid
 *  reporting a raw gesture upward. [onLongPress] is a pure callback — this composable has no
 *  opinion on what a long-press should DO (open a menu, confirm a delete, nothing at all); that
 *  decision belongs entirely to the caller, same as [onLongPress]'s default no-op leaves existing
 *  call sites unaffected. Received messages sit left, sent sit right, capped at 82% of the
 *  available width per the plan's own number. Delivery state is its own line under a sent
 *  bubble, in words — never a checkmark collapsing "sent" and "delivered" into the same glyph
 *  (plan §10: "Não inferir leitura a partir de entrega"). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(model: MessageBubbleModel, modifier: Modifier = Modifier, onLongPress: () -> Unit = {}) {
    val isSent = model.direction == MessageDirection.SENT
    val bubbleColor = if (isSent) Color(0xFF303B60) else Color(0xFF262831)
    val alignment = if (isSent) Alignment.End else Alignment.Start

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp) // ~82% of a typical phone's usable width at 16dp margins
                .clip(RoundedCornerShape(if (isSent) 20.dp else 16.dp))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics {
                    val directionLabel = if (isSent) "Enviada" else "Recebida"
                    val stateLabel = model.deliveryState?.let { ", ${it.accessibleLabel()}" }.orEmpty()
                    val replyLabel = model.replyPreview?.let { ", em resposta a ${it.authorLabel}: ${it.snippet ?: "Mensagem indisponível"}" }.orEmpty()
                    contentDescription = "$directionLabel às ${model.timeLabel}$stateLabel$replyLabel: ${model.text}"
                },
        ) {
            Column {
                model.replyPreview?.let { ReplyQuote(it) }
                Text(
                    text = model.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE5E7EE),
                    textAlign = TextAlign.Start,
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = model.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSent) {
                model.deliveryState?.let {
                    Text(
                        text = it.displayLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The quoted reference plan §11 puts above the replying message's own text — "indisponível" when
 *  [preview]'s snippet is null (the original was deleted locally, plan §12, or never resolved),
 *  per the plan's own wording, never a recovered/cached copy of the original text. */
@Composable
private fun ReplyQuote(preview: ReplyPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = preview.authorLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB0C6FF),
        )
        Text(
            text = preview.snippet ?: "Mensagem indisponível",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBFC2CC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plan §10's own wording for each state — kept here, not in the ViewModel, since it's purely a
 *  presentation concern (how to phrase a state the domain layer already resolved). */
private fun MessageDeliveryState.displayLabel(): String = when (this) {
    MessageDeliveryState.PENDING_LOCAL -> "Aguardando envio"
    MessageDeliveryState.WAITING_NETWORK -> "Aguardando conexão"
    MessageDeliveryState.SERVER_ACCEPTED -> "Enviada"
    MessageDeliveryState.DELIVERED -> "Entregue"
    MessageDeliveryState.RETRYING -> "Tentando novamente"
    MessageDeliveryState.FAILED -> "Não enviada"
    MessageDeliveryState.DELIVERY_UNCONFIRMED -> "Entrega não confirmada"
}

private fun MessageDeliveryState.accessibleLabel(): String = displayLabel()

@Preview(showBackground = true)
@Composable
private fun MessageBubbleSentDeliveredPreview() {
    LauncherTheme {
        MessageBubble(
            MessageBubbleModel(
                messageId = "m1", text = "Oi! Chegou certinho?", direction = MessageDirection.SENT,
                timeLabel = "14:32", deliveryState = MessageDeliveryState.DELIVERED,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubbleReceivedPreview() {
    LauncherTheme {
        MessageBubble(
            MessageBubbleModel(
                messageId = "m2", text = "Chegou sim, tudo certo por aqui.", direction = MessageDirection.RECEIVED,
                timeLabel = "14:33", deliveryState = null,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubbleReplyPreview() {
    LauncherTheme {
        MessageBubble(
            MessageBubbleModel(
                messageId = "m4", text = "Sim, chegou certinho!", direction = MessageDirection.RECEIVED,
                timeLabel = "14:34", deliveryState = null,
                replyPreview = ReplyPreview(authorLabel = "Você", snippet = "Oi! Chegou certinho?"),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubbleReplyUnavailablePreview() {
    LauncherTheme {
        MessageBubble(
            MessageBubbleModel(
                messageId = "m5", text = "Beleza então.", direction = MessageDirection.SENT,
                timeLabel = "14:36", deliveryState = MessageDeliveryState.SERVER_ACCEPTED,
                replyPreview = ReplyPreview(authorLabel = "Marco Aurélio", snippet = null),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubbleFailedLongTextPreview() {
    LauncherTheme {
        MessageBubble(
            MessageBubbleModel(
                messageId = "m3",
                text = "Essa é uma mensagem bem mais longa pra ver como o balão se comporta quando o texto " +
                    "ocupa várias linhas e precisa quebrar dentro do limite de largura definido.",
                direction = MessageDirection.SENT,
                timeLabel = "14:35",
                deliveryState = MessageDeliveryState.FAILED,
            ),
        )
    }
}
