package com.unyx.atlas.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unyx.atlas.ui.expressive.LocalExpressivePalette
import java.time.LocalDate

/**
 * Data portability (LGPD art. 18): saves a readable JSON copy of everything Atlas stores about the
 * user to a file they choose. The data is decrypted on the device and written straight to that
 * file — it never goes to the server — and the screen says plainly that the file itself is not
 * encrypted.
 */
@Composable
fun DataExportContent(viewModel: SettingsViewModel, userId: String?) {
    val palette = LocalExpressivePalette.current
    val context = LocalContext.current
    val state by viewModel.exportState.collectAsStateWithLifecycle()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && userId != null) {
            viewModel.exportData(userId) { context.contentResolver.openOutputStream(uri, "w") }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Salva um arquivo com tudo o que o Atlas guarda sobre você: memórias, pessoas, tarefas, projetos, notas e rotinas. " +
                "Os dados são decifrados neste aparelho e gravados direto no arquivo que você escolher; nada é enviado ao servidor. " +
                "O arquivo não é cifrado, então guarde-o com cuidado.",
            color = palette.dim,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (userId == null) {
            Text("Entre na sua conta Atlas para exportar seus dados.", color = palette.dim)
            return@Column
        }
        state.message?.let { Text(it, color = palette.dim, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { pickFile.launch("atlas-meus-dados-${LocalDate.now()}.json") },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "Exportando…" else "Exportar meus dados") }
    }
}
