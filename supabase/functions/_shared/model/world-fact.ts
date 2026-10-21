import type { Evidence } from "./evidence.ts"

export interface WorldFact {
  entityRef?: string
  predicate: string
  value: unknown
  effectiveFrom?: string
  effectiveUntil?: string
  confidence?: number | null
  freshness: string
  evidence: Evidence[]
}
