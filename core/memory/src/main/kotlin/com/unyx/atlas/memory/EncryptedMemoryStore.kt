package com.unyx.atlas.memory

import com.unyx.atlas.memory.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted backing store for Atlas memory rows. The table name and complete row live inside the
 * ciphertext; Supabase sees only an owner UUID, an opaque object UUID, and the crypto envelope.
 * Filtering and ordering happen on-device after authenticated decryption.
 */
class EncryptedMemoryStore(
    private val objects: AtlasEncryptedObjectRepository,
    private val legacyClient: SupabaseRestClient,
    private val auth: AuthRepository,
) {
    val isConfigured: Boolean get() = objects.isConfigured
    private val migratedLegacyTables = ConcurrentHashMap.newKeySet<String>()
    private val migrationLocks = ConcurrentHashMap<String, Mutex>()
    private val rowSnapshots = ConcurrentHashMap<String, RowSnapshot>()
    private val snapshotLocks = ConcurrentHashMap<String, Mutex>()
    private val snapshotCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun insert(table: String, input: JSONObject, accessToken: String): JSONObject {
        val userId = requireOwner(accessToken)
        requireSupportedTable(table)
        migrateLegacyTable(table, userId, accessToken)
        val row = normalizeRow(input, userId, UUID.randomUUID().toString(), table)
        persist(table, row, userId)
        return JSONObject(row.toString())
    }

    suspend fun select(table: String, query: String, accessToken: String): JSONArray {
        val userId = requireOwner(accessToken)
        requireSupportedTable(table)
        migrateLegacyTable(table, userId, accessToken)
        val rows = loadRowsSnapshot(userId).asSequence()
            .filter { it.first == table }
            .map { it.second }
            .filter { row -> matches(row, query) }
            .toList()
        val ordered = sortRows(rows, query)
        val offset = queryParam(query, "offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = queryParam(query, "limit")?.toIntOrNull()?.coerceAtLeast(0) ?: ordered.size
        val result = JSONArray()
        ordered.drop(offset).take(limit).forEach { result.put(JSONObject(it.toString())) }
        return result
    }

    suspend fun update(table: String, id: String, fields: JSONObject, accessToken: String) {
        val userId = requireOwner(accessToken)
        requireSupportedTable(table)
        val row = select(table, "id=eq.${encodeFilter(id)}&limit=1", accessToken)
            .takeIf { it.length() > 0 }
            ?.getJSONObject(0)
            ?: return
        val updated = JSONObject(row.toString())
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            updated.put(key, fields.opt(key))
        }
        updated.put("updated_at", now())
        persist(table, normalizeRow(updated, userId, id, table), userId)
    }

    suspend fun delete(table: String, id: String, accessToken: String) {
        val userId = requireOwner(accessToken)
        requireSupportedTable(table)
        if (select(table, "id=eq.${encodeFilter(id)}&limit=1", accessToken).length() == 0) return
        val lock = snapshotLocks.computeIfAbsent(userId) { Mutex() }
        lock.withLock {
            objects.delete(canonicalUuid(id)).getOrThrow()
            rowSnapshots.remove(userId)
        }
    }

    /** Proactively migrates every legacy plaintext memory table for this user, instead of only ever
     * migrating a table the moment something happens to call [insert]/[select]/[allRows] on it —
     * left on its own, a table a given user's workflow never touches (routines, say, for someone
     * who's never used them) would sit in plaintext in Supabase indefinitely even after the account
     * key is active and perfectly able to encrypt it. Meant to be called once the key becomes
     * active (fresh setup or restore) and is cheap to repeat: an already-migrated table is one empty
     * SELECT — for every table except [TABLE_EXCLUDED_FROM_PROACTIVE_MIGRATION].
     *
     * `memory_access_log` is deliberately skipped: confirmed live on an account with months of
     * real usage, it had accumulated 2,300+ rows, and [migrateLegacyTable] copies legacy rows one
     * at a time (insert, read back to verify, delete), each hop holding the very same per-user
     * [snapshotLocks] mutex an interactive save (a note, a chat memory write) also needs — so a
     * user opening Notes or Memória right after restoring their key queued behind a ~40-minute
     * migration with no end in sight, looking exactly like a stuck save. This table is just an
     * access-time audit trail (table/record reference + timestamp, see its schema) with no
     * personal content, so there's nothing sensitive lost by leaving old rows in the legacy
     * plaintext table — new access-log writes already go through the encrypted store via
     * [MemoryRepository.logAccess], this only affects history from before the account key existed. */
    suspend fun migrateAllLegacyTables(accessToken: String) {
        val userId = requireOwner(accessToken)
        (MEMORY_TABLES - TABLE_EXCLUDED_FROM_PROACTIVE_MIGRATION)
            .forEach { table -> migrateLegacyTable(table, userId, accessToken) }
    }

    /** Returns all encrypted memory rows for local ranking. No content or embedding leaves device. */
    suspend fun allRows(accessToken: String, tables: Set<String>? = null): List<Pair<String, JSONObject>> {
        val userId = requireOwner(accessToken)
        val requestedTables = tables?.also { requested ->
            require(requested.all { it in MEMORY_TABLES }) { "Unsupported encrypted memory table" }
        } ?: MEMORY_TABLES
        requestedTables.forEach { table -> migrateLegacyTable(table, userId, accessToken) }
        return loadRowsSnapshot(userId).filter { (table, _) -> tables == null || table in tables }
    }

    /** Copies a user's legacy plaintext rows to ciphertext and verifies every copy before removal. */
    private suspend fun migrateLegacyTable(table: String, userId: String, accessToken: String) {
        val migrationKey = "$userId:$table"
        if (migrationKey in migratedLegacyTables) return
        val lock = migrationLocks.computeIfAbsent(migrationKey) { Mutex() }
        lock.withLock {
            if (migrationKey in migratedLegacyTables) return
            val legacyRows = ArrayList<JSONObject>()
            var offset = 0
            while (true) {
                val page = legacyClient.select(
                    table,
                    "user_id=eq.$userId&order=id.asc&limit=$LEGACY_PAGE_SIZE&offset=$offset",
                    accessToken,
                )
                if (page.length() == 0) break
                for (index in 0 until page.length()) {
                    val row = page.getJSONObject(index)
                    check(row.optString("user_id") == userId) { "Legacy memory owner does not match the signed-in account" }
                    legacyRows += normalizeRow(row, userId, row.getString("id"), table)
                }
                offset += page.length()
                check(offset <= MAX_ROWS_PER_READ) { "Too many legacy memory rows for one migration; retry in smaller batches" }
            }

            for (row in legacyRows) {
                val id = row.getString("id")
                persist(table, row, userId)
                val stored = objects.read(id).getOrThrow()
                    ?: error("Encrypted legacy memory verification failed")
                try {
                    val payload = JSONObject(String(stored.payload, StandardCharsets.UTF_8))
                    check(payload.optInt("schema") == PAYLOAD_SCHEMA && payload.optString("table") == table) {
                        "Encrypted legacy memory verification failed"
                    }
                    check(jsonEquivalent(payload.getJSONObject("row"), row)) { "Encrypted legacy memory verification failed" }
                } finally {
                    stored.payload.fill(0)
                }
            }

            // Only remove a legacy record after the full table was copied and verified.
            for (row in legacyRows) legacyClient.delete(table, row.getString("id"), accessToken)
            migratedLegacyTables += migrationKey
        }
    }

    /** The network save itself deliberately runs outside [snapshotLocks] — confirmed live, holding
     *  that lock for the whole call let a single interactive write (one note) queue up behind
     *  [loadRowsSnapshot]'s decrypt-everything sweep (hundreds of objects once an account has real
     *  history, each needing its own Keystore round trip), turning a sub-second save into a 10-20s+
     *  stall that looked exactly like a hang. [AtlasEncryptedObjectRepository.save] already has its
     *  own optimistic-concurrency check against the *server* (the revision match), so correctness
     *  there never depended on this lock — it only ever existed to keep [rowSnapshots] consistent,
     *  which is all it's scoped to now. */
    private suspend fun persist(table: String, row: JSONObject, userId: String) {
        val id = canonicalUuid(row.getString("id"))
        row.put("id", id)
        row.put("user_id", userId)
        val bytes = JSONObject()
            .put("schema", PAYLOAD_SCHEMA)
            .put("table", table)
            .put("row", row)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        try {
            objects.save(id, OBJECT_TYPE_MEMORY, bytes).getOrThrow()
            val lock = snapshotLocks.computeIfAbsent(userId) { Mutex() }
            lock.withLock {
                val snapshot = rowSnapshots[userId]
                if (snapshot != null) {
                    if (System.nanoTime() - snapshot.loadedAtNanos >= SNAPSHOT_TTL_NANOS) {
                        rowSnapshots.remove(userId, snapshot)
                    } else {
                        val rowIndex = snapshot.rows.indexOfFirst { (storedTable, storedRow) ->
                            storedTable == table && storedRow.optString("id") == id
                        }
                        val updatedRow = table to JSONObject(row.toString())
                        if (rowIndex >= 0) {
                            snapshot.rows[rowIndex] = updatedRow
                        } else {
                            snapshot.rows.add(updatedRow)
                        }
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun loadRowsSnapshot(userId: String): List<Pair<String, JSONObject>> {
        val lock = snapshotLocks.computeIfAbsent(userId) { Mutex() }
        return lock.withLock {
            val now = System.nanoTime()
            val cached = rowSnapshots[userId]
            val rows = if (cached != null && now - cached.loadedAtNanos < SNAPSHOT_TTL_NANOS) {
                cached.rows
            } else {
                loadRows(userId).also { rows ->
                    val snapshot = RowSnapshot(System.nanoTime(), rows.toMutableList())
                    rowSnapshots[userId] = snapshot
                    snapshotCleanupScope.launch {
                        delay(SNAPSHOT_TTL_MILLIS)
                        rowSnapshots.remove(userId, snapshot)
                    }
                }
            }
            rows.map { (table, row) -> table to JSONObject(row.toString()) }
        }
    }

    private suspend fun loadRows(userId: String): List<Pair<String, JSONObject>> {
        val accessToken = requireNotNull(auth.currentAccessToken()) { "Sign in is required" }
        check(auth.currentUserId() == userId) { "The signed-in account changed" }
        val result = ArrayList<Pair<String, JSONObject>>()
        var offset = 0
        while (true) {
            val page = objects.readDecryptedPage(
                limit = PAGE_SIZE,
                offset = offset,
                objectType = OBJECT_TYPE_MEMORY,
            ).getOrThrow()
            if (page.isEmpty()) break
            try {
                for (item in page) {
                    val bytes = item.payload
                    try {
                        val payload = JSONObject(String(bytes, StandardCharsets.UTF_8))
                        require(payload.optInt("schema") == PAYLOAD_SCHEMA) { "Unsupported encrypted memory payload version" }
                        val table = payload.getString("table")
                        requireSupportedTable(table)
                        val row = payload.getJSONObject("row")
                        check(row.getString("user_id") == userId) { "Encrypted memory owner does not match" }
                        check(canonicalUuid(row.getString("id")) == item.objectId) {
                            "Encrypted memory ID does not match its object envelope"
                        }
                        result += table to JSONObject(row.toString())
                    } finally {
                        bytes.fill(0)
                    }
                }
            } finally {
                page.forEach { it.payload.fill(0) }
            }
            offset += page.size
            check(offset <= MAX_ROWS_PER_READ) { "Too many encrypted memory records for one read; use bounded pagination" }
        }
        check(auth.currentUserId() == userId) { "The signed-in account changed" }
        return result
    }

    private fun normalizeRow(input: JSONObject, userId: String, id: String, table: String): JSONObject =
        JSONObject(input.toString()).apply {
            put("id", canonicalUuid(optString("id").ifBlank { id }))
            put("user_id", userId)
            putIfAbsent("origem", "user_explicit")
            putIfAbsent("confidence", 1f)
            putIfAbsent("importance", 0.5f)
            putIfAbsent("heat", 0f)
            putIfAbsent("created_at", now())
            putIfAbsent("updated_at", now())
            putIfAbsent("confirmed_by_user", true)
            putIfAbsent("valid", true)
            when (table) {
                "memory_episodes" -> putIfAbsent("data", now())
                "memory_context" -> putIfAbsent("capturado_em", now())
                "memory_access_log" -> putIfAbsent("acessado_em", now())
                else -> Unit
            }
            if (!has("expires_at")) {
                when (table) {
                    "memory_events" -> put("expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).toString())
                    "memory_context" -> put("expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).toString())
                    else -> Unit
                }
            }
        }

    private fun JSONObject.putIfAbsent(key: String, value: Any) {
        if (!has(key)) put(key, value)
    }

    private fun jsonEquivalent(left: Any?, right: Any?): Boolean = when {
        left === JSONObject.NULL && right === JSONObject.NULL -> true
        left is JSONObject && right is JSONObject -> {
            val leftKeys = left.keys().asSequence().toSet()
            val rightKeys = right.keys().asSequence().toSet()
            leftKeys == rightKeys && leftKeys.all { key -> jsonEquivalent(left.opt(key), right.opt(key)) }
        }
        left is JSONArray && right is JSONArray -> left.length() == right.length() &&
            (0 until left.length()).all { index -> jsonEquivalent(left.opt(index), right.opt(index)) }
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }

    private fun requireOwner(accessToken: String): String {
        require(accessToken.isNotBlank()) { "Sign in is required" }
        return requireNotNull(auth.currentUserId()) { "Sign in is required" }
    }

    private fun requireSupportedTable(table: String) {
        require(table in MEMORY_TABLES) { "Unsupported encrypted memory table" }
    }

    private fun matches(row: JSONObject, query: String): Boolean = queryParts(query).all { (key, expression) ->
        when (key) {
            "order", "limit", "offset", "select" -> true
            "or" -> matchesOr(row, expression)
            else -> matchesValue(row, key, expression)
        }
    }

    private fun matchesOr(row: JSONObject, expression: String): Boolean {
        val clauses = expression.removePrefix("(").removeSuffix(")")
            .split(Regex(",(?=[A-Za-z_][A-Za-z0-9_]*\\.)"))
        return clauses.any { clause ->
            val dot = clause.indexOf('.')
            dot > 0 && matchesValue(row, clause.substring(0, dot), clause.substring(dot + 1))
        }
    }

    private fun matchesValue(row: JSONObject, key: String, expression: String): Boolean {
        if (expression == "is.null") return !row.has(key) || row.isNull(key)
        if (expression == "not.is.null") return row.has(key) && !row.isNull(key)
        val dot = expression.indexOf('.')
        if (dot < 0) return true
        val operation = expression.substring(0, dot)
        val expected = expression.substring(dot + 1)
        if (!row.has(key) || row.isNull(key) || row.opt(key) == JSONObject.NULL) return false
        val actual = row.opt(key)
        return when (operation) {
            "eq" -> scalar(actual).equals(expected, ignoreCase = false)
            "ilike" -> {
                val needle = expected.replace("%", "").replace("*", "").lowercase()
                scalar(actual).lowercase().contains(needle)
            }
            "in" -> {
                val values = expected.removePrefix("(").removeSuffix(")").split(',')
                values.any { scalar(actual) == it }
            }
            "cs" -> {
                val wanted = expected.removePrefix("{").removeSuffix("}")
                when (actual) {
                    is JSONArray -> (0 until actual.length()).any { actual.optString(it) == wanted }
                    is Collection<*> -> actual.any { it.toString() == wanted }
                    null -> false
                    else -> false
                }
            }
            "lt" -> compare(scalar(actual), expected) < 0
            "gte" -> compare(scalar(actual), expected) >= 0
            else -> false
        }
    }

    private fun sortRows(rows: List<JSONObject>, query: String): List<JSONObject> {
        val order = queryParam(query, "order") ?: return rows
        val clauses = order.split(',').map { raw ->
            val bits = raw.split('.')
            bits.first() to (bits.getOrNull(1)?.equals("desc", ignoreCase = true) == true)
        }
        return rows.sortedWith { left, right ->
            var comparison = 0
            for ((field, descending) in clauses) {
                comparison = compare(scalar(left.opt(field)), scalar(right.opt(field)))
                if (descending) comparison = -comparison
                if (comparison != 0) break
            }
            comparison
        }
    }

    private fun queryParts(query: String): List<Pair<String, String>> = query.split('&')
        .filter(String::isNotBlank)
        .mapNotNull { part ->
            val equals = part.indexOf('=')
            if (equals <= 0) null else decode(part.substring(0, equals)) to decode(part.substring(equals + 1))
        }

    private fun queryParam(query: String, key: String): String? =
        queryParts(query).firstOrNull { it.first == key }?.second

    private fun scalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun compare(left: String, right: String): Int {
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
        val leftDateTime = runCatching { OffsetDateTime.parse(left) }.getOrNull()
        val rightDateTime = runCatching { OffsetDateTime.parse(right) }.getOrNull()
        if (leftDateTime != null && rightDateTime != null) return leftDateTime.compareTo(rightDateTime)
        val leftDate = runCatching { java.time.LocalDate.parse(left) }.getOrNull()
        val rightDate = runCatching { java.time.LocalDate.parse(right) }.getOrNull()
        if (leftDate != null && rightDate != null) return leftDate.compareTo(rightDate)
        return left.compareTo(right, ignoreCase = true)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun encodeFilter(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun canonicalUuid(value: String): String = UUID.fromString(value).toString()
    private fun now(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private data class RowSnapshot(
        val loadedAtNanos: Long,
        val rows: MutableList<Pair<String, JSONObject>>,
    )

    private companion object {
        const val OBJECT_TYPE_MEMORY = "memory"
        const val SNAPSHOT_TTL_NANOS = 5_000_000_000L
        const val SNAPSHOT_TTL_MILLIS = 5_000L
        const val PAYLOAD_SCHEMA = 1
        const val PAGE_SIZE = 200
        const val LEGACY_PAGE_SIZE = 200
        const val MAX_ROWS_PER_READ = 10_000
        val MEMORY_TABLES = setOf(
            "memory_facts", "memory_preferences", "memory_events", "memory_episodes", "memory_routines",
            "memory_people", "memory_projects", "memory_tasks", "memory_notes", "memory_decisions",
            "memory_corrections", "memory_context", "memory_embeddings", "memory_access_log", "memory_places",
        )

        /** See [migrateAllLegacyTables]'s doc — an audit trail, not personal content, and large
         *  enough on a real account to turn the proactive sweep into a lock-starving marathon. */
        private const val TABLE_EXCLUDED_FROM_PROACTIVE_MIGRATION = "memory_access_log"
    }
}
