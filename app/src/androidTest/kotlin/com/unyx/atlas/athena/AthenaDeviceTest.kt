package com.unyx.atlas.athena

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unyx.athena.core.*
import com.unyx.athena.runtime.*
import com.unyx.atlas.LauncherApplication
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AthenaDeviceTest {
    // ATHENA has no screen/plugin of its own (2026-09-30 architecture correction) — real Hilt
    // composition is exercised here via the Application's own injected coordinator, the same one
    // ATLAS's Settings screen reads from, instead of launching a dedicated Athena activity.
    @Test fun coordinatorIsInjectedWithRealHiltComposition() {
        val app = ApplicationProvider.getApplicationContext<LauncherApplication>()
        assertNotNull(app.athenaCoordinator.runtime)
    }

    @Test fun atomicTechnicalCacheSurvivesRuntimeRestartOnAndroid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.noBackupFilesDir, "athena-test-${UUID.randomUUID()}")
        val file = File(directory, "world.json")
        val selector = WorldSelector.Service("device-fixture")
        var calls = 0
        val connector = object : WorldConnector {
            override val descriptor = WorldManifest(AthenaSource("device-fixture", "Fixture", "example.invalid"), Duration.ofMinutes(15))
            override fun accepts(selector: WorldSelector) = selector == WorldSelector.Service("device-fixture")
            override suspend fun fetch(selector: WorldSelector): WorldCandidate {
                calls++
                return WorldCandidate(selector, WorldPayload.Service(ServiceCondition.DEGRADED), Instant.now())
            }
        }
        try {
            val first = WorldRuntime(listOf(connector), FileWorldStore(file), Clock.systemUTC())
            assertTrue(first.query(WorldRequest(selector)) is WorldReply.Success)
            first.subscribe(WorldSubscription("test", selector, Instant.now().plusSeconds(600)))
            val restarted = WorldRuntime(listOf(connector), FileWorldStore(file), Clock.systemUTC())
            assertTrue((restarted.query(WorldRequest(selector)) as WorldReply.Success).fromCache)
            assertEquals(1, calls)
            assertEquals(1, restarted.subscriptions.value.size)
            restarted.cancel("test")
            assertTrue(restarted.poll().isEmpty())
        } finally {
            // Only this test's uniquely named directory; never touch application/user state.
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
