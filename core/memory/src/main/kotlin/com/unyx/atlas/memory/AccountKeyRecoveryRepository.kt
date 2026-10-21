package com.unyx.atlas.memory

import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.security.RecoveryEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RecoveryEnvelopeAlreadyExistsException : IllegalStateException(
    "An account recovery envelope already exists; restore the existing key instead of replacing it",
)

/** Access to the account's encrypted key-recovery envelope. RLS scopes every operation to auth.uid(). */
class AccountKeyRecoveryRepository(
    private val client: SupabaseRestClient,
    private val auth: AuthRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun publish(userId: String, envelope: RecoveryEnvelope): Result<Unit> = withContext(ioDispatcher) {
        try {
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            val accessToken = auth.currentAccessToken() ?: error("Sign in is required")
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            val row = JSONObject()
                    .put("user_id", userId)
                    .put("key_id", envelope.keyId)
                    .put("crypto_version", envelope.version)
                    .put("envelope", envelope.toJson())
            try {
                client.insert(TABLE, row, accessToken)
                Result.success(Unit)
            } catch (insertFailure: Exception) {
                // INSERT is intentionally non-upserting: a new device must not replace the only
                // recovery envelope for an existing account. If the server accepted our previous
                // request but its response was lost, recognize the exact same envelope as success.
                val existing = runCatching {
                    val rows = client.select(TABLE, query = "limit=1", accessToken = accessToken)
                    if (rows.length() == 0) null else RecoveryEnvelope.fromJson(rows.getJSONObject(0).getJSONObject("envelope"))
                }.getOrNull()
                when {
                    existing == envelope -> Result.success(Unit)
                    existing != null -> Result.failure(RecoveryEnvelopeAlreadyExistsException())
                    else -> Result.failure(insertFailure)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun fetch(userId: String): Result<RecoveryEnvelope?> = withContext(ioDispatcher) {
        try {
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            val accessToken = auth.currentAccessToken() ?: error("Sign in is required")
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            val rows = client.select(TABLE, query = "limit=1", accessToken = accessToken)
            if (rows.length() == 0) return@withContext Result.success(null)
            val row = rows.getJSONObject(0)
            val envelope = RecoveryEnvelope.fromJson(row.getJSONObject("envelope"))
            check(row.getString("key_id") == envelope.keyId) { "Recovery envelope key id does not match its row" }
            check(row.getInt("crypto_version") == envelope.version) { "Recovery envelope version does not match its row" }
            Result.success(envelope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private companion object {
        const val TABLE = "atlas_key_recovery_envelopes"
    }
}
