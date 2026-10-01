import { api } from '@/lib/api'
import type { UsageEntryDto, UsageSummary } from '@/types'

export const usageApi = {
  summary: () =>
    api.get<UsageSummary>('/api/usage/summary'),

  entries: (limit = 100) =>
    api.get<UsageEntryDto[]>(`/api/usage/entries?limit=${limit}`),
}
