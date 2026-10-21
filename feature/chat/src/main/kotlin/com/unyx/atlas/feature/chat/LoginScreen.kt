package com.unyx.atlas.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AtlasHeroBackground = Color(0xFF090E1D)
private val AtlasHeroMuted = Color(0xFFB8C4E3)

/** Shown while [com.unyx.atlas.memory.auth.AuthState] is LoggedOut or ConfirmationPending. */
@Composable
fun LoginScreen(
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    pendingConfirmationFor: String?,
    mfaRequired: Boolean,
    mfaCode: String,
    onMfaCodeChange: (String) -> Unit,
    onVerifyMfa: () -> Unit,
    onCancelMfa: () -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compact = maxWidth < 360.dp
                val cardPadding = if (compact) 16.dp else 28.dp
                // Size this against the card's real inner width so the first phrase
                // remains on one line on narrow phones.
                val headlineSize = ((maxWidth - cardPadding * 2).value / 15f)
                    .coerceIn(14f, 32f)
                    .sp

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(32.dp))
                        .background(AtlasHeroBackground)
                        .padding(cardPadding),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.atlas_login_icon),
                            contentDescription = "Ícone do Atlas",
                            modifier = Modifier
                                .size(if (compact) 60.dp else 72.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Color(0xFF050914))
                                .padding(5.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "ATLAS",
                                color = Color.White,
                                fontSize = if (compact) 28.sp else 34.sp,
                                lineHeight = if (compact) 32.sp else 38.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "INTELIGÊNCIA PESSOAL",
                                color = AtlasHeroMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                letterSpacing = 0.7.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Spacer(Modifier.height(if (compact) 22.dp else 30.dp))
                    Column {
                        Text(
                            text = "Mais que responder.",
                            color = Color.White,
                            fontSize = headlineSize,
                            lineHeight = headlineSize * 1.15f,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = "Entender.",
                            color = Color.White,
                            fontSize = headlineSize,
                            lineHeight = headlineSize * 1.15f,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Uma inteligência que conecta contexto, memória e ação para tornar sua rotina mais simples.",
                        color = AtlasHeroMuted,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (mfaRequired) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Confirme seu acesso",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Digite o código de seis dígitos do seu app autenticador para concluir o login.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = mfaCode,
                        onValueChange = onMfaCodeChange,
                        label = { Text("Código autenticador") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onVerifyMfa,
                        enabled = !busy && mfaCode.length == 6,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 10.dp).size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("Verificar código")
                    }
                    TextButton(
                        onClick = onCancelMfa,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancelar entrada") }
                }
            } else if (pendingConfirmationFor != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Confirme seu e-mail",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Enviamos um link de confirmação para $pendingConfirmationFor. Abra o e-mail e toque no link para continuar.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onSignIn,
                        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("Já confirmei, entrar")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Acesse sua conta",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = onEmailChange,
                        label = { Text("E-mail") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text("Senha") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Button(
                        onClick = onSignIn,
                        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(top = 2.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("Entrar")
                    }
                    TextButton(
                        onClick = onSignUp,
                        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Criar conta")
                    }
                }
            }
        }
    }
}
