package com.unyx.atlas.data.di

import android.content.Context
import com.unyx.atlas.data.ai.AiUsageMeter
import com.unyx.atlas.data.ai.ClaudeClient
import com.unyx.atlas.data.ai.GeminiChatClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Other modules (feature:updater, core:memory) also provide OkHttpClient into
 * SingletonComponent — an unqualified one here would collide with those bindings. Claude
 * responses can take a while to generate, hence the longer read timeout than the other clients. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @ClaudeHttpClient
    @Provides
    @Singleton
    fun provideClaudeOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        // See MemoryModule's identical setting: a silent retry after the server already received
        // the request (here, one that may have called save_fact/save_preference/etc.) can re-run a
        // tool call and duplicate the memory row it wrote — surface the failure instead.
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    fun provideAiUsageMeter(@ApplicationContext context: Context): AiUsageMeter = AiUsageMeter(context)

    @Provides
    @Singleton
    fun provideClaudeClient(@ClaudeHttpClient http: OkHttpClient, usageMeter: AiUsageMeter): ClaudeClient =
        ClaudeClient(http = http, usageMeter = usageMeter)

    // Shares ClaudeHttpClient's OkHttpClient rather than a dedicated one — the fallback only ever
    // fires when Claude itself is down, infrequent enough that a second connection pool/timeout
    // config isn't worth the extra binding.
    @Provides
    @Singleton
    fun provideGeminiChatClient(@ClaudeHttpClient http: OkHttpClient): GeminiChatClient = GeminiChatClient(http = http)
}
