// The contract ATHENA returns to ATLAS (docs §20). `status` distinguishes "nothing found" from
// "couldn't be sure" from "a provider is down" — never collapse these into a single boolean.
import type { Evidence } from "./evidence.ts"
import type { WorldEntity } from "./entity.ts"
import type { WorldEvent } from "./world-event.ts"
import type { WorldFact } from "./world-fact.ts"

export type AnswerStatus =
  | "SUCCESS"
  | "PARTIAL"
  | "NOT_FOUND"
  | "AMBIGUOUS"
  | "STALE_ONLY"
  | "CONFLICTING_EVIDENCE"
  | "PROVIDER_UNAVAILABLE"
  | "UNSUPPORTED"
  | "INVALID_QUERY"

export interface Confidence {
  level: string
  score?: number
}

export interface WorldAnswer {
  requestId: string
  status: AnswerStatus
  facts: WorldFact[]
  events: WorldEvent[]
  entities: WorldEntity[]
  confidence: Confidence
  freshness: string
  evidence: Evidence[]
  generatedAt: string
}
