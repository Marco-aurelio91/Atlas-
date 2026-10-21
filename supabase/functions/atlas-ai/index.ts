import { corsHeaders, providerError, readBodyWithinLimit, record } from "../_shared/ai/http.ts"
import { invokeAnthropic, invokeGeminiChat, invokeGeminiEmbedding } from "../_shared/ai/providers.ts"

async function authenticatedUserId(request: Request): Promise<string | null> {
  const authorization = request.headers.get("Authorization")
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")
  if (!authorization?.startsWith("Bearer ") || !supabaseUrl || !anonKey) return null

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 8000)
  try {
    const response = await fetch(`${supabaseUrl.replace(/\/$/, "")}/auth/v1/user`, {
      headers: { apikey: anonKey, Authorization: authorization },
      signal: controller.signal,
    })
    if (!response.ok) return null
    const user = record(await response.json())
    return typeof user?.id === "string" && user.id.length > 0 ? user.id : null
  } catch {
    return null
  } finally {
    clearTimeout(timeout)
  }
}

async function claimAiQuota(userId: string, operation: "chat" | "embedding"): Promise<boolean | null> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  if (!supabaseUrl || !serviceRoleKey) return null

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 8000)
  try {
    const response = await fetch(`${supabaseUrl.replace(/\/$/, "")}/rest/v1/rpc/atlas_claim_ai_quota`, {
      method: "POST",
      headers: {
        apikey: serviceRoleKey,
        Authorization: `Bearer ${serviceRoleKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ p_user_id: userId, p_operation: operation }),
      signal: controller.signal,
    })
    if (!response.ok) return null
    const result: unknown = await response.json()
    return typeof result === "boolean" ? result : null
  } catch {
    return null
  } finally {
    clearTimeout(timeout)
  }
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders })
  if (request.method !== "POST") return providerError(405, "method_not_allowed")

  const operation = new URL(request.url).pathname.split("/").filter(Boolean).at(-1)
  const quotaOperation = operation === "gemini-embedding"
    ? "embedding"
    : operation === "anthropic" || operation === "gemini-chat"
      ? "chat"
      : null
  if (!quotaOperation) return providerError(404, "unknown_ai_operation")

  // supabase/config.toml also requires a signed-in JWT at the Edge gateway. Verify it against Auth
  // here as well so this function remains closed if its gateway configuration is changed later.
  const userId = await authenticatedUserId(request)
  if (!userId) return providerError(401, "authentication_required")

  const quotaAllowed = await claimAiQuota(userId, quotaOperation)
  if (quotaAllowed === null) return providerError(503, "ai_quota_unavailable")
  if (!quotaAllowed) return providerError(429, "ai_quota_exceeded")

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
    switch (operation) {
      case "anthropic":
        return await invokeAnthropic(payload)
      case "gemini-chat":
        return await invokeGeminiChat(payload)
      case "gemini-embedding":
        return await invokeGeminiEmbedding(payload)
      default:
        return providerError(404, "unknown_ai_operation")
    }
  } catch {
    // Do not include provider URLs, headers, prompts, keys, or response bodies in logs/errors.
    return providerError(502, "ai_provider_unavailable")
  }
})
