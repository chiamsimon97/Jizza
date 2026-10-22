import { api } from '@/lib/api'
import type { ActivityCatalog, ActivityPage, ActivitySeverity, ActorType, AuditEntryDto, ChainVerification } from '@/types'

export interface ActivityQuery {
  from?: string
  to?: string
  categories?: string[]
  /** `category/group` keys. */
  groups?: string[]
  severities?: ActivitySeverity[]
  actors?: ActorType[]
  q?: string
  limit?: number
  offset?: number
}

/** Builds a query string, repeating a key for each value of a list and skipping empty ones. */
export function toParams(values: Record<string, string | number | string[] | undefined>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value == null || value === '') continue
    if (Array.isArray(value)) value.forEach(v => params.append(key, v))
    else params.set(key, String(value))
  }
  return params.toString()
}

export const viewerZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone

export const activityApi = {
  recent: (limit = 100, action?: string) =>
    api.get<AuditEntryDto[]>(
      `/api/activity?limit=${limit}${action ? `&action=${encodeURIComponent(action)}` : ''}`,
    ),

  catalog: () => api.get<ActivityCatalog>('/api/activity/catalog'),

  explore: (q: ActivityQuery) =>
    api.get<ActivityPage>(`/api/activity/explore?${toParams({
      from: q.from, to: q.to, category: q.categories, group: q.groups, severity: q.severities,
      actor: q.actors, q: q.q, limit: q.limit, offset: q.offset, tz: viewerZone(),
    })}`),

  verify: () =>
    api.get<ChainVerification>('/api/activity/verify'),
}
