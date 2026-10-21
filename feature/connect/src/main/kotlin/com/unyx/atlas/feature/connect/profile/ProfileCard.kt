package com.unyx.atlas.feature.connect.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.ui.component.ContactAvatar

/**
 * The visual "card" version of a chat profile — photo, nome, recado, bio e interesses together,
 * reused both in "Meu perfil" (live preview of your own card) and in the pairing screens
 * (self-preview while presenting/scanning). Never fed data that arrived over the wire mid-pairing
 * — at that point only pairing/crypto metadata exists, not profile content (see
 * `ConnectPairingViewModel`'s own doc on why this is always a LOCAL preview there, never anything
 * the other phone has already received).
 */
@Composable
fun ProfileCard(
    displayName: String,
    statusMessage: String,
    bio: String,
    interesses: List<String>,
    avatarBytes: ByteArray?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ContactAvatar(name = displayName, photoUri = null, photoBytes = avatarBytes, size = 56.dp)
                Column {
                    Text(
                        text = displayName.ifBlank { "Sem nome" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (statusMessage.isNotBlank()) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (bio.isNotBlank()) {
                Text(text = bio, style = MaterialTheme.typography.bodyMedium)
            }
            if (interesses.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    interesses.forEach { interesse ->
                        AssistChip(onClick = {}, label = { Text(interesse) })
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileCardPreview() {
    LauncherTheme {
        ProfileCard(
            displayName = "Marco Aurélio",
            statusMessage = "Disponível",
            bio = "Gosto de café, trilhas e tecnologia. Construindo o Atlas nos fins de semana.",
            interesses = listOf("café", "trilhas", "fotografia", "corrida"),
            avatarBytes = null,
        )
    }
}
