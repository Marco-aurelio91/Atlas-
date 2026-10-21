package com.unyx.athena.gateway

import com.unyx.athena.runtime.WorldRuntime
import com.unyx.athena.runtime.WeatherWorldConnector
import com.unyx.athena.status.GitHubStatusConnector
import com.unyx.athena.weather.OpenMeteoConnector
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/**
 * A supplementary local worker for ATHENA (docs/ATHENA_PLANO_CRIACAO_BACKEND_SUPABASE.md §78) —
 * Supabase's `athena-query` stays the stable contract ATLAS knows; this process is reached only
 * from that Edge Function (or, while there's no real caller yet, tested directly), never called by
 * the Android app itself. Exposed to the internet via Cloudflare Tunnel, so every request must
 * carry the shared secret in ATHENA_WORKER_KEY — this process has no other authentication of its
 * own (no Supabase JWT applies here, this isn't a Supabase service).
 */
val workerKey: String = System.getenv("ATHENA_WORKER_KEY").orEmpty()

fun main() {
    require(workerKey.isNotBlank()) { "ATHENA_WORKER_KEY must be set — this process is reachable from the internet." }
    val runtime = buildRuntime()
    // Loopback only: the tunnel process is on this same machine and already targets localhost:8080.
    // Nothing else on the LAN needs a path to this port.
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
        module(runtime)
    }.start(wait = true)
}

fun buildRuntime(): WorldRuntime = WorldRuntime(
    connectors = listOf(WeatherWorldConnector(OpenMeteoConnector()), GitHubStatusConnector()),
)

fun Application.module(runtime: WorldRuntime) {
    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respondText(status = HttpStatusCode.BadRequest, text = "invalid_query")
        }
    }
    configureRouting(runtime)
}
