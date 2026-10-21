package com.unyx.atlas.feature.chat

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Opens the Credential Manager account picker and returns a Google ID token, or null if the user
 * cancels or the request fails — the caller ([ChatViewModel.signInWithGoogle]) treats that the
 * same as any other sign-in failure. Needs an Activity context (not applicationContext), since
 * Credential Manager attaches its bottom-sheet UI to the calling Activity.
 */
// FUTURE: Re-enable the login button after Google OAuth is configured in Google Cloud and Supabase.
suspend fun requestGoogleIdToken(activityContext: Context, serverClientId: String): String? {
    require(serverClientId.isNotBlank()) { "Google Web Client ID is not configured" }
    val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    return try {
        val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
        GoogleIdTokenCredential.createFrom(response.credential.data).idToken
    } catch (_: GetCredentialException) {
        null
    }
}
