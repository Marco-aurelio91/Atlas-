package com.unyx.athena.gateway

import com.unyx.athena.core.GeographicRegion
import com.unyx.athena.core.WorldEvent
import com.unyx.athena.core.WorldPayload
import com.unyx.athena.core.WorldReply
import com.unyx.athena.core.WorldRequest
import com.unyx.athena.core.WorldSelector
import com.unyx.athena.runtime.WorldRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant

fun Application.configureRouting(runtime: WorldRuntime) {
    routing {
        get("/v1/health") {
            call.respond(HealthDto(status = "ok"))
        }
        post("/v1/query") {
            // Skipped only when no key is configured at all (workerKey blank) — i.e. local/test
            // runs that never went through main()'s check. main() itself refuses to start
            // reachable-from-the-internet without one, so production always enforces this.
            if (workerKey.isNotBlank() && call.request.header("X-Athena-Key") != workerKey) {
                return@post call.respond(HttpStatusCode.Unauthorized, errorAnswer("unknown", AnswerStatusDto.UNAUTHORIZED))
            }

            val query = runCatching { call.receive<WorldQueryDto>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, errorAnswer("unknown", AnswerStatusDto.INVALID_QUERY))

            val selector = query.toSelector()
                ?: return@post call.respond(HttpStatusCode.BadRequest, errorAnswer(query.requestId, AnswerStatusDto.INVALID_QUERY))

            when (val reply = runtime.query(WorldRequest(selector))) {
                is WorldReply.Success -> call.respond(reply.event.toAnswer(query.requestId))
                is WorldReply.Failure -> call.respond(errorAnswer(query.requestId, AnswerStatusDto.PROVIDER_UNAVAILABLE))
            }
        }
    }
}

private fun WorldQueryDto.toSelector(): WorldSelector? = when (intent) {
    WorldIntentDto.CURRENT_WEATHER -> location?.let {
        runCatching { WorldSelector.Weather(GeographicRegion((it.latitude * 100).toInt(), (it.longitude * 100).toInt())) }.getOrNull()
    }
    WorldIntentDto.SERVICE_STATUS -> serviceId?.let { runCatching { WorldSelector.Service(it) }.getOrNull() }
}

private fun errorAnswer(requestId: String, status: AnswerStatusDto) = WorldAnswerDto(
    requestId = requestId,
    status = status,
    freshness = "UNKNOWN",
    generatedAt = Instant.now().toString(),
)

private fun WorldEvent.toAnswer(requestId: String): WorldAnswerDto {
    val facts = when (val p = payload) {
        is WorldPayload.Weather -> listOf(
            WorldFactDto("temperatureC", p.conditions.temperatureC.toString()),
            WorldFactDto("weatherCode", p.conditions.weatherCode.toString()),
        )
        is WorldPayload.Service -> listOf(WorldFactDto("condition", p.condition.name))
        is WorldPayload.SustainedCold -> emptyList()
    }
    return WorldAnswerDto(
        requestId = requestId,
        status = AnswerStatusDto.SUCCESS,
        facts = facts,
        confidence = confidence?.toDouble(),
        freshness = if (fresh(Instant.now())) "FRESH" else "STALE",
        evidence = listOf(EvidenceDto(source.connectorId, source.provider, observedAt.toString())),
        generatedAt = Instant.now().toString(),
    )
}
