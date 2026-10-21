package com.unyx.atlas.feature.connect.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unyx.atlas.chatstorage.ChatContact
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.ui.component.ContactAvatar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plan §16 "Detalhes do contato e segurança" — see [ContactDetailViewModel]'s own doc for exactly
 * which elements of that section are real today vs. not yet built.
 */
@Composable
fun ContactDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    // System back is handled by AtlasConnectScreen's own NavDisplay now — see its doc.
    LaunchedEffect(conversationId) { viewModel.open(conversationId) }
    LaunchedEffect(viewModel.removed) { if (viewModel.removed) onRemoved() }
    // Picks up a profile update (plan §5) that arrived while this screen was already open —
    // nothing here polls on its own, this only re-reads on resume, same pattern ConnectionsScreen
    // already uses for its own list.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }

    ContactDetailScreenContent(
        contact = viewModel.contact,
        avatarBytes = viewModel.avatarBytes,
        onBack = onBack,
        onRequestRemove = { showRemoveConfirm = true },
        onRequestBlock = { showBlockConfirm = true },
        onToggleFavorite = { viewModel.toggleFavorite() },
        // Unblocking is purely reversible/restorative — no confirmation needed, matching how
        // "Cancelar" never confirms anywhere else in this module.
        onUnblock = { viewModel.setBlocked(false) },
    )

    if (showRemoveConfirm) {
        val name = viewModel.contact?.displayName ?: "esta conexão"
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remover conexão?") },
            text = {
                Text(
                    "O pareamento e o histórico de conversa com $name serão removidos deste " +
                        "aparelho. Isso não pode ser desfeito — para voltar a conversar seria " +
                        "preciso parear de novo.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    viewModel.removeConnection()
                }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancelar") } },
        )
    }

    if (showBlockConfirm) {
        val name = viewModel.contact?.displayName ?: "esta conexão"
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Bloquear $name?") },
            // Plan §13's own description of the effect, in plain language.
            text = { Text("Você não vai mais receber mensagens nem atualizações de perfil desta conexão, e ela não vai receber as suas. Pode desbloquear quando quiser.") },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirm = false
                    viewModel.setBlocked(true)
                }) { Text("Bloquear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ContactDetailScreenContent(
    contact: ChatContact?,
    avatarBytes: ByteArray? = null,
    onBack: () -> Unit,
    onRequestRemove: () -> Unit,
    onRequestBlock: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onUnblock: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    text = "Detalhes da conexão",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { padding ->
        if (contact == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContactAvatar(name = contact.displayName, photoUri = null, photoBytes = avatarBytes, size = 96.dp)
                Text(text = contact.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Conectado em ${formatPairedDate(contact.pairedAtEpochSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contact.isBlocked) {
                    Text(
                        text = "Bloqueado — novas mensagens e atualizações de perfil estão suspensas nos dois sentidos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                val receivedDisplayName = contact.receivedDisplayName
                val receivedStatusMessage = contact.receivedStatusMessage
                if (receivedDisplayName != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(text = "Perfil recebido", style = MaterialTheme.typography.titleMedium)
                        Text(
                            // Plan §2/§16: a self-reported name/recado changing is NOT proof of
                            // anything cryptographic — kept visually and textually separate from
                            // "Identidade verificada" below on purpose.
                            text = "Nome e recado informados por esta pessoa — mudar isso não prova " +
                                "nem desfaz a verificação de identidade abaixo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = receivedDisplayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                        if (!receivedStatusMessage.isNullOrBlank()) {
                            Text(text = receivedStatusMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(text = "Identidade verificada", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Este código foi conferido presencialmente no momento do pareamento, " +
                            "comparando as duas identidades criptográficas. Se ele não for idêntico " +
                            "ao que a outra pessoa vê no aparelho dela, não confie nesta conexão.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = contact.verifiedFingerprint.ifBlank { "Não disponível (pareado antes deste recurso existir)" },
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
                    Text(if (contact.isFavorite) "Remover dos favoritos" else "Marcar como favorita")
                }
                OutlinedButton(onClick = if (contact.isBlocked) onUnblock else onRequestBlock, modifier = Modifier.fillMaxWidth()) {
                    Text(if (contact.isBlocked) "Desbloquear" else "Bloquear")
                }
                OutlinedButton(onClick = onRequestRemove, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Remover conexão", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatPairedDate(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

@Preview(showBackground = true)
@Composable
private fun ContactDetailScreenPreview() {
    LauncherTheme {
        ContactDetailScreenContent(
            contact = ChatContact(
                conversationId = "conv-1", displayName = "Marco Aurélio", remoteUserId = "user-1",
                remoteDeviceId = "device-1", pairedAtEpochSeconds = 1_760_000_000L,
                verifiedFingerprint = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890",
            ),
            onBack = {},
            onRequestRemove = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactDetailScreenWithReceivedProfilePreview() {
    LauncherTheme {
        ContactDetailScreenContent(
            contact = ChatContact(
                conversationId = "conv-1", displayName = "Marco Aurélio", remoteUserId = "user-1",
                remoteDeviceId = "device-1", pairedAtEpochSeconds = 1_760_000_000L,
                verifiedFingerprint = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890",
                receivedDisplayName = "Marco A. (trabalho)", receivedStatusMessage = "Reunião até 15h",
            ),
            onBack = {},
            onRequestRemove = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactDetailScreenBlockedPreview() {
    LauncherTheme {
        ContactDetailScreenContent(
            contact = ChatContact(
                conversationId = "conv-1", displayName = "Marco Aurélio", remoteUserId = "user-1",
                remoteDeviceId = "device-1", pairedAtEpochSeconds = 1_760_000_000L,
                verifiedFingerprint = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890",
                isBlocked = true,
            ),
            onBack = {},
            onRequestRemove = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactDetailScreenLoadingPreview() {
    LauncherTheme {
        ContactDetailScreenContent(contact = null, onBack = {}, onRequestRemove = {})
    }
}
