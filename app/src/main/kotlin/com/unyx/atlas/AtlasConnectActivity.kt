package com.unyx.atlas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.connect.AtlasConnectScreen
import dagger.hilt.android.AndroidEntryPoint

/** Hosts Atlas Connect ("Conexões" + pairing) — its own Activity, independent of
 *  [AtlasChatActivity] (the AI assistant), same pattern as [NotesActivity]. Reached from the
 *  chat's "+ menu" modal, not the chat's own conversation-history drawer. */
@AndroidEntryPoint
class AtlasConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherTheme {
                AtlasConnectScreen(onBack = { finish() })
            }
        }
    }
}
