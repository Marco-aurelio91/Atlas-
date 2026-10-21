package com.unyx.atlas.feature.connect.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unyx.atlas.chatremote.ChatMessagingRepository
import com.unyx.atlas.chatstorage.ChatContactStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Plan §15 — polls every 15 minutes (WorkManager's floor for periodic work, same constraint
 * [com.unyx.atlas.feature.chat.automation.AutomationWorker] already documents) for new chat
 * envelopes, same [ChatMessagingRepository.fetchAndDecryptNew] call the open-conversation screen's
 * own foreground poll already uses — this is what covers messages arriving while no conversation
 * screen is open at all. Posts at most one notification per run, and only if at least one newly
 * decrypted message belongs to a conversation that isn't currently muted (plan §13's
 * [com.unyx.atlas.chatstorage.ChatContact.isMuted]) — "Silenciar não impede sincronização... só
 * suprime alertas", so the fetch/decrypt/receipt side effects always happen regardless of mute
 * state; only whether a notification gets posted depends on it.
 */
@HiltWorker
class ChatNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messagingRepository: ChatMessagingRepository,
    private val contactStore: ChatContactStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val incoming = messagingRepository.fetchAndDecryptNew()
            val now = System.currentTimeMillis() / 1000
            val anyUnmuted = incoming.any { decrypted ->
                val contact = contactStore.load(decrypted.conversationId)
                contact == null || !contact.isMuted(now)
            }
            if (anyUnmuted) ChatNotifier.notifyNewMessage(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (it is CancellationException) throw it
                Result.retry()
            },
        )
    }
}
