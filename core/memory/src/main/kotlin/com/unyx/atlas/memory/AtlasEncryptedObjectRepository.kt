package com.unyx.atlas.memory

import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.security.AtlasAccountKeyManager
import com.unyx.atlas.security.CryptoContext
import com.unyx.atlas.security.EncryptedEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class DecryptedAtlasObject(
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revision: Long,
    val updatedAt: String?,
) {
    override fun toString(): String =
        "DecryptedAtlasObject(objectId=$objectId, objectType=$objectType, payload=[REDACTED], revision=$revision)"
}

/**
 * Authenticated storage for new opaque Atlas objects. Each payload is encrypted on-device before
 * PostgREST receives it. This repository is intentionally not wired to legacy MemoryRepository:
 * existing records need an explicit, reversible migration before that switch can be made.
 * Callers own and must clear the returned plaintext byte arrays after use.
 */
class AtlasEncryptedObjectRepository(
    private val client: SupabaseRestClient,
    private val auth: AuthRepository,
    private val accountKeys: AtlasAccountKeyManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val isConfigured: Boolean get() = client.isConfigured

    suspend fun save(objectId: String, objectType: String, plaintext: ByteArray): Result<Long> =
        withContext(ioDispatcher) {
            try {
                require(plaintext.size <= MAX_PAYLOAD_BYTES) { "Encrypted Atlas payload exceeds the size limit" }
                val stableId = canonicalUuid(objectId)
                require(objectType in OBJECT_TYPES) { "Unsupported Atlas object type" }
                val userId = requireNotNull(auth.currentUserId()) { "Sign in is required" }
                val accessToken = requireNotNull(auth.currentAccessToken()) { "Sign in is required" }
                check(auth.currentUserId() == userId) { "The signed-in account changed" }
                val query = "object_id=eq.$stableId&limit=1"
                val rows = client.select(TABLE, query, accessToken)
                val existing = if (rows.length() == 0) null else parseRow(rows.getJSONObject(0), userId)
                if (existing != null) {
                    require(existing.objectType == objectType) { "An object id cannot change its type" }
                }

                val context = CryptoContext(userId, stableId, objectType)
                val envelope = accountKeys.encryptObject(userId, plaintext, context)
                val nextRevision = (existing?.revision ?: 0L) + 1L
                val row = JSONObject()
                    .put("user_id", userId)
                    .put("object_id", stableId)
                    .put("object_type", objectType)
                    .put("crypto_version", envelope.version)
                    .put("key_id", envelope.keyId)
                    .put("envelope", envelope.toJson())
                    .put("revision", nextRevision)

                if (existing == null) {
                    client.insert(TABLE, row, accessToken)
                } else {
                    val changed = client.updateWhere(
                        table = TABLE,
                        filters = "object_id=eq.$stableId&revision=eq.${existing.revision}",
                        fields = JSONObject()
                            .put("object_type", objectType)
                            .put("crypto_version", envelope.version)
                            .put("key_id", envelope.keyId)
                            .put("envelope", envelope.toJson())
                            .put("revision", nextRevision),
                        accessToken = accessToken,
                    )
                    check(changed == 1) { "Encrypted Atlas object changed concurrently; reload before saving" }
                }
                Result.success(nextRevision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    suspend fun read(objectId: String): Result<DecryptedAtlasObject?> = withContext(ioDispatcher) {
        try {
            val stableId = canonicalUuid(objectId)
            val userId = requireNotNull(auth.currentUserId()) { "Sign in is required" }
            val accessToken = requireNotNull(auth.currentAccessToken()) { "Sign in is required" }
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            val rows = client.select(TABLE, "object_id=eq.$stableId&limit=1", accessToken)
            if (rows.length() == 0) return@withContext Result.success(null)
            val record = parseRow(rows.getJSONObject(0), userId)
            val payload = accountKeys.decryptObject(
                userId = userId,
                envelope = record.envelope,
                context = CryptoContext(userId, record.objectId, record.objectType),
            )
            Result.success(
                DecryptedAtlasObject(
                    objectId = record.objectId,
                    objectType = record.objectType,
                    payload = payload,
                    revision = record.revision,
                    updatedAt = record.updatedAt,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    /** Reads one bounded page; use revision and updatedAt to schedule later incremental sync. */
    suspend fun listEncryptedPage(
        limit: Int = 100,
        offset: Int = 0,
        objectType: String? = null,
    ): Result<List<EncryptedAtlasObjectRow>> =
        withContext(ioDispatcher) {
            try {
                require(limit in 1..MAX_PAGE_SIZE) { "Invalid page size" }
                require(offset >= 0) { "Invalid page offset" }
                if (objectType != null) require(objectType in OBJECT_TYPES) { "Unsupported Atlas object type" }
                val userId = requireNotNull(auth.currentUserId()) { "Sign in is required" }
                val accessToken = requireNotNull(auth.currentAccessToken()) { "Sign in is required" }
                check(auth.currentUserId() == userId) { "The signed-in account changed" }
                val typeFilter = objectType?.let { "object_type=eq.$it&" }.orEmpty()
                val rows = client.select(
                    TABLE,
                    query = "${typeFilter}order=updated_at.asc&limit=$limit&offset=$offset",
                    accessToken = accessToken,
                )
                val result = ArrayList<EncryptedAtlasObjectRow>(rows.length())
                for (index in 0 until rows.length()) {
                    result += parseRow(rows.getJSONObject(index), userId).toPublicRow()
                }
                Result.success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    /** Reads and decrypts one page of objects. Returned byte arrays belong to the caller. */
    suspend fun readDecryptedPage(
        limit: Int = 100,
        offset: Int = 0,
        objectType: String? = null,
    ): Result<List<DecryptedAtlasObject>> = withContext(ioDispatcher) {
        try {
            val userId = requireNotNull(auth.currentUserId()) { "Sign in is required" }
            val records = listEncryptedPage(limit, offset, objectType).getOrThrow()
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            // One Keystore round trip for the whole page instead of one per record — see
            // decryptObjects's doc for why that mattered live (10-20+ second page loads).
            val payloads = accountKeys.decryptObjects(
                userId = userId,
                items = records.map { record -> record.envelope to CryptoContext(userId, record.objectId, record.objectType) },
            )
            val result = records.mapIndexed { index, record ->
                DecryptedAtlasObject(
                    objectId = record.objectId,
                    objectType = record.objectType,
                    payload = payloads[index],
                    revision = record.revision,
                    updatedAt = record.updatedAt,
                )
            }
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun delete(objectId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val stableId = canonicalUuid(objectId)
            val userId = requireNotNull(auth.currentUserId()) { "Sign in is required" }
            val accessToken = requireNotNull(auth.currentAccessToken()) { "Sign in is required" }
            check(auth.currentUserId() == userId) { "The signed-in account changed" }
            client.deleteWhere(TABLE, "object_id=eq.$stableId", accessToken)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun parseRow(json: JSONObject, expectedUserId: String): StoredRow {
        check(json.getString("user_id") == expectedUserId) { "Encrypted object belongs to a different user" }
        val objectId = canonicalUuid(json.getString("object_id"))
        val objectType = json.getString("object_type")
        require(objectType in OBJECT_TYPES) { "Unsupported Atlas object type" }
        val envelope = EncryptedEnvelope.fromJson(json.getJSONObject("envelope"))
        check(json.getInt("crypto_version") == envelope.version) { "Encrypted object version does not match its envelope" }
        check(json.getString("key_id") == envelope.keyId) { "Encrypted object key id does not match its envelope" }
        val revision = json.getLong("revision")
        require(revision > 0L) { "Encrypted object has an invalid revision" }
        return StoredRow(
            objectId = objectId,
            objectType = objectType,
            envelope = envelope,
            revision = revision,
            createdAt = json.optString("created_at").takeIf(String::isNotBlank),
            updatedAt = json.optString("updated_at").takeIf(String::isNotBlank),
        )
    }

    private fun StoredRow.toPublicRow() = EncryptedAtlasObjectRow(
        objectId = objectId,
        objectType = objectType,
        envelope = envelope,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun canonicalUuid(value: String): String = UUID.fromString(value).toString()

    private data class StoredRow(
        val objectId: String,
        val objectType: String,
        val envelope: EncryptedEnvelope,
        val revision: Long,
        val createdAt: String?,
        val updatedAt: String?,
    )

    private companion object {
        const val TABLE = "atlas_encrypted_objects"
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_PAGE_SIZE = 500
        val OBJECT_TYPES = setOf("memory", "conversation", "routine", "note", "attachment")
    }
}

data class EncryptedAtlasObjectRow(
    val objectId: String,
    val objectType: String,
    val envelope: EncryptedEnvelope,
    val revision: Long,
    val createdAt: String?,
    val updatedAt: String?,
) {
    override fun toString(): String =
        "EncryptedAtlasObjectRow(objectId=$objectId, objectType=$objectType, envelope=[CIPHERTEXT], revision=$revision)"
}
