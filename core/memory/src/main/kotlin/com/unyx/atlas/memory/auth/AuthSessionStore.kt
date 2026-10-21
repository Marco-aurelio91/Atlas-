package com.unyx.atlas.memory.auth

import android.content.Context
import android.content.SharedPreferences
import com.unyx.atlas.security.AtlasSecurePreferences

/**
 * Persists the current auth session across app restarts. A refresh token is effectively a
 * long-lived credential, so this is backed by encrypted preferences (AES-256, key held in the
 * Android Keystore). If the Keystore is unavailable, the session stays in process memory.
 */
class AuthSessionStore(context: Context) {

    private val prefs: SharedPreferences = AtlasSecurePreferences.open(
        context = context,
        secureName = "atlas_auth_secure",
        legacyPlainName = "atlas_auth",
    ).preferences

    fun save(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .apply()
    }

    fun load(): AuthSession? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = prefs.getString(KEY_EMAIL, null),
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
