package com.unyx.atlas.memory.di

import android.content.Context
import com.unyx.atlas.memory.BuildConfig
import com.unyx.atlas.memory.AccountKeyRecoveryRepository
import com.unyx.atlas.memory.AtlasEncryptedObjectRepository
import com.unyx.atlas.memory.AiResponseReporter
import com.unyx.atlas.memory.EncryptedMemoryStore
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.SupabaseRestClient
import com.unyx.atlas.memory.athena.AthenaGateway
import com.unyx.atlas.memory.athena.AthenaGatewayImpl
import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.memory.auth.AuthSessionStore
import com.unyx.atlas.memory.auth.SupabaseAuthClient
import com.unyx.atlas.security.AtlasAccountKeyManager
import com.unyx.atlas.security.AtlasSecurity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Other feature modules (e.g. feature:updater) also provide an unqualified OkHttpClient into
 * SingletonComponent — an unqualified one here would be a duplicate Hilt binding the moment both
 * modules are on the app's classpath together. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MemoryHttpClient

@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    @MemoryHttpClient
    @Provides
    @Singleton
    fun provideMemoryOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // OkHttp's default silently retries a request after a connection failure — harmless for
        // reads, but for insert()/update() (POST/PATCH, not idempotent) a dropped response after
        // the server already applied the write turns into a second, duplicate row. Better to
        // surface the failure to the caller (already handled via runCatching) than to double-write.
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    fun provideSupabaseRestClient(@MemoryHttpClient http: OkHttpClient): SupabaseRestClient = SupabaseRestClient(
        http = http,
        baseUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    @Provides
    @Singleton
    fun provideSupabaseAuthClient(@MemoryHttpClient http: OkHttpClient): SupabaseAuthClient = SupabaseAuthClient(
        http = http,
        baseUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    @Provides
    @Singleton
    fun provideAuthSessionStore(@ApplicationContext context: Context): AuthSessionStore = AuthSessionStore(context)

    @Provides
    @Singleton
    fun provideAuthRepository(client: SupabaseAuthClient, store: AuthSessionStore): AuthRepository =
        AuthRepository(client, store)

    @Provides
    @Singleton
    fun provideAtlasSecurity(): AtlasSecurity = AtlasSecurity()

    @Provides
    @Singleton
    fun provideAtlasAccountKeyManager(
        @ApplicationContext context: Context,
        security: AtlasSecurity,
    ): AtlasAccountKeyManager = AtlasAccountKeyManager(context, security)

    @Provides
    @Singleton
    fun provideAccountKeyRecoveryRepository(
        client: SupabaseRestClient,
        auth: AuthRepository,
    ): AccountKeyRecoveryRepository = AccountKeyRecoveryRepository(client, auth)

    @Provides
    @Singleton
    fun provideAtlasEncryptedObjectRepository(
        client: SupabaseRestClient,
        auth: AuthRepository,
        keys: AtlasAccountKeyManager,
    ): AtlasEncryptedObjectRepository = AtlasEncryptedObjectRepository(client, auth, keys)

    @Provides
    @Singleton
    fun provideEncryptedMemoryStore(
        objects: AtlasEncryptedObjectRepository,
        client: SupabaseRestClient,
        auth: AuthRepository,
    ): EncryptedMemoryStore = EncryptedMemoryStore(objects, client, auth)

    @Provides
    @Singleton
    fun provideAthenaGateway(@MemoryHttpClient http: OkHttpClient, auth: AuthRepository): AthenaGateway = AthenaGatewayImpl(
        http = http,
        baseUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_ANON_KEY,
        auth = auth,
    )

    @Provides
    @Singleton
    fun provideAiResponseReporter(
        client: SupabaseRestClient,
        auth: AuthRepository,
    ): AiResponseReporter = AiResponseReporter(client, auth)

    @Provides
    @Singleton
    fun provideMemoryRepository(
        secureStore: EncryptedMemoryStore,
        auth: AuthRepository,
    ): MemoryRepository = MemoryRepository(secureStore, auth)
}
