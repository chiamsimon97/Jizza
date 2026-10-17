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

  /** Saves the conversation as a file: Markdown to read, or JSON with every message's details. */
  download: async (id: string, title: string | undefined, format: 'md' | 'json') => {
    const file = await api.blob(`/api/conversations/${id}/export?format=${format}`)
    const name = (title ?? 'conversation').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'conversation'
    const url = URL.createObjectURL(file)
    const link = document.createElement('a')
    link.href = url
    link.download = `${name}.${format}`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },

  jobs: (id: string) =>
    api.get<JobDto[]>(`/api/conversations/${id}/jobs`),
}
