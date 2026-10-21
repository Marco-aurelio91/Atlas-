package com.unyx.atlas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.chat.AtlasChatScreen
import dagger.hilt.android.AndroidEntryPoint

/** Hosts Atlas chat and forwards authentication callbacks to Compose. */
@AndroidEntryPoint
class AtlasChatActivity : ComponentActivity() {

    private var pendingAssistantVoice by mutableStateOf(false)

    private var pendingAuthCallbackFragment by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingAssistantVoice = savedInstanceState?.getBoolean("pendingAssistantVoice")
            ?: isAssistantIntent(intent)
        pendingAuthCallbackFragment = intent?.data?.fragment
        setContent {
            LauncherTheme {
                val context = LocalContext.current
                // Location and notifications enable passive features (weather, automation/update
                // alerts) with no single obvious "tap to allow" moment the way the mic (tap-to-talk)
                // or calendar (creating a reminder) have — a user asked for exactly this after
                // hitting "preciso de permissão de localização" mid-chat with no way to act on it
                // right there. Mic/calendar stay requested lazily, in context, when that specific
                // feature is actually used (see their own manifest comments) — contextual requests
                // read as less alarming and grant at a higher rate than a wall of upfront dialogs.
                val ambientPermissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { /* Every check of these is live (checkSelfPermission) — nothing to store here. */ }
                LaunchedEffect(Unit) {
                    val missing = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isNotEmpty()) ambientPermissionsLauncher.launch(missing.toTypedArray())
                }
                AtlasChatScreen(
                    pendingAuthCallbackFragment = pendingAuthCallbackFragment,
                    onAuthCallbackConsumed = { pendingAuthCallbackFragment = null },
                    assistantVoiceRequested = pendingAssistantVoice,
                    onAssistantVoiceConsumed = { pendingAssistantVoice = false },
                    onOpenNotes = { startActivity(Intent(this, NotesActivity::class.java)) },
                    onOpenAtlasConnect = { startActivity(Intent(this, AtlasConnectActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingAssistantVoice", pendingAssistantVoice)
        super.onSaveInstanceState(outState)
    }

    private fun isAssistantIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_ASSIST || intent?.action == "android.intent.action.VOICE_ASSIST"

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAssistantVoice = isAssistantIntent(intent)
        pendingAuthCallbackFragment = intent.data?.fragment
    }
}
