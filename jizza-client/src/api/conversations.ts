import { api } from '@/lib/api'
import type { ConversationSummary, JobDto, MessageDto } from '@/types'

export const conversationsApi = {
  list: (opts: { q?: string; archived?: boolean; limit?: number } = {}) => {
    const params = new URLSearchParams({ limit: String(opts.limit ?? 50) })
    if (opts.q) params.set('q', opts.q)
    if (opts.archived) params.set('archived', 'true')
    return api.get<ConversationSummary[]>(`/api/conversations?${params}`)
  },

  update: (id: string, patch: { title?: string; pinned?: boolean; archived?: boolean }) =>
    api.patch<ConversationSummary>(`/api/conversations/${id}`, patch),

  remove: (id: string) => api.delete<void>(`/api/conversations/${id}`),

  messages: (id: string) =>
    api.get<MessageDto[]>(`/api/conversations/${id}/messages`),

  jobs: (id: string) =>
    api.get<JobDto[]>(`/api/conversations/${id}/jobs`),
}
