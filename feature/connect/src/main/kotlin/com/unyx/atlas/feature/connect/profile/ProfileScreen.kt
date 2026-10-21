package com.unyx.atlas.feature.connect.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.ui.component.ContactAvatar
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    val context = LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(context.contentResolver, uri)
    }

    val editorBitmap = viewModel.editorBitmap
    if (viewModel.isEditingAvatar && editorBitmap != null) {
        AvatarCropEditor(
            bitmap = editorBitmap,
            isSaving = viewModel.isProcessingAvatar,
            error = viewModel.avatarError,
            onRotateLeft = viewModel::rotateEditorLeft,
            onRotateRight = viewModel::rotateEditorRight,
            onCancel = viewModel::cancelAvatarEdit,
            onApply = { crop -> viewModel.applyAvatarCrop(context.contentResolver, crop) },
        )
        return
    }

    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    ProfileScreenContent(
        displayName = viewModel.displayName,
        statusMessage = viewModel.statusMessage,
        bio = viewModel.bio,
        interessesInput = viewModel.interessesInput,
        atlasId = viewModel.atlasId,
        canSave = viewModel.canSave,
        justSaved = viewModel.justSaved,
        avatarBytes = viewModel.avatarBytes,
        isProcessingAvatar = viewModel.isProcessingAvatar,
        avatarError = viewModel.avatarError,
        snackbarHostState = snackbarHostState,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onStatusMessageChange = viewModel::onStatusMessageChange,
        onBioChange = viewModel::onBioChange,
        onInteressesInputChange = viewModel::onInteressesInputChange,
        onCopyAtlasId = {
            clipboard.setText(AnnotatedString(viewModel.atlasId))
            scope.launch { snackbarHostState.showSnackbar("ID Atlas copiado") }
        },
        onSave = viewModel::save,
        onPickAvatar = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveAvatar = viewModel::removeAvatar,
        onBack = onBack,
    )
}

