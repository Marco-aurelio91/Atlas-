export interface WorldEntity {
  id: string
  entityType: string
  canonicalName: string
  externalIds: Record<string, string>
  aliases: string[]
}
