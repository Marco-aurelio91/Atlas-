package com.unyx.atlas.chatremote.di

import com.unyx.atlas.chatremote.ChatAuthSession
import com.unyx.atlas.chatremote.ChatMessageRemoteClient
import com.unyx.atlas.chatremote.ChatMessagingRepository
import com.unyx.atlas.chatremote.ChatPairingRemoteClient
import com.unyx.atlas.chatremote.ChatPairingRepository
import com.unyx.atlas.chatremote.SupabaseChatMessageRemoteClient
import com.unyx.atlas.chatremote.SupabaseChatPairingRemoteClient
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatIdentityStore
import com.unyx.atlas.memory.SupabaseRestClient
import com.unyx.atlas.memory.auth.AuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires `chat:remote`'s orchestration repository to the already-provided
 * `SupabaseRestClient`/`AuthRepository` (from `core/memory`'s own Hilt module) and the
 * `ChatIdentityStore`/`ChatConversationStore` (from `core/chat-storage`'s `ChatStorageModule`) —
 * this module has no state of its own, only assembly. */
@Module
@InstallIn(SingletonComponent::class)
object ChatRemoteModule {

    @Provides
    @Singleton
    fun provideChatPairingRemoteClient(supabase: SupabaseRestClient): ChatPairingRemoteClient =
        SupabaseChatPairingRemoteClient(supabase)

    @Provides
    @Singleton
    fun provideChatAuthSession(auth: AuthRepository): ChatAuthSession = object : ChatAuthSession {
        override fun currentUserId(): String? = auth.currentUserId()
        override suspend fun currentAccessToken(): String? = auth.currentAccessToken()
    }

    @Provides
    @Singleton
    fun provideChatPairingRepository(
        identityStore: ChatIdentityStore,
        sessionStore: ChatConversationStore,
        contactStore: ChatContactStore,
        remote: ChatPairingRemoteClient,
        auth: ChatAuthSession,
    ): ChatPairingRepository = ChatPairingRepository(identityStore, sessionStore, contactStore, remote, auth)

    @Provides
    @Singleton
    fun provideChatMessageRemoteClient(supabase: SupabaseRestClient): ChatMessageRemoteClient =
        SupabaseChatMessageRemoteClient(supabase)

    @Provides
    @Singleton
    fun provideChatMessagingRepository(
        sessionStore: ChatConversationStore,
        contactStore: ChatContactStore,
        avatarStore: ChatAvatarStore,
        remote: ChatMessageRemoteClient,
        auth: ChatAuthSession,
    ): ChatMessagingRepository = ChatMessagingRepository(sessionStore, contactStore, avatarStore, remote, auth)
}
