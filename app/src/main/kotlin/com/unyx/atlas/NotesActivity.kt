package com.unyx.atlas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.notes.NotesScreen
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the Notas screen — its own Activity (not a panel inside AtlasChatActivity) so it can be
 * pinned to the dock as its own app, matching AtlasChatActivity/SettingsActivity's own pattern. */
@AndroidEntryPoint
class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherTheme {
                NotesScreen(onBack = { finish() })
            }
        }
    }
}
