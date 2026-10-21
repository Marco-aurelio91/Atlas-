// ATHENA's only public entry point (docs/ATHENA_PLANO_CRIACAO_BACKEND_SUPABASE.md §17, §60) —
// and, per the 2026-09-30 decision, the STABLE front door ATLAS always calls. The real engine
// (query planning, connectors, circuit breakers, confidence/freshness) lives on the local PC
// worker (athena/gateway, Kotlin) — this function only forwards and translates contracts. That
// keeps ATLAS's own contract exactly as stable as §79 asks ("contrato estável") while avoiding a
// second implementation of already-tested Kotlin domain logic in TypeScript.
//
// If the worker is unreachable (PC off, tunnel down), this returns PROVIDER_UNAVAILABLE — a valid,
// honest answer (§53) — never a crash, and never a fabricated result.
//
// Secrets required (see `supabase secrets set`): ATHENA_WORKER_URL, ATHENA_WORKER_KEY.
import { corsHeaders, jsonResponse } from "../_shared/utils/http.ts"
import type { WorldQuery } from "../_shared/model/world-query.ts"
import type { WorldAnswer } from "../_shared/model/world-answer.ts"

function isValidQuery(body: unknown): body is WorldQuery {
  if (typeof body !== "object" || body === null) return false
  const q = body as Record<string, unknown>
  return typeof q.requestId === "string" && q.requestId.length > 0 && typeof q.intent === "string" && q.intent.length > 0
}

// The worker only understands these two today (athena/gateway's WorldIntentDto) — every other
// intent in the doc's own taxonomy (§16) is honestly UNSUPPORTED until a matching provider exists.
const WORKER_INTENTS = new Set(["CURRENT_WEATHER", "SERVICE_STATUS"])

interface WorkerAnswer {
  requestId: string
  status: string
  facts: { key: string; value: string }[]
  confidence: number | null
  freshness: string
  evidence: { sourceId: string; sourceName: string; observedAt: string }[]
  generatedAt: string
}

async function callWorker(query: WorldQuery): Promise<WorkerAnswer | null> {
  const workerUrl = Deno.env.get("ATHENA_WORKER_URL")
  const workerKey = Deno.env.get("ATHENA_WORKER_KEY")
  if (!workerUrl || !workerKey) return null

  const body: Record<string, unknown> = { requestId: query.requestId, intent: query.intent }
  if (query.location) body.location = { latitude: query.location.lat, longitude: query.location.lon }
  if (query.intent === "SERVICE_STATUS" && query.query) body.serviceId = query.query

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 8000) // §36: global timeout budget
  try {
    const res = await fetch(`${workerUrl}/v1/query`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Athena-Key": workerKey },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
    if (!res.ok) return null
    return (await res.json()) as WorkerAnswer
  } catch {
    return null // network error, timeout, tunnel down — all the same to the caller: unavailable
  } finally {
    clearTimeout(timeout)
  }
}

function toWorldAnswer(requestId: string, worker: WorkerAnswer | null): WorldAnswer {
  const now = new Date().toISOString()
  if (!worker) {
    return {
      requestId, status: "PROVIDER_UNAVAILABLE", facts: [], events: [], entities: [],
      confidence: { level: "UNKNOWN" }, freshness: "UNKNOWN", evidence: [], generatedAt: now,
    }
  }
  return {
    requestId,
    status: worker.status as WorldAnswer["status"],
    facts: worker.facts.map((f) => ({ predicate: f.key, value: f.value, freshness: worker.freshness, evidence: [] })),
    events: [],
    entities: [],
    confidence: { level: worker.confidence == null ? "UNKNOWN" : String(worker.confidence) },
    freshness: worker.freshness,
    evidence: worker.evidence.map((e) => ({ sourceId: e.sourceId, sourceName: e.sourceName, retrievedAt: e.observedAt })),
    generatedAt: worker.generatedAt,
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders })
  if (req.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405)

  let body: unknown
  try {
    body = await req.json()
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400)
  }

  if (!isValidQuery(body)) {
    return jsonResponse({ error: "invalid_query", detail: "requestId and intent are required" }, 400)
  }

  const query = body
  if (!WORKER_INTENTS.has(query.intent)) {
    return jsonResponse(toWorldAnswer(query.requestId, null) satisfies WorldAnswer)
  }

  const workerAnswer = await callWorker(query)
  return jsonResponse(toWorldAnswer(query.requestId, workerAnswer))
})
