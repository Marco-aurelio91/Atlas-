package com.unyx.atlas.feature.chat

import android.content.Context
import android.content.Intent

/** Fires the system share sheet with plain text — the "share OUT" half of Atlas's sharing support
 * (the other half being the Assistant screen's ACTION_SEND intent-filter, which receives a share).
 * `FLAG_ACTIVITY_NEW_TASK` is required here because [context] is sometimes the application context
 * (not an Activity) when called from a context that doesn't have one handy. `internal` — shared by
 * the full chat's message action row and the Assistant screen's own answer actions. */
internal fun shareText(context: Context, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
