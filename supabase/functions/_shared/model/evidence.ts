// Provenance for a fact or event (docs §30). Every non-mock WorldAnswer fact/event must carry at
// least one of these — "como sabemos disso?" always needs a real answer.
export interface Evidence {
  sourceId: string
  sourceName: string
  url?: string
  retrievedAt: string // ISO 8601
  publishedAt?: string // ISO 8601
  externalRecordId?: string
}
