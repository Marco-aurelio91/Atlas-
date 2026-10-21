package com.unyx.atlas.di

import com.unyx.athena.core.AtlasAthenaProtocol
import com.unyx.athena.runtime.AthenaRuntime
import com.unyx.athena.weather.OpenMeteoConnector
import com.unyx.atlas.athena.AtlasAthenaBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.unyx.athena.core.WorldProtocol
import com.unyx.athena.runtime.WorldRuntime
import com.unyx.athena.runtime.FileWorldStore
import com.unyx.athena.runtime.WeatherWorldConnector
import com.unyx.athena.status.GitHubStatusConnector
import com.unyx.atlas.athena.WorldWeatherProtocol
import java.io.File

/** ATLAS composition root. Nothing in the ATHENA modules depends back on this Android module. */
@Module
@InstallIn(SingletonComponent::class)
object AthenaModule {
    @Provides
    @Singleton
    fun provideWorldRuntime(@ApplicationContext context: Context): WorldRuntime = WorldRuntime(
        listOf(WeatherWorldConnector(OpenMeteoConnector()), GitHubStatusConnector()),
        FileWorldStore(File(context.noBackupFilesDir, "athena/world-v2.json")),
    )

    @Provides
    fun provideWorldProtocol(runtime: WorldRuntime): WorldProtocol = runtime

    @Provides
    @Singleton
    fun provideAthenaProtocol(world: WorldProtocol): AtlasAthenaProtocol = WorldWeatherProtocol(world)

    @Provides
    @Singleton
    fun provideAthenaBridge(protocol: AtlasAthenaProtocol): AtlasAthenaBridge = AtlasAthenaBridge(protocol)
}
