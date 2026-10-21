package com.unyx.athena.status

import com.unyx.athena.core.*
import java.io.InterruptedIOException
import java.time.Duration
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class StatusTransportTest {
    @Test fun `fixed destination rejects redirect invalid content and excessive decompressed bodies`() = runTest {
        for ((code, content, mime, expected) in listOf(
            Case(302, "{}", "application/json", AthenaError.INVALID_DATA),
            Case(200, "<html>", "text/html", AthenaError.INVALID_DATA),
            Case(200, "x".repeat(65537), "application/json", AthenaError.INVALID_DATA),
            Case(503, "{}", "application/json", AthenaError.UNAVAILABLE),
        )) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals(GitHubStatusConnector.URL, chain.request().url.toString())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .header("Location", "https://example.invalid/").body(content.toResponseBody(mime.toMediaType())).build()
            }.build()
            try { GitHubStatusTransport(client).get(); fail() }
            catch (e: ConnectorException) { assertEquals(expected, e.error) }
            finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            assertEquals(1, calls)
        }
    }

    @Test fun `rate limit retains bounded retry after but never provider body`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("fixture")
                .header("Retry-After", "120").body("private response".toResponseBody()).build()
        }.build()
        try { GitHubStatusTransport(client).get(); fail() }
        catch (e: ConnectorException) {
            assertEquals(AthenaError.RATE_LIMITED, e.error)
            assertEquals(Duration.ofSeconds(120), e.retryAfter)
            assertEquals("RATE_LIMITED", e.message)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `HTTP timeouts keep typed error`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw InterruptedIOException("private endpoint") }.build()
        try { GitHubStatusTransport(client).get(); fail() }
        catch (e: ConnectorException) { assertEquals(AthenaError.TIMEOUT, e.error); assertEquals("TIMEOUT", e.message) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private data class Case(val code: Int, val body: String, val mime: String, val error: AthenaError)
}
