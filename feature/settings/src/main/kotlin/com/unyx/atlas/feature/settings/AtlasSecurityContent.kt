package com.unyx.atlas.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unyx.atlas.security.AccountKeyState
import com.unyx.atlas.ui.expressive.Accent
import com.unyx.atlas.ui.expressive.LocalExpressivePalette

@Composable
fun AtlasSecurityContent(viewModel: SettingsViewModel, userId: String?) {
    val state by viewModel.securityState.collectAsStateWithLifecycle()
    val recoveryCode = state.recoveryCode
    val palette = LocalExpressivePalette.current
    val context = LocalContext.current
    var recoveryCodeSaved by remember(recoveryCode) { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    LaunchedEffect(userId) { viewModel.loadSecurity(userId) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Proteção das memórias",
            color = palette.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "O Atlas cifra as memórias neste aparelho antes de sincronizá-las. Para ler ou salvar memórias, a chave precisa estar ativa. O código de recuperação permite restaurar a mesma chave em outro aparelho.",
            color = palette.dim,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (userId == null) {
            Text("Entre na sua conta Atlas para configurar a recuperação.", color = palette.dim)
            return@Column
        }

        Text("CHAVE DE MEMÓRIA", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        state.message?.let { Text(it, color = palette.dim, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (!state.persistentStorageAvailable) {
            Text(
                "O armazenamento seguro persistente não está disponível neste aparelho. A configuração foi bloqueada para não criar uma chave que desapareça ao fechar o app.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        state.recoveryStatusError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !state.busy,
                onClick = { viewModel.refreshSecurity(userId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Verificando…" else "Verificar novamente") }
        }

        when (state.keyState) {
            AccountKeyState.ACTIVE -> {
                Text("Estado: chave ativa neste aparelho", color = Accent, fontWeight = FontWeight.SemiBold)
                Text(
                    "As memórias podem ser salvas e lidas neste aparelho.",
                    color = palette.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
                when (state.recoveryEnvelopeAvailable) {
                    true -> Text(
                        "Recuperação da conta: configurada. Guarde o código fora do celular; ele não pode ser exibido novamente.",
                        color = palette.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    false -> Text(
                        "Atenção: não há cópia de recuperação na conta. Se este aparelho for perdido, a chave e as memórias cifradas podem ficar inacessíveis. Configure a recuperação antes de trocar de aparelho.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    null -> Unit
                }
            }
            AccountKeyState.SETUP_PENDING -> {
                if (recoveryCode != null) {
                    Text(
                        "Configuração em andamento — conclua os passos abaixo antes de sair desta tela.",
                        color = palette.text,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("1. Copie o código e guarde-o fora deste aparelho.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(
                            recoveryCode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = palette.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = { copyRecoveryCode(context, recoveryCode) },
                    ) { Text("Copiar código") }
                    Text("2. Confirme que salvou o código em local seguro.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = recoveryCodeSaved,
                            onCheckedChange = { recoveryCodeSaved = it },
                            enabled = !state.busy,
                        )
                        Text(
                            "Salvei o código fora do Atlas, em local seguro.",
                            color = palette.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("3. Digite o mesmo código para verificar.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.codeInput,
                        onValueChange = viewModel::updateRecoveryCode,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Digite o código para confirmar") },
                        singleLine = false,
                        shape = RoundedCornerShape(16.dp),
                        enabled = !state.busy,
                    )
                    if (state.recoveryEnvelopeConflict) {
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = { viewModel.cancelPendingSetupToRestore(userId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancelar e restaurar chave existente") }
                    }
                    Button(
                        enabled = !state.busy && recoveryCodeSaved && state.codeInput.isNotBlank(),
                        onClick = { viewModel.confirmAccountKeySetup(userId, recoveryCodeSaved) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                state.busy -> "Salvando…"
                                state.recoveryEnvelopeUploaded -> "Ativar chave"
                                else -> "Salvar recuperação e ativar chave"
                            },
                        )
                    }
                } else {
                    Text(
                        "Uma configuração anterior foi interrompida. O código antigo não fica guardado no Atlas e não pode ser mostrado novamente.",
                        color = palette.dim,
                    )
                    when (state.recoveryEnvelopeAvailable) {
                        true -> {
                            Text("Há uma cópia de recuperação na conta. Restaure a chave existente; não crie outra.", color = palette.dim)
                            RecoveryInput(viewModel, state)
                            OutlinedButton(
                                enabled = !state.busy && state.codeInput.isNotBlank(),
                                onClick = { viewModel.restoreAccountKey(userId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (state.busy) "Restaurando…" else "Restaurar chave existente") }
                        }
                        false -> {
                            Text(
                                "Não há cópia de recuperação na conta. Se você já usava memórias cifradas com uma chave que foi perdida, um novo código não recupera essa chave. Registros antigos ainda não cifrados podem ser migrados quando acessados.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                enabled = !state.busy,
                                onClick = { confirmNewKey = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Começar configuração com uma nova chave") }
                        }
                        null -> Text(
                            "Aguarde a verificação da cópia de recuperação antes de continuar.",
                            color = palette.dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            AccountKeyState.NOT_CONFIGURED -> {
                when (state.recoveryEnvelopeAvailable) {
                    true -> {
                        Text("Há uma cópia de recuperação salva na conta. Use o código que você guardou para restaurar a chave e acessar as memórias existentes.", color = palette.text)
                        RecoveryInput(viewModel, state)
                        OutlinedButton(
                            enabled = !state.busy && state.codeInput.isNotBlank(),
                            onClick = { viewModel.restoreAccountKey(userId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.busy) "Restaurando…" else "Restaurar chave existente") }
                    }
                    false -> {
                        Text(
                            "Nenhuma chave de recuperação foi encontrada na conta. Se este é seu primeiro uso, siga a configuração abaixo. Se você já usava o Atlas, uma nova chave não recupera memórias cifradas com uma chave antiga perdida.",
                            color = palette.dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("1. Gere uma chave e um código novos.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                        Text("2. Guarde o código fora do celular; o Atlas não consegue recuperá-lo por você.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                        Text("3. Confirme o código para salvá-lo na conta e ativar a chave.", color = palette.dim, style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = !state.busy,
                            onClick = { confirmNewKey = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.busy) "Preparando…" else "Começar configuração") }
                    }
                    null -> Text(
                        "Verificando se a conta já tem uma chave de recuperação…",
                        color = palette.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            null -> Text("Verificando o estado da chave neste aparelho…", color = palette.dim, style = MaterialTheme.typography.bodySmall)
        }

        if (confirmNewKey) {
            AlertDialog(
                onDismissRequest = { confirmNewKey = false },
                title = { Text("Antes de criar uma nova chave") },
                text = {
                    Text(
                        "Uma nova chave protege as próximas memórias, mas não abre memórias cifradas com uma chave antiga perdida. O código novo será a única forma de recuperar esta chave em outro aparelho. Quer continuar?",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmNewKey = false
                            if (state.keyState == AccountKeyState.SETUP_PENDING) {
                                viewModel.restartPendingSetup(userId)
                            } else {
                                viewModel.beginAccountKeySetup(userId)
                            }
                        },
                    ) { Text("Criar nova chave") }
                },
                dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("Voltar") } },
            )
        }

    }
}

@Composable
fun AtlasMfaContent(viewModel: SettingsViewModel, userId: String?) {
    val state by viewModel.securityState.collectAsStateWithLifecycle()
    val palette = LocalExpressivePalette.current
    LaunchedEffect(userId) { viewModel.loadSecurity(userId) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Esta opção protege o acesso à conta, não a chave de memória.",
            color = palette.text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Um app autenticador gera códigos temporários além da sua senha. Isso ajuda se alguém descobrir a senha. A proteção efetiva depende de a verificação em duas etapas também ser exigida pelo serviço; ela não substitui o código de recuperação das memórias.",
            color = palette.dim,
            style = MaterialTheme.typography.bodySmall,
        )
        if (userId == null) {
            Text("Entre na sua conta Atlas para configurar a autenticação.", color = palette.dim)
        } else {
            MfaSection(viewModel = viewModel, userId = userId, state = state, palette = palette)
        }
    }
}

@Composable
private fun RecoveryInput(
    viewModel: SettingsViewModel,
    state: AtlasSecurityUiState,
) {
    OutlinedTextField(
        value = state.codeInput,
        onValueChange = viewModel::updateRecoveryCode,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Digite o código de recuperação") },
        supportingText = { Text("Não compartilhe o código. Ele é necessário para abrir a chave.") },
        singleLine = false,
        shape = RoundedCornerShape(16.dp),
        enabled = !state.busy,
    )
}

@Composable
private fun MfaSection(
    viewModel: SettingsViewModel,
    userId: String,
    state: AtlasSecurityUiState,
    palette: com.unyx.atlas.ui.expressive.ExpressivePalette,
) {
    val verifiedFactors = state.mfaFactors.filter { it.status.equals("verified", ignoreCase = true) }
    var factorToRemove by remember { mutableStateOf<String?>(null) }
    val enrollment = state.totpEnrollment

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (enrollment != null) {
            Text(
                "No seu autenticador, adicione uma conta escolhendo inserir uma chave de configuração. Use o tipo baseado em tempo (TOTP).",
                color = palette.text,
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(
                    enrollment.secret,
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Não envie nem compartilhe este segredo. Se sair desta tela antes de confirmar, a configuração pendente será removida.",
                color = palette.dim,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.mfaCode,
                onValueChange = viewModel::updateMfaCode,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Código de seis dígitos") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(16.dp),
                enabled = !state.mfaBusy,
            )
            if (state.mfaMessage != null) Text(state.mfaMessage, color = palette.dim, style = MaterialTheme.typography.bodySmall)
            if (state.mfaError != null) Text(state.mfaError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !state.mfaBusy && state.mfaCode.length == 6,
                onClick = { viewModel.confirmTotpEnrollment(userId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.mfaBusy) "Verificando…" else "Confirmar e ativar") }
            OutlinedButton(
                enabled = !state.mfaBusy,
                onClick = { viewModel.cancelTotpEnrollment(userId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancelar configuração") }
        } else if (verifiedFactors.isNotEmpty()) {
            Text(
                "MFA ativa · ${verifiedFactors.size} autenticador(es) TOTP",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            verifiedFactors.forEach { factor ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(factor.friendlyName ?: "App autenticador", color = palette.text)
                    TextButton(
                        enabled = !state.mfaBusy,
                        onClick = { factorToRemove = factor.id },
                    ) { Text("Remover") }
                }
            }
            if (state.mfaMessage != null) Text(state.mfaMessage, color = palette.dim, style = MaterialTheme.typography.bodySmall)
            if (state.mfaError != null) Text(state.mfaError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Button(
                enabled = !state.mfaBusy,
                onClick = { viewModel.beginTotpEnrollment(userId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.mfaBusy) "Preparando…" else "Ativar app autenticador") }
            if (state.mfaMessage != null) Text(state.mfaMessage, color = palette.dim, style = MaterialTheme.typography.bodySmall)
            if (state.mfaError != null) Text(state.mfaError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (factorToRemove != null) {
        AlertDialog(
            onDismissRequest = { factorToRemove = null },
            title = { Text("Remover autenticador?") },
            text = { Text("Nas próximas entradas, esta conta não pedirá um código TOTP. Confirme que deseja remover este fator.") },
            confirmButton = {
                TextButton(
                    enabled = !state.mfaBusy,
                    onClick = {
                        factorToRemove?.let { viewModel.removeTotpFactor(userId, it) }
                        factorToRemove = null
                    },
                ) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { factorToRemove = null }) { Text("Manter") } },
        )
    }
}

private fun copyRecoveryCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Código de recuperação do Atlas", code))
    Toast.makeText(context, "Código copiado. Guarde-o em local seguro.", Toast.LENGTH_LONG).show()
}
