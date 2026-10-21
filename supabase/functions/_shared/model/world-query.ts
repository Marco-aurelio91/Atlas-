// The contract ATLAS sends to ATHENA (docs/ATHENA_PLANO_CRIACAO_BACKEND_SUPABASE.md §19).
// No personal field belongs here — see §44 "Isolamento de dados pessoais": ATLAS resolves "quando
// eu sair" into a location/time range *before* calling athena-query. This file only describes the
// shape of that already-depersonalized request.

export interface EntityRef {
  type: string
  name?: string
  externalId?: string
}

export interface GeoReference {
  lat: number
  lon: number
}

export interface TimeRange {
  from: string // ISO 8601
  to: string // ISO 8601
}

export interface WorldQuery {
  requestId: string
  intent: string
  query?: string
  entities?: EntityRef[]
  location?: GeoReference
  timeRange?: TimeRange
  fields?: string[]
  requiredFreshness?: string
  maxSources?: number
}
