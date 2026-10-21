package com.unyx.atlas.feature.connect.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.connect.model.ConversationRowModel
import com.unyx.atlas.ui.component.ContactAvatar

/** One row in the conversation list — plan §9/§15: avatar, name, last-event time, a generic
 *  (never literal-message) preview label, and an unread badge. Pure presentation: [onClick]
 *  is the only thing this emits, no domain or crypto types cross this boundary. */
@Composable
fun ConversationRow(
    model: ConversationRowModel,
    onClick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                val unreadDescription = if (model.unreadCount > 0) ", ${model.unreadCount} não lidas" else ""
                contentDescription = "${model.displayName}$unreadDescription"
            }
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick(model.conversationId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(name = model.displayName, photoUri = model.photoUri, photoBytes = model.photoBytes, size = 48.dp)
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.previewLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            model.lastEventTimeLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onToggleFavorite(model.conversationId) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (model.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (model.isFavorite) "Remover dos favoritos" else "Marcar como favorita",
                        tint = if (model.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onDelete(model.conversationId) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Excluir conexão",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (model.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(width = 20.dp, height = 20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (model.unreadCount > 99) "99+" else model.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationRowUnreadPreview() {
    LauncherTheme {
        ConversationRow(
            model = ConversationRowModel(
                conversationId = "conv-1",
                displayName = "Alice",
                photoUri = null,
                isFavorite = true,
                lastEventTimeLabel = "14:32",
                previewLabel = "Conversa privada",
                unreadCount = 3,
            ),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationRowLongNamePendingPreview() {
    LauncherTheme {
        ConversationRow(
            model = ConversationRowModel(
                conversationId = "conv-2",
                displayName = "Bartholomew Alexander Montgomery III",
                photoUri = null,
                isFavorite = false,
                lastEventTimeLabel = "Ontem",
                previewLabel = "Envio pendente",
                unreadCount = 0,
            ),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationRowVerificationNeededPreview() {
    LauncherTheme {
        ConversationRow(
            model = ConversationRowModel(
                conversationId = "conv-3",
                displayName = "Carol",
                photoUri = null,
                isFavorite = false,
                lastEventTimeLabel = null,
                previewLabel = "Verificação necessária",
                unreadCount = 120,
            ),
            onClick = {},
        )
    }
}
