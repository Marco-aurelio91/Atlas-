package com.unyx.atlas.feature.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Root of feature:notes — no NavHost in this app (see feature:chat's AtlasChatScreen doc), so this
 * is a single Activity-hosted composable that switches between the grid and the editor via
 * [NotesViewModel.draft] being null/non-null, the same "internal state, not a nav graph" pattern
 * feature:chat already uses for its own side panels.
 */
@Composable
fun NotesScreen(onBack: () -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val draft = viewModel.draft
        if (draft != null) {
            NoteEditorScreen(viewModel = viewModel, note = draft, onBack = { viewModel.closeEditor() })
        } else {
            NotesListScreen(viewModel = viewModel, onBack = onBack)
        }
    }
}
