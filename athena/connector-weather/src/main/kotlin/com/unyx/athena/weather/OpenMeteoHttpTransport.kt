package com.unyx.athena.weather

import com.unyx.athena.core.AthenaError
import com.unyx.athena.core.ConnectorException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Fixed HTTPS origin, no redirects, bounded decompressed body and cancellation of the actual call. */
class OpenMeteoHttpTransport internal constructor(client: OkHttpClient) : WeatherTransport {
    constructor() : this(OkHttpClient())

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String): String {
        val address = url.toHttpUrlOrNull()
        if (address == null || address.scheme != "https" || address.host != OpenMeteoConnector.HOST ||
            address.port != 443 || address.username.isNotEmpty() || address.password.isNotEmpty() ||
            address.encodedPath != "/v1/forecast"
        ) throw ConnectorException(AthenaError.INVALID_DATA)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(address).header("Accept", "application/json").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        ConnectorException(if (e is InterruptedIOException) AthenaError.TIMEOUT else AthenaError.UNAVAILABLE),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 429) throw ConnectorException(AthenaError.RATE_LIMITED,
                                com.unyx.athena.core.retryAfter(it.header("Retry-After")))
                            if (!it.isSuccessful) throw ConnectorException(AthenaError.UNAVAILABLE)
                            val body = it.body ?: throw ConnectorException(AthenaError.INVALID_DATA)
                            if (body.contentType()?.subtype != "json") throw ConnectorException(AthenaError.INVALID_DATA)
                            if (body.contentLength() > OpenMeteoConnector.MAX_RESPONSE_BYTES) {
                                throw ConnectorException(AthenaError.INVALID_DATA)
                            }
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > OpenMeteoConnector.MAX_RESPONSE_BYTES) {
                                        throw ConnectorException(AthenaError.INVALID_DATA)
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            e as? ConnectorException ?: ConnectorException(
                                if (e is InterruptedIOException) AthenaError.TIMEOUT else AthenaError.UNAVAILABLE,
                            ),
                        )
                    }
                }
            })
        }
    }
}
