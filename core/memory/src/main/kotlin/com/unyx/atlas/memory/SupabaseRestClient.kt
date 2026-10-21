package com.unyx.atlas.memory

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal PostgREST client scoped to Atlas's Supabase project — plain OkHttp + org.json, matching
 * the same lightweight style already used elsewhere in this codebase (e.g.
 * feature/backup/.../DriveRestClient.kt) instead of pulling in the full supabase-kt SDK and its
 * Ktor/kotlinx-serialization dependency chain for what is, so far, simple CRUD.
 *
 * Every memory_* table's row-level security policy is `auth.uid()::text = user_id` (see
 * core/memory/schema/001_init.sql) — `apikey` alone (the anon/publishable key) identifies the
 * *project*, not a person, so every call here also needs the signed-in user's own access token in
 * [accessToken] or PostgREST will just see zero matching rows (reads) / reject the write (RLS
 * violation). Pass null only for truly anonymous, unscoped calls — there aren't any of those among
 * the memory_* tables today.
 */
class SupabaseRestClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    private fun authed(b: Request.Builder, accessToken: String?): Request.Builder = b
        .header("apikey", apiKey)
        .header("Authorization", "Bearer ${accessToken ?: apiKey}")

    /** Inserts one row; returns the row as Supabase echoes it back (with server-generated fields like id/created_at filled in). */
    fun insert(table: String, row: JSONObject, accessToken: String?): JSONObject {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val body = row.toString().toRequestBody("application/json".toMediaType())
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table")
                .post(body)
                .header("Prefer", "return=representation"),
            accessToken,
        ).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("insert $table")
            return JSONArray(resp.body?.string().orEmpty()).getJSONObject(0)
        }
    }

    /**
     * Inserts [row], or updates it in place if a row already matches [onConflictColumns] (e.g.
     * memory_embeddings' `(tabela_origem, registro_id, modelo)` unique constraint) — lets
     * re-indexing the same record for search overwrite its old vector instead of accumulating
     * duplicate rows every time the source text changes. No `return=representation`: callers that
     * only need to know the write succeeded (not the row back) avoid parsing a response shape that
     * varies for exotic column types like `vector`.
     */
    fun upsert(table: String, row: JSONObject, onConflictColumns: String, accessToken: String?) {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val body = row.toString().toRequestBody("application/json".toMediaType())
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table?on_conflict=$onConflictColumns")
                .post(body)
                .header("Prefer", "resolution=merge-duplicates"),
            accessToken,
        ).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw it.toError("upsert $table") }
    }

    /** Calls a Postgres function exposed by PostgREST at `/rest/v1/rpc/[fn]`, e.g. the
     * `match_memory_embeddings` similarity-search function (see schema/002_vector_search.sql).
     * A `returns void` function (e.g. the `atlas_chat_*` pairing RPCs) gets back HTTP 204 with an
     * empty body from PostgREST — treated as an empty array, not a parse failure; `JSONArray("")`
     * would otherwise throw even though the call itself succeeded. */
    fun rpc(fn: String, args: JSONObject, accessToken: String?): JSONArray {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val body = args.toString().toRequestBody("application/json".toMediaType())
        val req = authed(Request.Builder().url("$baseUrl/rest/v1/rpc/$fn").post(body), accessToken).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("rpc $fn")
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isEmpty() || raw == "null") return JSONArray()
            return JSONArray(raw)
        }
    }

    /** Merges [fields] into the row matching `id=eq.$id`. */
    fun update(table: String, id: String, fields: JSONObject, accessToken: String?) {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val body = fields.toString().toRequestBody("application/json".toMediaType())
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table?id=eq.$id")
                .patch(body),
            accessToken,
        ).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw it.toError("update $table") }
    }

    /** Conditional PostgREST update used for composite-key rows with optimistic revision checks. */
    fun updateWhere(table: String, filters: String, fields: JSONObject, accessToken: String?): Int {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        requireSafeTableAndFilters(table, filters)
        val body = fields.toString().toRequestBody("application/json".toMediaType())
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table?$filters")
                .patch(body)
                .header("Prefer", "return=representation"),
            accessToken,
        ).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("conditional update $table")
            return JSONArray(resp.body?.string().orEmpty()).length()
        }
    }

    /**
     * Reads rows from [table]. [query] is raw PostgREST syntax appended to the URL, e.g.
     * `"valid=eq.true&order=created_at.desc&limit=20"` — see
     * https://postgrest.org/en/stable/references/api/tables_views.html for the operators. There's
     * no need to filter by user_id explicitly; RLS already scopes every row to [accessToken]'s owner.
     */
    fun select(table: String, query: String = "", accessToken: String?): JSONArray {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val url = if (query.isBlank()) "$baseUrl/rest/v1/$table?select=*" else "$baseUrl/rest/v1/$table?select=*&$query"
        val req = authed(Request.Builder().url(url), accessToken).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("select $table")
            return JSONArray(resp.body?.string().orEmpty())
        }
    }

    fun delete(table: String, id: String, accessToken: String?) {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table?id=eq.$id")
                .delete(),
            accessToken,
        ).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw it.toError("delete $table") }
    }

    /** Deletes rows selected by a caller-built, validated PostgREST filter. */
    fun deleteWhere(table: String, filters: String, accessToken: String?): Int {
        require(isConfigured) { "Supabase isn't configured (missing URL/key) — see supabase.properties" }
        requireSafeTableAndFilters(table, filters)
        val req = authed(
            Request.Builder()
                .url("$baseUrl/rest/v1/$table?$filters")
                .delete()
                .header("Prefer", "return=representation"),
            accessToken,
        ).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("filtered delete $table")
            return JSONArray(resp.body?.string().orEmpty()).length()
        }
    }

    private fun requireSafeTableAndFilters(table: String, filters: String) {
        require(table.matches(Regex("[a-z_][a-z0-9_]*"))) { "Invalid table name" }
        require(filters.isNotBlank() && filters.matches(Regex("[A-Za-z0-9_.,=&-]+"))) {
            "Invalid PostgREST filter"
        }
    }

    /** Builds an [IOException] including the HTTP status and the API's error body (explains WHY — a
     * bad RLS policy, a missing column, a malformed filter — instead of just "it failed"). */
    private fun Response.toError(op: String): IOException {
        val detail = runCatching { body?.string() }.getOrNull()?.take(300)?.trim().orEmpty()
        return IOException("Supabase $op HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}")
    }
}
