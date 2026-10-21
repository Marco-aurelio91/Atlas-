package com.unyx.atlas.memory.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Where Supabase should send the user after they tap the confirmation link in their email —
 * must be registered in the project's Auth > URL Configuration > Redirect URLs allow-list. */
const val AUTH_CALLBACK_REDIRECT = "atlas://auth-callback"

/** Thrown by [SupabaseAuthClient.refresh] only when Supabase explicitly rejected the refresh
 * token (expired/revoked/reused) — the one case where [AuthRepository] should actually sign the
 * user out. Any other failure (network error, timeout, 5xx) is transient and must leave the
 * stored session alone. */
class AuthSessionExpiredException(message: String) : IOException(message)

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String?,
    /** Epoch seconds. */
    val expiresAt: Long,
)

data class AuthMfaFactor(
    val id: String,
    val type: String,
    val status: String,
    val friendlyName: String?,
)

/** The TOTP secret is returned only for an in-memory enrollment flow and is never persisted. */
data class TotpEnrollment(
    val factorId: String,
    val secret: String,
    val uri: String,
    val qrCodeSvg: String,
) {
    override fun toString(): String = "TotpEnrollment(factorId=$factorId, secret=[REDACTED], uri=[REDACTED], qrCodeSvg=[REDACTED])"
}

sealed interface SignUpResult {
    /** Project has `mailer_autoconfirm` on, or the email was already confirmed — session is ready immediately. */
    data class SignedIn(val session: AuthSession) : SignUpResult
    /** The normal case with email confirmation required: nothing usable yet until the user taps the emailed link. */
    data object ConfirmationEmailSent : SignUpResult
}

/**
 * Minimal Supabase Auth (GoTrue) REST client — same lightweight OkHttp + org.json style as
 * [com.unyx.atlas.memory.SupabaseRestClient]. Email/password only for now; Supabase supports much
 * more (OAuth, phone OTP, magic links) but this is a personal single-user app, not a public
 * product yet.
 */
class SupabaseAuthClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    fun signUp(email: String, password: String): SignUpResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("options", JSONObject().put("email_redirect_to", AUTH_CALLBACK_REDIRECT))
        val json = callAuthEndpoint("$baseUrl/auth/v1/signup", body)
        return if (json.has("access_token") && !json.isNull("access_token")) {
            SignUpResult.SignedIn(json.toAuthSession())
        } else {
            SignUpResult.ConfirmationEmailSent
        }
    }

    fun signIn(email: String, password: String): AuthSession {
        val body = JSONObject().put("email", email).put("password", password)
        return callAuthEndpoint("$baseUrl/auth/v1/token?grant_type=password", body).toAuthSession()
    }

    /** Exchanges a Google ID token (from Android Credential Manager) for a Supabase session —
     * same shape as [signIn], just a different grant type. Supabase itself verifies the token's
     * signature and audience against the Google provider config; this class trusts that check. */
    fun signInWithIdToken(idToken: String): AuthSession {
        val body = JSONObject().put("provider", "google").put("id_token", idToken)
        return callAuthEndpoint("$baseUrl/auth/v1/token?grant_type=id_token", body).toAuthSession()
    }

    fun refresh(refreshToken: String): AuthSession {
        val body = JSONObject().put("refresh_token", refreshToken)
        return callAuthEndpoint(
            "$baseUrl/auth/v1/token?grant_type=refresh_token",
            body,
            rejectionIsSessionExpired = true,
        ).toAuthSession()
    }

    fun listMfaFactors(accessToken: String): List<AuthMfaFactor> {
        // GoTrue exposes the signed-in user's enrolled factors from GET /user.
        // The /factors collection only accepts POST (enrollment); GET there returns 405.
        val json = authenticatedRequest("$baseUrl/auth/v1/user", accessToken, "GET")
        val factors = json.optJSONArray("factors")
        return buildList {
            if (factors != null) {
                for (index in 0 until factors.length()) {
                    val factor = factors.optJSONObject(index) ?: continue
                    val id = factor.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        AuthMfaFactor(
                            id = id,
                            type = factor.optString("factor_type").ifBlank { factor.optString("type", "totp") },
                            status = factor.optString("status"),
                            friendlyName = factor.optString("friendly_name").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.distinctBy(AuthMfaFactor::id)
    }

    fun beginTotpEnrollment(accessToken: String): TotpEnrollment {
        val body = JSONObject()
            .put("factor_type", "totp")
            .put("friendly_name", "Atlas Android")
            .put("issuer", "Atlas")
        val json = authenticatedRequest("$baseUrl/auth/v1/factors", accessToken, "POST", body)
        val totp = json.optJSONObject("totp") ?: throw IOException("Supabase Auth did not return TOTP setup data")
        val factorId = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw IOException("Supabase Auth did not return a factor id")
        val secret = totp.optString("secret").takeIf { it.isNotBlank() }
            ?: throw IOException("Supabase Auth did not return a TOTP secret")
        return TotpEnrollment(
            factorId = factorId,
            secret = secret,
            uri = totp.optString("uri"),
            qrCodeSvg = totp.optString("qr_code"),
        )
    }

    fun challengeMfa(accessToken: String, factorId: String): String {
        val json = authenticatedRequest(
            "$baseUrl/auth/v1/factors/${encodePathSegment(factorId)}/challenge",
            accessToken,
            "POST",
            JSONObject(),
        )
        return json.optString("id").takeIf { it.isNotBlank() }
            ?: throw IOException("Supabase Auth did not return an MFA challenge id")
    }

    fun verifyMfa(accessToken: String, factorId: String, challengeId: String, code: String): AuthSession {
        val json = authenticatedRequest(
            "$baseUrl/auth/v1/factors/${encodePathSegment(factorId)}/verify",
            accessToken,
            "POST",
            JSONObject().put("challenge_id", challengeId).put("code", code),
        )
        return json.toAuthSession()
    }

    fun unenrollMfaFactor(accessToken: String, factorId: String) {
        authenticatedRequest(
            "$baseUrl/auth/v1/factors/${encodePathSegment(factorId)}",
            accessToken,
            "DELETE",
        )
    }

    fun signOut(accessToken: String) {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/logout")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $accessToken")
            .post("".toRequestBody())
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    /**
     * Parses the URL fragment Supabase redirects to after the user taps the confirmation link —
     * `atlas://auth-callback#access_token=...&refresh_token=...&expires_in=...&token_type=bearer`.
     * The tokens are already valid at that point; no further network call is needed to "exchange"
     * them (unlike an OAuth authorization-code flow).
     */
    fun parseCallback(fragment: String): AuthSession? {
        val params = fragment.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
        val accessToken = params["access_token"] ?: return null
        val refreshToken = params["refresh_token"] ?: return null
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L
        val userId = decodeJwtSubject(accessToken) ?: return null
        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = null, // not carried in the fragment; fetched separately via currentUser() if needed
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn,
        )
    }

    /** Fetches the authenticated user's profile (mainly for the email, which the callback fragment doesn't carry). */
    fun currentUser(accessToken: String): Pair<String, String?> {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $accessToken")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Supabase Auth /user HTTP ${resp.code}")
            val json = JSONObject(text)
            return json.getString("id") to json.optString("email").takeIf { it.isNotBlank() }
        }
    }

    private fun callAuthEndpoint(
        url: String,
        body: JSONObject,
        rejectionIsSessionExpired: Boolean = false,
    ): JSONObject {
        require(isConfigured) { "Supabase Auth isn't configured (missing URL/key) — see supabase.properties" }
        val req = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val message = runCatching {
                    val err = JSONObject(text)
                    err.optString("msg").ifBlank { err.optString("error_description") }.ifBlank { err.optString("message") }
                }.getOrNull()
                val fullMessage = "Supabase Auth HTTP ${resp.code}${if (!message.isNullOrBlank()) ": $message" else ""}"
                // A 4xx here means Supabase itself rejected the refresh token (expired/revoked/reused)
                // — that's a real sign-out. A 5xx or a network failure elsewhere (see
                // AuthRepository.currentAccessToken) is a transient problem and must NOT drop the
                // session, or a brief server hiccup would force the user to log back in.
                if (rejectionIsSessionExpired && resp.code in 400..499) {
                    throw AuthSessionExpiredException(fullMessage)
                }
                throw IOException(fullMessage)
            }
            return JSONObject(text)
        }
    }

    private fun authenticatedRequest(
        url: String,
        accessToken: String,
        method: String,
        body: JSONObject? = null,
    ): JSONObject {
        require(isConfigured) { "Supabase Auth isn't configured (missing URL/key) — see supabase.properties" }
        require(accessToken.isNotBlank()) { "An authenticated session is required" }
        val builder = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $accessToken")
        if (body != null) builder.header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> error("Unsupported Auth method")
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val error = JSONObject(text)
                    error.optString("msg")
                        .ifBlank { error.optString("error_description") }
                        .ifBlank { error.optString("message") }
                }.getOrNull()
                throw IOException("Supabase MFA HTTP ${response.code}${if (!message.isNullOrBlank()) ": $message" else ""}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun JSONObject.toAuthSession(): AuthSession {
        val user = getJSONObject("user")
        return AuthSession(
            accessToken = getString("access_token"),
            refreshToken = getString("refresh_token"),
            userId = user.getString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() },
            expiresAt = System.currentTimeMillis() / 1000 + optLong("expires_in", 3600),
        )
    }
}

private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Pulls the `sub` claim (the user's id) out of a JWT without verifying its signature — fine here
 * since the token just arrived directly from Supabase's own redirect over HTTPS; nothing untrusted
 * is being trusted based on this decode. */
private fun decodeJwtSubject(jwt: String): String? {
    val parts = jwt.split(".")
    if (parts.size < 2) return null
    return runCatching {
        val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        JSONObject(payload).getString("sub")
    }.getOrNull()
}
