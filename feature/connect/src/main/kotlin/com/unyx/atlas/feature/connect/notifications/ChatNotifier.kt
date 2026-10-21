package com.unyx.atlas.feature.connect.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Plan §15 — "Notificação: título 'Atlas' e texto 'Você recebeu uma nova mensagem'. Não mostrar
 *  avatar, nome, trecho ou ação de resposta direta na tela bloqueada." Deliberately the exact same
 *  generic copy regardless of which conversation(s) had new messages or how many — the plan is
 *  explicit this must never leak who messaged or what was said, even to the lock screen, so there
 *  is nothing conversation-specific to compute here in the first place. Tapping opens
 *  `AtlasConnectActivity` generically (never a specific conversation — see [AtlasConnectScreen]'s
 *  own doc on why that would be the one thing that could force a `NavHost`/deep-link migration
 *  this module doesn't need yet). Mirrors `feature:updater`'s own `UpdateNotifier` pattern. */
object ChatNotifier {
    private const val CHANNEL = "chat_messages"
    private const val NOTIF_ID = 4301

    /** Returns true only if actually shown, so the caller can decide whether to treat the batch
     *  as "notified" — same contract [UpdateNotifier.notifyAvailable] already established. */
    fun notifyNewMessage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mensagens do Atlas Connect", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // setClassName, not a direct class reference — feature:connect doesn't need a compile dep
        // on :app this way, same reasoning UpdateNotifier's own doc comment gives.
        val launch = Intent().apply {
            setClassName(context.packageName, "com.unyx.atlas.AtlasConnectActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Atlas")
            .setContentText("Você recebeu uma nova mensagem")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // A single fixed id, not one per batch: the content never varies (by design, for
        // privacy), so a second new-message notification replacing the first instead of stacking
        // an unbounded pile of identical ones is the right behavior, not a bug.
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification); true }.getOrDefault(false)
    }
}
