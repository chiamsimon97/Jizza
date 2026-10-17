import { api } from '@/lib/api'
import type { AuditEntryDto, ChainVerification } from '@/types'

export const activityApi = {
  recent: (limit = 100, action?: string) =>
    api.get<AuditEntryDto[]>(
      `/api/activity?limit=${limit}${action ? `&action=${encodeURIComponent(action)}` : ''}`,
    ),

  verify: () =>
    api.get<ChainVerification>('/api/activity/verify'),
}
