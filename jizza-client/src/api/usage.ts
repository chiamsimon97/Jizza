import { api } from '@/lib/api'
import { toParams, viewerZone } from '@/api/activity'
import type { UsageDimension, UsageEntryDto, UsageExplorePage, UsageSummary } from '@/types'

export interface UsageQuery {
  from?: string
  to?: string
  groupBy: UsageDimension
  thenBy?: UsageDimension
  models?: string[]
  areas?: string[]
}

export const usageApi = {
  summary: () =>
    api.get<UsageSummary>('/api/usage/summary'),

  entries: (limit = 100) =>
    api.get<UsageEntryDto[]>(`/api/usage/entries?limit=${limit}`),

  explore: (q: UsageQuery) =>
    api.get<UsageExplorePage>(`/api/usage/explore?${toParams({
      from: q.from, to: q.to, groupBy: q.groupBy, thenBy: q.thenBy, model: q.models,
      area: q.areas, tz: viewerZone(),
    })}`),
}
