package com.unyx.athena.gateway

import com.unyx.athena.core.*
import com.unyx.athena.runtime.WorldRuntime
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** workerKey is blank in this JVM (no ATHENA_WORKER_KEY env var set for the test run), so the
 * X-Athena-Key check is a no-op here — see Routing.kt's own doc for why. */
class QueryRouteTest {
    private val fixtureRegion = GeographicRegion(-2200, -4500)

    private fun fakeWeatherConnector(temperatureC: Double = 24.0) = object : WorldConnector {
        override val descriptor = WorldManifest(AthenaSource("fixture.weather", "Fixture Weather", "example.invalid"), Duration.ofMinutes(30))
        override fun accepts(selector: WorldSelector) = selector is WorldSelector.Weather
        override suspend fun fetch(selector: WorldSelector): WorldCandidate =
            WorldCandidate(selector, WorldPayload.Weather(WeatherConditions(temperatureC, 1)), Instant.now())
    }

    @Test fun queryReturnsAStructuredAnswerForARealSelector() = testApplication {
        application { module(WorldRuntime(listOf(fakeWeatherConnector()))) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val response = client.post("/v1/query") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(WorldQueryDto.serializer(), WorldQueryDto(
                requestId = "test-1",
                intent = WorldIntentDto.CURRENT_WEATHER,
                location = GeoReferenceDto(fixtureRegion.latitude, fixtureRegion.longitude),
            )))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val answer = response.body<WorldAnswerDto>()
        assertEquals("test-1", answer.requestId)
        assertEquals(AnswerStatusDto.SUCCESS, answer.status)
        assertTrue(answer.facts.any { it.key == "temperatureC" && it.value == "24.0" })
    }

    @Test fun invalidQueryIsRejected() = testApplication {
        application { module(WorldRuntime(listOf(fakeWeatherConnector()))) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val response = client.post("/v1/query") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(WorldQueryDto.serializer(), WorldQueryDto(requestId = "test-2", intent = WorldIntentDto.CURRENT_WEATHER)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(AnswerStatusDto.INVALID_QUERY, response.body<WorldAnswerDto>().status)
    }

    @Test fun healthEndpointReportsOk() = testApplication {
        application { module(WorldRuntime(listOf(fakeWeatherConnector()))) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.body<HealthDto>().status)
    }
}
