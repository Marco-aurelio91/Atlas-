// Gateway for PARTNER apps (e.g. Unyx) — not for Atlas's own Android client, which uses atlas-ai
// (authenticated by an Atlas Supabase Auth JWT). A partner app has its own, completely separate
// user base; it authenticates with a per-app key instead, and its end users' ids are only ever
// used as an opaque rate-limiting bucket — never checked against anything in this project. See
// supabase/migrations/*_atlas_sdk_partner_apps.sql for the schema this relies on.
import { corsHeaders, providerError, readBodyWithinLimit, record } from "../_shared/ai/http.ts"
import { invokeAnthropic } from "../_shared/ai/providers.ts"

const MAX_END_USER_ID_LENGTH = 128

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("")
}

/** Looks up the app key AND claims this request's quota in one round trip (atomic — no gap
 *  between "is this key valid" and "did this push the app over its limit"). Returns the app_id on
 *  success; null for EITHER an unknown/inactive key OR a quota breach — deliberately the same
 *  signal for both, so a caller probing with bad keys can't use the response to tell "wrong key"
 *  apart from "rate limited". */
async function authorizeAndClaim(appKey: string, endUserId: string, operation: "chat" | "embedding"): Promise<string | null> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  if (!supabaseUrl || !serviceRoleKey) return null

  const keyHash = await sha256Hex(appKey)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 8000)
  try {
    const response = await fetch(`${supabaseUrl.replace(/\/$/, "")}/rest/v1/rpc/atlas_sdk_authorize_and_claim`, {
      method: "POST",
      headers: {
        apikey: serviceRoleKey,
        Authorization: `Bearer ${serviceRoleKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ p_key_hash: keyHash, p_end_user_id: endUserId, p_operation: operation }),
      signal: controller.signal,
    })
    if (!response.ok) return null
    const rows: unknown = await response.json()
    const row = record(Array.isArray(rows) ? rows[0] : rows)
    if (!row || row.allowed !== true) return null
    return typeof row.matched_app_id === "string" ? row.matched_app_id : null
  } catch {
    return null
  } finally {
    clearTimeout(timeout)
  }
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders })
  if (request.method !== "POST") return providerError(405, "method_not_allowed")

  // v1 only relays to Claude — every real ARIA use (captions, hashtags, moderation) is "complete
  // a prompt, get text back", nothing here has needed Gemini or embeddings yet. Add them the same
  // way atlas-ai does if a future partner app needs them.
  const operation = new URL(request.url).pathname.split("/").filter(Boolean).at(-1)
  if (operation !== "anthropic") return providerError(404, "unknown_ai_operation")

  const appKey = request.headers.get("X-Atlas-App-Key")
  const endUserId = request.headers.get("X-Atlas-End-User")
  if (!appKey || !endUserId || endUserId.length > MAX_END_USER_ID_LENGTH) {
    return providerError(401, "authentication_required")
  }

  const appId = await authorizeAndClaim(appKey, endUserId, "chat")
  // Same status/code for "key unknown or inactive" and "quota exceeded" — see authorizeAndClaim's
  // own doc for why this ambiguity is deliberate.
  if (!appId) return providerError(429, "ai_quota_exceeded_or_unauthorized")

  let rawBody: string | null
  try {
    rawBody = await readBodyWithinLimit(request)
  } catch {
    return providerError(400, "invalid_body")
  }
  if (rawBody === null) return providerError(413, "request_too_large")

  let payload: Record<string, unknown> | null
  try {
    payload = record(JSON.parse(rawBody))
  } catch {
    payload = null
  }
  if (!payload) return providerError(400, "invalid_json")

  try {
    return await invokeAnthropic(payload)
  } catch {
    // Do not include provider URLs, headers, prompts, keys, or response bodies in logs/errors.
    return providerError(502, "ai_provider_unavailable")
  }
})
