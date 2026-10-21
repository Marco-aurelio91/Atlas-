package com.unyx.atlas.security

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.MessageDigest.isEqual
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID

enum class AccountKeyState { NOT_CONFIGURED, SETUP_PENDING, ACTIVE }

/** Thrown by [AtlasAccountKeyManager.encryptObject]/[decryptObject] when this device has no active
 * account root key yet — every note/memory save routes through one of those, so until the user
 * finishes the one-time setup (Configurações → Segurança do Atlas) all of them fail with this. A
 * distinct type (not a bare [IllegalStateException]) so callers can show an actionable message
 * instead of leaking the raw English detail string into the UI. */
class AccountKeyNotActiveException : IllegalStateException(
    "A proteção da conta ainda não foi ativada neste aparelho. Abra Configurações → Segurança do " +
        "Atlas e conclua a configuração da chave para poder salvar notas e memórias.",
)

data class AccountKeyStatus(
    val state: AccountKeyState,
    val persistentStorageAvailable: Boolean,
)

/** Returned only while the recovery code is being shown and confirmed. Never log or persist it. */
data class AccountKeySetup(
    val recoveryCode: String,
    val recoveryEnvelope: RecoveryEnvelope,
) {
    override fun toString(): String =
        "AccountKeySetup(recoveryCode=[REDACTED], keyId=${recoveryEnvelope.keyId})"
}

/**
 * Owns an account root key (ARK) on this device. The persisted copy is wrapped by the device
 * Keystore key; the cloud copy is only the recovery-code-encrypted envelope. No plaintext ARK or
 * recovery code is written to preferences, logs, files, or the database.
 */
