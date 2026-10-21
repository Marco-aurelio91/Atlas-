// Shared by atlas-ai and atlas-sdk — the AI-relay-specific HTTP helpers (stricter than
// _shared/utils/http.ts, which athena-query uses: POST/OPTIONS only, a request-size ceiling, and
// an opaque provider-error envelope). Moved out of atlas-ai/index.ts verbatim (pure refactor, no
// behavior change) so atlas-sdk doesn't have to duplicate it.
export const corsHeaders: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-atlas-app-key, x-atlas-end-user",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
}

export const MAX_BODY_BYTES = 18 * 1024 * 1024

export function jsonResponse(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, ...extraHeaders, "Content-Type": "application/json" },
  })
}

export function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

export function providerError(status: number, code: string): Response {
  return jsonResponse({ error: { message: code } }, status)
}

export function relay(upstream: Response, extraHeaders: Record<string, string> = {}): Response {
  const headers = new Headers(corsHeaders)
  const contentType = upstream.ok ? (upstream.headers.get("Content-Type") ?? "application/json") : "application/json"
  headers.set("Content-Type", contentType)
  Object.entries(extraHeaders).forEach(([name, value]) => headers.set(name, value))
  if (!upstream.ok) {
    return new Response(JSON.stringify({ error: { message: "ai_provider_error" } }), {
      status: upstream.status,
      headers,
    })
  }
  return new Response(upstream.body, { status: upstream.status, headers })
}

export async function readBodyWithinLimit(request: Request): Promise<string | null> {
  const lengthHeader = request.headers.get("Content-Length")
  if (lengthHeader && Number(lengthHeader) > MAX_BODY_BYTES) return null
  if (!request.body) return ""

  const reader = request.body.getReader()
  const decoder = new TextDecoder()
  let bytesRead = 0
  let text = ""
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      bytesRead += value.byteLength
      if (bytesRead > MAX_BODY_BYTES) {
        await reader.cancel()
        return null
      }
      text += decoder.decode(value, { stream: true })
    }
    return text + decoder.decode()
  } finally {
    reader.releaseLock()
  }
}
