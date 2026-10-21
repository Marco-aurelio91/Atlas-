package com.unyx.atlas.feature.chat.di

import android.content.Context
import com.unyx.atlas.feature.chat.ChatHistoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ClaudeClient's own Hilt bindings (and the ClaudeHttpClient qualifier) live in
// core:data/.../di/AiModule.kt now — see that file's doc comment for why.

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    @Provides
    @Singleton
    fun provideChatHistoryStore(@ApplicationContext context: Context): ChatHistoryStore = ChatHistoryStore(context)
}