class AtlasAccountKeyManager(
    context: Context,
    private val crypto: AtlasSecurity = AtlasSecurity(),
) {
    private val appContext = context.applicationContext

    @Synchronized
    fun status(userId: String): AccountKeyStatus {
        val secure = preferences(userId)
        val state = when (secure.preferences.getString(KEY_STATE, null)) {
            STATE_PENDING -> AccountKeyState.SETUP_PENDING
            STATE_ACTIVE -> AccountKeyState.ACTIVE
            else -> AccountKeyState.NOT_CONFIGURED
        }
        return AccountKeyStatus(state, secure.persistent)
    }

    /** Starts or replaces an unfinished setup. Active account keys are never silently replaced. */
    @Synchronized
    fun beginSetup(userId: String): AccountKeySetup {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val secure = preferences(userId)
        requirePersistent(secure)
        val prefs = secure.preferences
        check(prefs.getString(KEY_STATE, null) != STATE_ACTIVE) { "An account key is already active" }

        val rootKey = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val (recoveryCode, recoveryCodeBytes) = AtlasRecoveryEnvelopeCrypto.newRecoveryCode()
        val keyId = UUID.randomUUID().toString()
        try {
            val context = rootKeyContext(userId, keyId)
            val localEnvelope = crypto.encrypt(rootKey, context)
            val recoveryEnvelope = AtlasRecoveryEnvelopeCrypto.wrapAccountRootKey(
                accountRootKey = rootKey,
                recoveryCodeBytes = recoveryCodeBytes,
                userId = userId,
                keyId = keyId,
            )
            val saved = prefs.edit()
                .putString(KEY_STATE, STATE_PENDING)
                .putString(KEY_ID, keyId)
                .putString(KEY_LOCAL_ENVELOPE, localEnvelope.toJson().toString())
                .putString(KEY_RECOVERY_ENVELOPE, recoveryEnvelope.toJson().toString())
                .commit()
            check(saved) { "Could not store the protected account key on this device" }
            return AccountKeySetup(recoveryCode, recoveryEnvelope)
        } finally {
            Arrays.fill(rootKey, 0)
            Arrays.fill(recoveryCodeBytes, 0)
        }
    }

    /** Call only after the matching recovery envelope has been saved to the authenticated account. */
    @Synchronized
    fun confirmRecoveryCode(userId: String, recoveryCode: String, recoveryEnvelopeUploaded: Boolean) {
        check(recoveryEnvelopeUploaded) { "Upload the recovery envelope before activating the key" }
        val secure = preferences(userId)
        requirePersistent(secure)
        val prefs = secure.preferences
        check(prefs.getString(KEY_STATE, null) == STATE_PENDING) { "There is no pending account-key setup" }
        verifyRecoveryCode(userId, recoveryCode)
        check(prefs.edit().putString(KEY_STATE, STATE_ACTIVE).commit()) {
            "Could not activate the protected account key"
        }
    }

    /** Verifies the user's saved recovery code without activating or uploading the key. */
    @Synchronized
    fun verifyRecoveryCode(userId: String, recoveryCode: String) {
        val secure = preferences(userId)
        requirePersistent(secure)
        val prefs = secure.preferences
        check(prefs.getString(KEY_STATE, null) == STATE_PENDING) { "There is no pending account-key setup" }
        val keyId = requireNotNull(prefs.getString(KEY_ID, null))
        val recoveryEnvelope = pendingRecoveryEnvelope(userId)
        check(recoveryEnvelope.keyId == keyId) { "Pending recovery envelope does not match the local key" }
        val codeBytes = AtlasRecoveryEnvelopeCrypto.parseRecoveryCode(recoveryCode)
        var localRootKey: ByteArray? = null
        var recoveredRootKey: ByteArray? = null
        try {
            localRootKey = decryptLocalRootKey(userId, keyId, prefs)
            recoveredRootKey = AtlasRecoveryEnvelopeCrypto.unwrapAccountRootKey(recoveryEnvelope, codeBytes, userId)
            check(isEqual(requireNotNull(localRootKey), requireNotNull(recoveredRootKey))) { "Recovery code did not match" }
        } finally {
            Arrays.fill(codeBytes, 0)
            localRootKey?.let { Arrays.fill(it, 0) }
            recoveredRootKey?.let { Arrays.fill(it, 0) }
        }
    }

    /**
     * Restores the account key on a new device. The server-provided recovery envelope must be
     * authenticated by the caller through Supabase RLS before it is passed here.
     */
    @Synchronized
    fun restoreFromRecovery(userId: String, recoveryCode: String, envelope: RecoveryEnvelope) {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val secure = preferences(userId)
        requirePersistent(secure)
        val prefs = secure.preferences
        check(prefs.getString(KEY_STATE, null) != STATE_ACTIVE) { "An account key is already active on this device" }
        val codeBytes = AtlasRecoveryEnvelopeCrypto.parseRecoveryCode(recoveryCode)
        val rootKey = try {
            AtlasRecoveryEnvelopeCrypto.unwrapAccountRootKey(envelope, codeBytes, userId)
        } finally {
            Arrays.fill(codeBytes, 0)
        }
        try {
            UUID.fromString(envelope.keyId)
            val localEnvelope = crypto.encrypt(rootKey, rootKeyContext(userId, envelope.keyId))
            check(
                prefs.edit()
                    .putString(KEY_STATE, STATE_ACTIVE)
                    .putString(KEY_ID, envelope.keyId)
                    .putString(KEY_LOCAL_ENVELOPE, localEnvelope.toJson().toString())
                    .putString(KEY_RECOVERY_ENVELOPE, envelope.toJson().toString())
                    .commit(),
            ) { "Could not store the restored account key on this device" }
        } finally {
            Arrays.fill(rootKey, 0)
        }
    }

    @Synchronized
    fun pendingRecoveryEnvelope(userId: String): RecoveryEnvelope {
        val prefs = preferences(userId).preferences
        check(prefs.getString(KEY_STATE, null) == STATE_PENDING) { "There is no pending account-key setup" }
        return RecoveryEnvelope.parse(requireNotNull(prefs.getString(KEY_RECOVERY_ENVELOPE, null)))
    }

    @Synchronized
    fun recoveryEnvelopeForUpload(userId: String): RecoveryEnvelope {
        val prefs = preferences(userId).preferences
        val state = prefs.getString(KEY_STATE, null)
        check(state == STATE_PENDING || state == STATE_ACTIVE) { "Account key is not configured" }
        return RecoveryEnvelope.parse(requireNotNull(prefs.getString(KEY_RECOVERY_ENVELOPE, null)))
    }

    /** Removes only an unfinished local setup so the user can start over if the code was lost. */
    @Synchronized
    fun discardPendingSetup(userId: String) {
        val prefs = preferences(userId).preferences
        if (prefs.getString(KEY_STATE, null) == STATE_PENDING) {
            check(prefs.edit().clear().commit()) { "Could not discard the unfinished account-key setup" }
        }
    }

    @Synchronized
    fun encryptObject(userId: String, plaintext: ByteArray, context: CryptoContext): EncryptedEnvelope {
        require(context.userId == userId) { "Crypto context belongs to a different user" }
        val prefs = activePreferences(userId)
        val keyId = requireNotNull(prefs.getString(KEY_ID, null))
        val rootKey = decryptLocalRootKey(userId, keyId, prefs)
        return try {
            crypto.encryptForAccount(plaintext, context, keyId, rootKey)
        } finally {
            Arrays.fill(rootKey, 0)
        }
    }

    @Synchronized
    fun decryptObject(userId: String, envelope: EncryptedEnvelope, context: CryptoContext): ByteArray {
        require(context.userId == userId) { "Crypto context belongs to a different user" }
        val prefs = activePreferences(userId)
        val keyId = requireNotNull(prefs.getString(KEY_ID, null))
        val rootKey = decryptLocalRootKey(userId, keyId, prefs)
        return try {
            crypto.decryptForAccount(envelope, context, keyId, rootKey)
        } finally {
            Arrays.fill(rootKey, 0)
        }
    }

    /** Decrypts many objects while unwrapping the account root key from the Keystore only **once**
     *  for the whole batch, instead of once per object. Confirmed live: loading a few hundred
     *  memory objects one [decryptObject] call at a time — each its own Keystore round trip just to
     *  re-obtain the *same* root key — took 10-20+ seconds and blocked interactive saves sharing the
     *  same per-user lock; the actual per-object work (AES-GCM over an already-unwrapped key) is
     *  microseconds. Callers doing a bulk load (Memória screen, Notas list, a chat tool reading
     *  memory) should use this instead of looping [decryptObject]. */
    @Synchronized
    fun decryptObjects(userId: String, items: List<Pair<EncryptedEnvelope, CryptoContext>>): List<ByteArray> {
        if (items.isEmpty()) return emptyList()
        val prefs = activePreferences(userId)
        val keyId = requireNotNull(prefs.getString(KEY_ID, null))
        val rootKey = decryptLocalRootKey(userId, keyId, prefs)
        return try {
            items.map { (envelope, context) ->
                require(context.userId == userId) { "Crypto context belongs to a different user" }
                crypto.decryptForAccount(envelope, context, keyId, rootKey)
            }
        } finally {
            Arrays.fill(rootKey, 0)
        }
    }

    private fun activePreferences(userId: String): SharedPreferences {
        val secure = preferences(userId)
        requirePersistent(secure)
        if (secure.preferences.getString(KEY_STATE, null) != STATE_ACTIVE) throw AccountKeyNotActiveException()
        return secure.preferences
    }

    private fun decryptLocalRootKey(userId: String, keyId: String, prefs: SharedPreferences): ByteArray {
        val localEnvelope = EncryptedEnvelope.parse(requireNotNull(prefs.getString(KEY_LOCAL_ENVELOPE, null)))
        return crypto.decrypt(localEnvelope, rootKeyContext(userId, keyId)).also { rootKey ->
            if (rootKey.size != KEY_BYTES) {
                Arrays.fill(rootKey, 0)
                throw IllegalStateException("Stored account key has an invalid size")
            }
        }
    }

    private fun preferences(userId: String): AtlasSecurePreferences {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(StandardCharsets.UTF_8))
        val suffix = digest.take(12)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        Arrays.fill(digest, 0)
        return AtlasSecurePreferences.open(appContext, "atlas_account_key_$suffix")
    }

    private fun rootKeyContext(userId: String, keyId: String) =
        CryptoContext(userId = userId, objectId = keyId, objectType = "account-root-key")

    private fun requirePersistent(secure: AtlasSecurePreferences) {
        check(secure.persistent) { "Secure persistent storage is unavailable on this device" }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val KEY_STATE = "account_key_state"
        const val KEY_ID = "account_key_id"
        const val KEY_LOCAL_ENVELOPE = "account_key_local_envelope"
        const val KEY_RECOVERY_ENVELOPE = "account_key_recovery_envelope"
        const val STATE_PENDING = "pending"
        const val STATE_ACTIVE = "active"
    }
}
