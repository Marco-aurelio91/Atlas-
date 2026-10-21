package com.unyx.athena.status

import com.unyx.athena.core.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Fixed public origin; no user URLs, redirects, cookies, authentication or provider text in errors. */
class GitHubStatusTransport internal constructor(client: OkHttpClient) : StatusTransport {
    constructor() : this(OkHttpClient())
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()
    override suspend fun get(): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(GitHubStatusConnector.URL).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ConnectorException(if (e is InterruptedIOException) AthenaError.TIMEOUT else AthenaError.UNAVAILABLE))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (it.code == 429) throw ConnectorException(AthenaError.RATE_LIMITED, retryAfter(it.header("Retry-After")))
                        if (!it.isSuccessful) throw ConnectorException(if (it.code >= 500) AthenaError.UNAVAILABLE else AthenaError.INVALID_DATA)
                        val body = it.body ?: throw ConnectorException(AthenaError.INVALID_DATA)
                        if (body.contentType()?.subtype != "json" || body.contentLength() > 65536) throw ConnectorException(AthenaError.INVALID_DATA)
                        body.byteStream().use { input ->
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                if (out.size() + n > 65536) throw ConnectorException(AthenaError.INVALID_DATA)
                                out.write(buffer, 0, n)
                            }
                            out.toString(Charsets.UTF_8.name())
                        }
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e as? ConnectorException ?: ConnectorException(if (e is InterruptedIOException) AthenaError.TIMEOUT else AthenaError.UNAVAILABLE))
                }
            }
        })
    }
}
