package com.unyx.atlas.memory.auth

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SupabaseAuthClientDeleteAccountTest {

    private class Recorder(private val status: Int) : Interceptor {
        var request: Request? = null
        var body: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            request = req
            body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(recorder: Recorder) =
        SupabaseAuthClient(OkHttpClient.Builder().addInterceptor(recorder).build(), "https://example.supabase.co", "anon-key")

    @Test
    fun `posts the explicit confirmation word to the account deletion function as the signed-in user`() {
        val recorder = Recorder(200)

        client(recorder).deleteAccount("user-jwt")

        val request = recorder.request!!
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://example.supabase.co/functions/v1/atlas-account-delete")
        assertThat(request.header("Authorization")).isEqualTo("Bearer user-jwt")
        assertThat(request.header("apikey")).isEqualTo("anon-key")
        assertThat(JSONObject(recorder.body!!).getString("confirm")).isEqualTo("EXCLUIR")
    }

    @Test
    fun `a server rejection surfaces as an IOException so the caller keeps the session`() {
        assertThrows(IOException::class.java) { client(Recorder(502)).deleteAccount("user-jwt") }
        assertThrows(IOException::class.java) { client(Recorder(401)).deleteAccount("user-jwt") }
    }

    @Test
    fun `refuses to call the server without a session token`() {
        val recorder = Recorder(200)

        assertThrows(IllegalArgumentException::class.java) { client(recorder).deleteAccount("") }

        assertThat(recorder.request).isNull()
    }
}
