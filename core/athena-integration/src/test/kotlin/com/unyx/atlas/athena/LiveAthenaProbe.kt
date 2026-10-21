package com.unyx.atlas.athena

import com.unyx.athena.core.*
import com.unyx.athena.runtime.*
import com.unyx.athena.status.GitHubStatusConnector
import com.unyx.athena.weather.OpenMeteoConnector
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Manual network verification using a fixed public test region, never device/user location. */
fun main(): Unit = runBlocking {
    val runtime = WorldRuntime(listOf(WeatherWorldConnector(OpenMeteoConnector()), GitHubStatusConnector()))
    var failed = false
    for (selector in listOf(WorldSelector.Weather(GeographicRegion(5148, 0)), GitHubStatusConnector.SELECTOR)) {
        when (val result = runtime.query(WorldRequest(selector, Duration.ZERO, Duration.ofSeconds(20)))) {
            is WorldReply.Success -> println("${result.event.source.connectorId}: ACCEPTED; schema=${result.event.schemaVersion}; fresh=${result.event.fresh(java.time.Instant.now())}")
            is WorldReply.Failure -> { println("${selector.javaClass.simpleName}: ${result.error}"); failed = true }
        }
    }
    // OkHttp executors may remain alive briefly; this is a standalone diagnostic process only.
    exitProcess(if (failed) 1 else 0)
}
