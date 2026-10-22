import { corsHeaders, providerError, record } from "../_shared/ai/http.ts"

// Permanently deletes the caller's own account. Every table that stores user data references
// auth.users(id) with ON DELETE CASCADE (memory_*, atlas_encrypted_objects,
// atlas_key_recovery_envelopes, atlas_ai_usage, atlas_chat_*), so removing the Auth user removes
// all of it in one step. Required by Google Play (in-app account deletion) and LGPD art. 18.
//
// The caller can only ever delete THEMSELVES: the target id comes from the verified JWT, never from
// the request body. The body must carry an explicit confirmation so a stray request can't wipe an
// account.

const CONFIRMATION = "EXCLUIR"

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

async function deleteAuthUser(userId: string): Promise<boolean> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  if (!supabaseUrl || !serviceRoleKey) return false

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(
      `${supabaseUrl.replace(/\/$/, "")}/auth/v1/admin/users/${encodeURIComponent(userId)}`,
      {
        method: "DELETE",
        headers: { apikey: serviceRoleKey, Authorization: `Bearer ${serviceRoleKey}` },
        signal: controller.signal,
      },
    )
    // 404 means it is already gone — treat as success so a retry after a dropped response works.
    return response.ok || response.status === 404
  } catch {
    return false
  } finally {
    clearTimeout(timeout)
  }
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders })
  if (request.method !== "POST") return providerError(405, "method_not_allowed")

  // supabase/config.toml also requires a signed-in JWT at the Edge gateway; verify it against Auth
  // here too so this function stays closed if the gateway configuration is ever changed.
  const userId = await authenticatedUserId(request)
  if (!userId) return providerError(401, "authentication_required")

  let confirmation: unknown
  try {
    confirmation = record(await request.json())?.confirm
  } catch {
    return providerError(400, "invalid_body")
  }
  if (confirmation !== CONFIRMATION) return providerError(400, "confirmation_required")

  if (!(await deleteAuthUser(userId))) return providerError(502, "account_deletion_failed")
  return new Response(JSON.stringify({ deleted: true }), {
    status: 200,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  })
})