@Composable
private fun ProfileScreenContent(
    displayName: String,
    statusMessage: String,
    bio: String,
    interessesInput: String,
    atlasId: String,
    canSave: Boolean,
    justSaved: Boolean,
    avatarBytes: ByteArray?,
    isProcessingAvatar: Boolean,
    avatarError: String?,
    snackbarHostState: SnackbarHostState,
    onDisplayNameChange: (String) -> Unit,
    onStatusMessageChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onInteressesInputChange: (String) -> Unit,
    onCopyAtlasId: () -> Unit,
    onSave: () -> Unit,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text("Meu perfil", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (isProcessingAvatar) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 36.dp))
            } else {
                ContactAvatar(name = displayName, photoUri = null, photoBytes = avatarBytes, size = 112.dp)
            }
            Text("Sua foto aparece para as conexões que você já adicionou.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickAvatar, enabled = !isProcessingAvatar) { Text("Editar foto") }
                if (avatarBytes != null) {
                    TextButton(onClick = onRemoveAvatar, enabled = !isProcessingAvatar) {
                        Text("Remover foto", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (avatarError != null) {
                Text(avatarError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            ProfileSectionTitle("Sua identidade")
            OutlinedTextField(
                value = atlasId.ifBlank { "Não conectado" },
                onValueChange = {},
                readOnly = true,
                label = { Text("ID Atlas") },
                supportingText = { Text("Identificador fixo da sua conta Atlas") },
                trailingIcon = {
                    IconButton(onClick = onCopyAtlasId, enabled = atlasId.isNotBlank()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar ID Atlas")
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            ProfileSectionTitle("Informações do perfil")
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Nome de exibição") },
                supportingText = { Text("${displayName.codePointCount(0, displayName.length)}/$PROFILE_DISPLAY_NAME_MAX_LENGTH") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = statusMessage,
                onValueChange = onStatusMessageChange,
                label = { Text("Recado") },
                supportingText = { Text("${statusMessage.codePointCount(0, statusMessage.length)}/$PROFILE_STATUS_MESSAGE_MAX_LENGTH") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bio,
                onValueChange = onBioChange,
                label = { Text("Bio") },
                supportingText = { Text("${bio.codePointCount(0, bio.length)}/$PROFILE_BIO_MAX_LENGTH") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = interessesInput,
                onValueChange = onInteressesInputChange,
                label = { Text("Interesses") },
                supportingText = { Text("Separados por vírgula — até $PROFILE_INTEREST_MAX_COUNT") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Nome, recado, bio e interesses são enviados de forma criptografada às conexões " +
                        "existentes quando você salvar. A foto é atualizada depois que você confirmar a edição.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar alterações")
            }
            if (justSaved) {
                Text("Perfil salvo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            ProfileSectionTitle("Prévia do seu card")
            ProfileCard(
                displayName = displayName,
                statusMessage = statusMessage,
                bio = bio,
                interesses = parseInteresses(interessesInput),
                avatarBytes = avatarBytes,
            )
        }
    }
}

@Composable
private fun ProfileSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AvatarCropEditor(
    bitmap: android.graphics.Bitmap,
    isSaving: Boolean,
    error: String?,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onCancel: () -> Unit,
    onApply: (AvatarCrop) -> Unit,
) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var selectedCrop by remember(bitmap) { mutableStateOf<AvatarCrop?>(null) }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, enabled = !isSaving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancelar edição")
                }
                Text("Editar foto", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Arraste para posicionar, aproxime e confira a prévia circular.", style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds(),
            ) {
                val viewportW = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val viewportH = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
                val baseScale = max(viewportW / bitmap.width, viewportH / bitmap.height)
                val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
                    val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                    val nextScale = baseScale * nextZoom
                    val maxPanX = max(0f, (bitmap.width * nextScale - viewportW) / 2f)
                    val maxPanY = max(0f, (bitmap.height * nextScale - viewportH) / 2f)
                    zoom = nextZoom
                    pan = Offset(
                        (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                        (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY),
                    )
                }
                val displayScale = baseScale * zoom
                val imageW = bitmap.width * displayScale
                val imageH = bitmap.height * displayScale
                val maxPanX = max(0f, (imageW - viewportW) / 2f)
                val maxPanY = max(0f, (imageH - viewportH) / 2f)
                val displayPan = Offset(pan.x.coerceIn(-maxPanX, maxPanX), pan.y.coerceIn(-maxPanY, maxPanY))
                val left = (viewportW - imageW) / 2f + displayPan.x
                val top = (viewportH - imageH) / 2f + displayPan.y
                val sourceScale = baseScale * zoom
                val sourceSide = (viewportW / sourceScale).coerceAtMost(minOf(bitmap.width, bitmap.height).toFloat())
                val sourceLeft = ((-left / sourceScale).coerceIn(0f, bitmap.width - sourceSide))
                val sourceTop = ((-top / sourceScale).coerceIn(0f, bitmap.height - sourceSide))
                val crop = AvatarCrop(
                    leftFraction = sourceLeft / bitmap.width,
                    topFraction = sourceTop / bitmap.height,
                    sizeFraction = sourceSide / minOf(bitmap.width, bitmap.height),
                )
                SideEffect { selectedCrop = crop }
                Canvas(Modifier.fillMaxSize().transformable(transformState)) {
                    clipRect {
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(imageW.toInt(), imageH.toInt()),
                        )
                    }
                    val previewMask = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    }
                    drawPath(previewMask, Color.Black.copy(alpha = 0.46f))
                    drawCircle(Color.White, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
            Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f, enabled = !isSaving)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRotateLeft, enabled = !isSaving) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Girar para a esquerda") }
                Text("Girar", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onRotateRight, enabled = !isSaving) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Girar para a direita") }
            }
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel, enabled = !isSaving, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                Button(
                    onClick = { selectedCrop?.let(onApply) },
                    enabled = !isSaving && selectedCrop != null,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Usar foto")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    LauncherTheme {
        ProfileScreenContent(
            displayName = "Marco Aurélio",
            statusMessage = "Disponível",
            bio = "Gosto de café, trilhas e tecnologia.",
            interessesInput = "café, trilhas, fotografia",
            atlasId = "atlas-id",
            canSave = true,
            justSaved = false,
            avatarBytes = null,
            isProcessingAvatar = false,
            avatarError = null,
            snackbarHostState = remember { SnackbarHostState() },
            onDisplayNameChange = {},
            onStatusMessageChange = {},
            onBioChange = {},
            onInteressesInputChange = {},
            onCopyAtlasId = {},
            onSave = {},
            onPickAvatar = {},
            onRemoveAvatar = {},
            onBack = {},
        )
    }
}
