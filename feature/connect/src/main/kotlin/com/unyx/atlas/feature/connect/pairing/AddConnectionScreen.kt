package com.unyx.atlas.feature.connect.pairing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.unyx.atlas.chatstorage.ChatProfile
import com.unyx.atlas.feature.connect.nfc.NfcCardReader
import com.unyx.atlas.feature.connect.nfc.NfcCardService
import com.unyx.atlas.feature.connect.profile.ProfileCard
import com.unyx.atlas.feature.connect.qr.QrCodeAnalyzer
import java.util.concurrent.Executors

/**
 * The "Adicionar contato" + "Pareamento e verificação" screens from
 * `docs/ATLAS_CHAT_Plano_Arquitetura_Layouts_e_Telas.md` §11 (T05/T06), folded into one
 * phase-driven composable instead of a nav graph — same "internal state, not a nav graph"
 * pattern the rest of this app already uses (see `AtlasConnectScreen`'s own doc). NFC and QR run
 * side by side in [PresentingContent]/[ScanningContent] — no separate method choice for NFC,
 * whichever transport the other phone uses first wins (see [NfcCardReader]'s own doc).
 */
@Composable
fun AddConnectionScreen(viewModel: ConnectPairingViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    // Without this, the system back gesture/button was never intercepted at all — it fell
    // through to AtlasConnectActivity's own default (finish/move-to-back), exiting the whole
    // pairing flow in one press from ANY phase instead of unwinding it one step at a time, the
    // same logic the toolbar's own back arrow already uses.
    BackHandler {
        if (viewModel.phase == PairingPhase.METHOD_CHOICE) onBack() else viewModel.back()
    }
    Scaffold(
        topBar = {
            Row(
                // Without this, the whole top bar — including this back arrow — renders under
                // the system status bar on an edge-to-edge Activity (AtlasConnectActivity calls
                // enableEdgeToEdge()): visible, but taps there are swallowed by the status bar
                // instead of reaching this IconButton. Every other screen in this app already
                // applies statusBarsPadding() to its own header for the same reason.
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (viewModel.phase == PairingPhase.METHOD_CHOICE) onBack() else viewModel.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    text = titleFor(viewModel.phase),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            when (viewModel.phase) {
                PairingPhase.METHOD_CHOICE -> MethodChoiceContent(onChoose = viewModel::chooseMethod)
                PairingPhase.ENTERING_NAME -> EnterNameContent(
                    name = viewModel.displayNameInput,
                    onNameChange = { viewModel.displayNameInput = it },
                    error = viewModel.errorMessage,
                    busy = viewModel.isBusy,
                    onConfirm = viewModel::confirmName,
                )
                PairingPhase.PRESENTING, PairingPhase.AWAITING_CLAIM -> PresentingContent(viewModel = viewModel)
                PairingPhase.VERIFY_AS_CREATOR -> VerifyFingerprintContent(
                    fingerprint = viewModel.detectedClaim?.fingerprint.orEmpty(),
                    contactName = viewModel.contactNameInput,
                    onContactNameChange = { viewModel.contactNameInput = it },
                    error = viewModel.errorMessage,
                    busy = viewModel.isBusy,
                    onConfirm = viewModel::confirmFingerprintAsCreator,
                    onCancel = viewModel::back,
                )
                PairingPhase.SCANNING -> ScanningContent(
                    onDecoded = viewModel::onCardBytesReceived,
                    busy = viewModel.isBusy,
                    onPresentInstead = { viewModel.chooseMethod(PairingMethod.PRESENT_QR) },
                    myProfile = viewModel.myProfile,
                    myAvatarBytes = viewModel.myAvatarBytes,
                )
                PairingPhase.VERIFY_AS_CLAIMER -> VerifyFingerprintContent(
                    fingerprint = viewModel.accepted?.fingerprint.orEmpty(),
                    contactName = viewModel.contactNameInput,
                    onContactNameChange = { viewModel.contactNameInput = it },
                    error = viewModel.errorMessage,
                    busy = viewModel.isBusy,
                    onConfirm = viewModel::confirmFingerprintAsClaimer,
                    onCancel = viewModel::back,
                )
                PairingPhase.AWAITING_REMOTE_CONFIRMATION -> WaitingContent(
                    message = "Aguardando a outra pessoa confirmar o pareamento...",
                )
                PairingPhase.ESTABLISHED -> EstablishedContent(onDone = onDone)
                PairingPhase.ERROR -> ErrorContent(message = viewModel.errorMessage, onBack = onBack)
            }
        }
    }
}

private fun titleFor(phase: PairingPhase): String = when (phase) {
    PairingPhase.METHOD_CHOICE -> "Adicionar contato"
    PairingPhase.ENTERING_NAME -> "Seu nome"
    PairingPhase.PRESENTING, PairingPhase.AWAITING_CLAIM -> "Apresentar meu QR"
    PairingPhase.VERIFY_AS_CREATOR, PairingPhase.VERIFY_AS_CLAIMER -> "Verificação de identidade"
    PairingPhase.SCANNING -> "Ler QR Code"
    PairingPhase.AWAITING_REMOTE_CONFIRMATION -> "Pareamento"
    PairingPhase.ESTABLISHED -> "Conectado"
    PairingPhase.ERROR -> "Erro"
}

@Composable
private fun MethodChoiceContent(onChoose: (PairingMethod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Pareie presencialmente com outra conta Atlas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A dedicated, explicit NFC entry — the QR buttons below already run NFC in the
        // background too (see PresentingContent/ScanningContent), but a live test showed a
        // one-line caption wasn't visible enough: tapping THIS button goes straight to
        // PresentingContent, which shows this device's own ProfileCard and starts both HCE and
        // the QR fallback at once — "mostrar o cartão do contato" is exactly that card.
        if (nfcSupported(LocalContext.current)) {
            ListItem(
                headlineContent = { Text("Conectar por NFC") },
                supportingContent = { Text("Aproxime os aparelhos — mostra seu card de contato") },
                leadingContent = { Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onChoose(PairingMethod.PRESENT_QR) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Conectar por NFC")
            }
            Text(
                text = "Se for você quem vai aproximar o aparelho de quem já está esperando, use " +
                    "\"Ler QR Code\" abaixo em vez disso — ele também lê por NFC.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListItem(
            headlineContent = { Text("Apresentar meu contato") },
            supportingContent = { Text("Mostra um QR para a outra pessoa escanear") },
            leadingContent = { Icon(Icons.Default.QrCode2, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onChoose(PairingMethod.PRESENT_QR) }, modifier = Modifier.fillMaxWidth()) {
            Text("Apresentar meu QR")
        }
        ListItem(
            headlineContent = { Text("Receber contato") },
            supportingContent = { Text("Usa a câmera, ou aproxime o aparelho (NFC), para ler o contato") },
            leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { onChoose(PairingMethod.SCAN_QR) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ler QR Code")
        }
    }
}

/** `FEATURE_NFC_HOST_CARD_EMULATION` covers both presenting and reading — a device with NFC but
 *  no HCE support (rare, but exists) can still read a tag-emulating device, so this check alone
 *  isn't a perfect predictor of "NFC pairing will work here both ways"; it's used only to decide
 *  whether to even mention NFC in the UI, never to block the code paths themselves (those degrade
 *  silently on their own — see [PresentingContent]/[ScanningContent]). */
private fun nfcSupported(context: android.content.Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

/** The visible, persistent NFC indicator for [PresentingContent]/[ScanningContent] — a one-line
 *  caption on [MethodChoiceContent] wasn't enough (easy to miss, and gone by the time the user is
 *  actually holding two phones together), so this renders directly where the QR code or camera
 *  preview itself is, every time NFC is actually available. */
@Composable
private fun NfcAvailableRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = "Ou aproxime os dois aparelhos (NFC) — não precisa escolher nada.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EnterNameContent(
    name: String,
    onNameChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Como a outra pessoa vai ver seu nome?",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Nome de exibição") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Gerar QR")
        }
    }
}

@Composable
private fun PresentingContent(viewModel: ConnectPairingViewModel) {
    // Registers this app's NfcCardService as the preferred HCE service WHILE this screen is
    // actually resumed and on top — tied to the real Activity lifecycle (not just composition),
    // since CardEmulation.setPreferredService/unsetPreferredService is exactly what the platform
    // expects to be paired with onResume/onPause. A device without HCE support just gets a no-op
    // here (CardEmulation.getInstance returns usable null-safe calls that silently fail) — never
    // blocks the QR path, which keeps working regardless.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val activity = context as? Activity
        val nfcAdapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        val cardEmulation = nfcAdapter?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }
        val serviceComponent = activity?.let { android.content.ComponentName(it, NfcCardService::class.java) }
        fun register() {
            if (activity != null && cardEmulation != null && serviceComponent != null) {
                runCatching { cardEmulation.setPreferredService(activity, serviceComponent) }
            }
        }
        fun unregister() {
            if (activity != null && cardEmulation != null) runCatching { cardEmulation.unsetPreferredService(activity) }
        }
        // Register right away — by the time this effect runs, the Activity is normally ALREADY
        // resumed (Compose only composes a visible screen), so an observer added here would only
        // ever see a FUTURE ON_RESUME (e.g. coming back from Settings), never the current one.
        // Without this immediate call, setPreferredService silently never ran on first entry —
        // the exact bug a live two-device test caught (NFC "worked" at the hardware level, but
        // the OS's default tag dispatch took over instead of this app, since nothing had told it
        // this app's HostApduService should be preferred).
        register()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        viewModel.myProfile?.let { profile ->
            ProfileCard(
                displayName = profile.displayName,
                statusMessage = profile.statusMessage,
                bio = profile.bio,
                interesses = profile.interesses,
                avatarBytes = viewModel.myAvatarBytes,
            )
            Text(
                text = "Seu card — enviado para a outra pessoa depois que a conexão for confirmada.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        viewModel.myQrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Código QR do meu convite",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Text(
            text = "Peça para a outra pessoa escanear este código.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (nfcSupported(LocalContext.current)) {
            NfcAvailableRow()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Text("Aguardando...", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VerifyFingerprintContent(
    fingerprint: String,
    contactName: String,
    onContactNameChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Compare este código com o que aparece no outro aparelho. Só confirme se forem idênticos.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = contactName,
            onValueChange = onContactNameChange,
            label = { Text("Como você quer chamar essa conexão?") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Confirmo, os códigos são iguais")
        }
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun ScanningContent(
    onDecoded: (ByteArray) -> Unit,
    busy: Boolean,
    onPresentInstead: () -> Unit,
    myProfile: ChatProfile?,
    myAvatarBytes: ByteArray?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onDecodedState = rememberUpdatedState(onDecoded)
    var nfcHint by remember { mutableStateOf<String?>(null) }

    // NFC reader mode runs independently of the camera permission flow below — NFC has no
    // runtime prompt, so this keeps working even when the user denied the camera. Same
    // lifecycle-tied registration pattern as PresentingContent's CardEmulation side; a device
    // without NFC hardware just gets a no-op (NfcAdapter.getDefaultAdapter returns null).
    DisposableEffect(lifecycleOwner) {
        val activity = context as? Activity
        val nfcAdapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        val reader = NfcCardReader(
            onCardRead = { bytes -> nfcHint = null; onDecodedState.value(bytes) },
            onTagLost = { nfcHint = "Toque perdido — aproxime os aparelhos novamente, sem afastar." },
        )
        fun register() {
            if (activity == null || nfcAdapter == null) return
            runCatching {
                nfcAdapter.enableReaderMode(
                    activity, reader,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        fun unregister() {
            if (activity != null && nfcAdapter != null) runCatching { nfcAdapter.disableReaderMode(activity) }
        }
        // Same reason as PresentingContent's own register() call: this effect runs while the
        // Activity is normally already resumed, so waiting only for a future ON_RESUME would
        // mean enableReaderMode never actually ran on first entry — and without it active,
        // Android's own default tag dispatch intercepts the NFC tap instead of this app,
        // surfacing the system's "nenhum app compatível com essa etiqueta" dialog. Confirmed
        // live: hardware detection between the two phones worked, this registration call did not.
        register()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDenied = true
    }
    DisposableEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose {}
    }

    if (!hasCameraPermission) {
        // Plan §11: "câmera negada oferece apresentar o próprio QR ou voltar, sem bloquear o
        // aplicativo inteiro" — a dead end here would be exactly the thing that instruction
        // rules out, so the fallback is a real button, not just a sentence suggesting it.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (permissionDenied) {
                    "Sem acesso à câmera. Você pode apresentar o seu próprio QR em vez de ler o da outra pessoa."
                } else {
                    "Precisamos da câmera para ler o QR Code."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!permissionDenied) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Permitir câmera")
                }
            }
            OutlinedButton(onClick = onPresentInstead, modifier = Modifier.fillMaxWidth()) {
                Text("Apresentar meu QR em vez disso")
            }
            if (nfcSupported(context)) {
                NfcAvailableRow()
            }
            nfcHint?.let { hint ->
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (nfcSupported(context)) {
            NfcAvailableRow()
        }
        nfcHint?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        myProfile?.let { profile ->
            ProfileCard(
                displayName = profile.displayName,
                statusMessage = profile.statusMessage,
                bio = profile.bio,
                interesses = profile.interesses,
                avatarBytes = myAvatarBytes,
            )
            Text(
                text = "Seu card — a outra pessoa vai ver depois que a conexão for confirmada.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor(), QrCodeAnalyzer { bytes ->
                            onDecodedState.value(bytes)
                        })
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    },
                    ContextCompat.getMainExecutor(ctx),
                )
                previewView
            },
        )
        Text(
            text = if (busy) "Lendo convite..." else "Aponte a câmera para o QR Code da outra pessoa.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaitingContent(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EstablishedContent(onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Pareamento concluído! A conexão já está pronta — o envio de mensagens pela rede " +
                "ainda está sendo construído.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
    }
}

@Composable
private fun ErrorContent(message: String?, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = message ?: "Algo deu errado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
    }
}
