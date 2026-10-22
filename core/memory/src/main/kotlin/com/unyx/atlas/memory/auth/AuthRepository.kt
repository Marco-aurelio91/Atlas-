package com.unyx.atlas.memory.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

sealed interface AuthState {
    data object LoggedOut : AuthState
    data class ConfirmationPending(val email: String) : AuthState
    data class MfaRequired(val email: String?, val factorId: String) : AuthState
    data class LoggedIn(val userId: String, val email: String?) : AuthState
}

/**
 * The one entry point the chat UI (and, later, anything needing to attach the right token to a
 * memory request) talks to. Holds the current [AuthState] as a hot flow so a login screen and a
 * chat screen sharing this instance stay in sync automatically.
 */
class AuthRepository(
    private val client: SupabaseAuthClient,
    private val store: AuthSessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var session: AuthSession? = null
    /** Holds the short-lived AAL1 session only in memory until the TOTP challenge succeeds. */
    private var pendingMfaSession: AuthSession? = null
    private val refreshMutex = Mutex()

    init {
        store.load()?.let {
            session = it
            _state.value = AuthState.LoggedIn(it.userId, it.email)
        }
    }

    /** The current access token, refreshing first if it's expired or about to be. Null if logged
     * out, or if the refresh attempt failed. Only a refresh Supabase itself explicitly rejected
     * (expired/revoked/reused token) actually signs the user out — a transient failure (no
     * connectivity, a timeout, a 5xx from Supabase) leaves the stored session in place so the next
     * attempt can simply try again instead of forcing a fresh login. */
    suspend fun currentAccessToken(): String? = withContext(ioDispatcher) {
        refreshMutex.withLock {
            val current = session ?: return@withLock null
            val soon = System.currentTimeMillis() / 1000 + 60
            if (current.expiresAt > soon) return@withLock current.accessToken
            val refreshed = try {
                client.refresh(current.refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: AuthSessionExpiredException) {
                if (session == current) signOutLocally()
                return@withLock null
            } catch (_: Exception) {
                return@withLock null
            }
            session = refreshed
            store.save(refreshed)
            refreshed.accessToken
        }
    }

    fun currentUserId(): String? = session?.userId

    suspend fun signUp(email: String, password: String): Result<SignUpResult> = withContext(ioDispatcher) {
        runCatching {
            val result = client.signUp(email, password)
            when (result) {
                is SignUpResult.SignedIn -> {
                    finishSignIn(result.session)
                }
                SignUpResult.ConfirmationEmailSent -> {
                    _state.value = AuthState.ConfirmationPending(email)
                }
            }
            result
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val result = client.signIn(email, password)
            finishSignIn(result)
        }
    }

    /** FUTURE: exchanges the Google Credential Manager ID token for the same session as email login. */
    suspend fun signInWithGoogle(idToken: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val result = client.signInWithIdToken(idToken)
            finishSignIn(result)
        }
    }

    suspend fun verifyPendingMfa(code: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val pending = requireNotNull(pendingMfaSession) { "No MFA challenge is pending" }
            val required = _state.value as? AuthState.MfaRequired
                ?: error("No MFA challenge is pending")
            require(code.filter(Char::isDigit).length == 6) { "Enter the six-digit authenticator code" }
            val challengeId = client.challengeMfa(pending.accessToken, required.factorId)
            val verified = client.verifyMfa(
                accessToken = pending.accessToken,
                factorId = required.factorId,
                challengeId = challengeId,
                code = code.filter(Char::isDigit),
            )
            pendingMfaSession = null
            saveAuthenticatedSession(verified)
        }
    }

    suspend fun cancelPendingMfa() = withContext(ioDispatcher) {
        pendingMfaSession?.let { runCatching { client.signOut(it.accessToken) } }
        pendingMfaSession = null
        session = null
        store.clear()
        _state.value = AuthState.LoggedOut
    }

    suspend fun mfaFactors(): Result<List<AuthMfaFactor>> = withContext(ioDispatcher) {
        runCatching {
            val token = currentAccessToken() ?: error("A signed-in session is required")
            client.listMfaFactors(token)
        }
    }

    suspend fun beginTotpEnrollment(): Result<TotpEnrollment> = withContext(ioDispatcher) {
        runCatching {
            val token = currentAccessToken() ?: error("A signed-in session is required")
            client.beginTotpEnrollment(token)
        }
    }

    suspend fun verifyTotpEnrollment(enrollment: TotpEnrollment, code: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            require(code.filter(Char::isDigit).length == 6) { "Enter the six-digit authenticator code" }
            val token = currentAccessToken() ?: error("A signed-in session is required")
            val challengeId = client.challengeMfa(token, enrollment.factorId)
            val verified = client.verifyMfa(
                accessToken = token,
                factorId = enrollment.factorId,
                challengeId = challengeId,
                code = code.filter(Char::isDigit),
            )
            saveAuthenticatedSession(verified)
        }
    }

    suspend fun removeMfaFactor(factorId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val token = currentAccessToken() ?: error("A signed-in session is required")
            client.unenrollMfaFactor(token, factorId)
        }
    }

    /** Called from the deep-link handler once Supabase redirects back after the user confirms their email. */
    suspend fun completeEmailConfirmation(callbackFragment: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val parsed = client.parseCallback(callbackFragment) ?: error("Link de confirmação inválido ou expirado")
            val (userId, email) = runCatching { client.currentUser(parsed.accessToken) }
                .getOrDefault(parsed.userId to parsed.email)
            val complete = parsed.copy(userId = userId, email = email)
            finishSignIn(complete)
        }
    }

    suspend fun signOut() = withContext(ioDispatcher) {
        session?.let { runCatching { client.signOut(it.accessToken) } }
        pendingMfaSession?.let { runCatching { client.signOut(it.accessToken) } }
        refreshMutex.withLock { signOutLocally() }
    }

    /**
     * Deletes the account on the server, then signs out locally. Local state is only cleared after
     * the server confirmed — a failed or offline attempt leaves the session intact so the user can
     * retry instead of being logged out of an account that still exists.
     */
    suspend fun deleteAccount(): Result<Unit> = withContext(ioDispatcher) {
        val token = currentAccessToken() ?: return@withContext Result.failure(IllegalStateException("Sem sessão ativa."))
        try {
            client.deleteAccount(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext Result.failure(failure)
        }
        refreshMutex.withLock { signOutLocally() }
        Result.success(Unit)
    }

    private fun finishSignIn(authSession: AuthSession) {
        // Fail closed if Auth cannot enumerate factors. Otherwise a transient Auth error could
        // silently bypass MFA and persist an AAL1 session when the account has TOTP enabled.
        val verifiedFactor = client.listMfaFactors(authSession.accessToken)
            .firstOrNull { it.status.equals("verified", ignoreCase = true) }
        if (verifiedFactor != null) {
            session = null
            store.clear()
            pendingMfaSession = authSession
            _state.value = AuthState.MfaRequired(authSession.email, verifiedFactor.id)
        } else {
            pendingMfaSession = null
            saveAuthenticatedSession(authSession)
        }
    }

    private fun saveAuthenticatedSession(authSession: AuthSession) {
        session = authSession
        pendingMfaSession = null
        store.save(authSession)
        _state.value = AuthState.LoggedIn(authSession.userId, authSession.email)
    }

    private fun signOutLocally() {
        session = null
        pendingMfaSession = null
        store.clear()
        _state.value = AuthState.LoggedOut
    }
}
