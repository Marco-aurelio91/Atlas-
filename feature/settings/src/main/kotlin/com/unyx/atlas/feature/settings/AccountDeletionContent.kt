package com.unyx.atlas.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unyx.atlas.memory.auth.ACCOUNT_DELETION_CONFIRMATION
import com.unyx.atlas.ui.expressive.LocalExpressivePalette

/**
 * Permanent, in-app account deletion (a Google Play requirement and a LGPD right). Deliberately
 * hard to trigger by accident: the user must type the confirmation word, and the dialog spells out
 * what is lost — including that memories are encrypted, so there is no recovering them afterwards.
 */
@Composable
fun AccountDeletionContent(viewModel: SettingsViewModel, userId: String?) {
    val palette = LocalExpressivePalette.current
    val state by viewModel.deletionState.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Excluir a conta apaga, de forma definitiva, tudo o que o Atlas guarda sobre você no servidor: " +
                "memórias, tarefas, projetos, notas sincronizadas, conversas do Atlas Connect, chaves de recuperação e o próprio login. " +
                "Não há como desfazer nem recuperar depois.",
            color = palette.dim,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (userId == null) {
            Text("Entre na sua conta Atlas para poder excluí-la.", color = palette.dim)
            return@Column
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { typed = ""; viewModel.clearDeletionError(); confirming = true },
            enabled = !state.busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "Excluindo…" else "Excluir minha conta") }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirming = false },
            title = { Text("Excluir a conta definitivamente?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Suas memórias são cifradas e só existem na sua conta: depois da exclusão, nem você nem o Atlas conseguem recuperá-las. " +
                            "Digite $ACCOUNT_DELETION_CONFIRMATION para confirmar.",
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Confirmação") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && typed.trim() == ACCOUNT_DELETION_CONFIRMATION && userId != null,
                    onClick = {
                        confirming = false
                        if (userId != null) viewModel.deleteAccount(userId)
                    },
                ) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancelar") } },
        )
    }
}
