import type { Evidence } from "./evidence.ts"

export interface WorldEvent {
  id: string
  eventType: string
  entityIds: string[]
  status: string
  severity?: string
  location?: Record<string, unknown>
  startedAt?: string
  endedAt?: string
  detectedAt: string
  confidence?: number | null
  evidence: Evidence[]
}
