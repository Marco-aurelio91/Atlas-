package com.unyx.atlas.chatstorage.di

import android.content.Context
import android.content.SharedPreferences
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatIdentityStore
import com.unyx.atlas.chatstorage.ChatMessageHistoryStore
import com.unyx.atlas.chatstorage.ChatProfileStore
import com.unyx.atlas.chatstorage.EncryptedFileChatAvatarStore
import com.unyx.atlas.security.AtlasSecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** [ChatConversationStore] and [ChatContactStore] share one underlying file (see this module's
 * own doc for why) — this qualifier makes sure both providers below consume the exact same
 * [SharedPreferences] instance instead of each independently calling
 * [AtlasSecurePreferences.open] for the same file name, which this codebase has never needed to
 * do twice before and isn't a pattern worth risking. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatSessionsPreferences

/** Wires `chat:storage` to real Keystore-encrypted preferences — the same
 * [AtlasSecurePreferences] pattern `core/security`'s `AtlasAccountKeyManager` already uses for the
 * account root key, per `ChatConversationStore`'s own doc comment on what production wiring must
 * look like. Three preference files, split by failure/reset semantics: losing the identity store
 * is catastrophic (every pairing has to be redone); losing sessions+contacts just means
 * re-pairing those specific conversations — they share a file because a session without knowing
 * who it's with is useless and vice versa, so there's no reason to let one survive a reset the
 * other doesn't; losing message history only loses the visible transcript, nothing functional —
 * its own file so wiping it can't accidentally take the working crypto state down with it. */
@Module
@InstallIn(SingletonComponent::class)
object ChatStorageModule {

    private const val SESSIONS_PREFS_NAME = "atlas_chat_sessions_secure"
    private const val IDENTITY_PREFS_NAME = "atlas_chat_identity_secure"
    private const val HISTORY_PREFS_NAME = "atlas_chat_history_secure"
    private const val PROFILE_PREFS_NAME = "atlas_chat_profile_secure"

    @Provides
    @Singleton
    @ChatSessionsPreferences
    fun provideChatSessionsPreferences(@ApplicationContext context: Context): SharedPreferences =
        AtlasSecurePreferences.open(context, SESSIONS_PREFS_NAME).preferences

    @Provides
    @Singleton
    fun provideChatConversationStore(@ChatSessionsPreferences preferences: SharedPreferences): ChatConversationStore =
        ChatConversationStore(preferences)

    @Provides
    @Singleton
    fun provideChatIdentityStore(@ApplicationContext context: Context): ChatIdentityStore =
        ChatIdentityStore(AtlasSecurePreferences.open(context, IDENTITY_PREFS_NAME).preferences)

    @Provides
    @Singleton
    fun provideChatContactStore(@ChatSessionsPreferences preferences: SharedPreferences): ChatContactStore =
        ChatContactStore(preferences)

    @Provides
    @Singleton
    fun provideChatMessageHistoryStore(@ApplicationContext context: Context): ChatMessageHistoryStore =
        ChatMessageHistoryStore(AtlasSecurePreferences.open(context, HISTORY_PREFS_NAME).preferences)

    @Provides
    @Singleton
    fun provideChatProfileStore(@ApplicationContext context: Context): ChatProfileStore =
        ChatProfileStore(AtlasSecurePreferences.open(context, PROFILE_PREFS_NAME).preferences)

    @Provides
    @Singleton
    fun provideChatAvatarStore(@ApplicationContext context: Context): ChatAvatarStore = EncryptedFileChatAvatarStore(context)
}
