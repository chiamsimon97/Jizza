import { api } from '@/lib/api'
import type { ConversationSummary, JobDto, MessageDto, ThreadGuide } from '@/types'

/** A file name made of a title, the way the server names its exports. */
function slug(title: string | undefined, fallback: string) {
  return (title ?? fallback).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || fallback
}

async function save(path: string, name: string) {
  const file = await api.blob(path)
  const url = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

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
  download: (id: string, title: string | undefined, format: 'md' | 'json') =>
    save(`/api/conversations/${id}/export?format=${format}`, `${slug(title, 'conversation')}.${format}`),

  /** One conversation's row: for a thread, its lead, topic and main chat. */
  summary: (id: string) => api.get<ConversationSummary>(`/api/conversations/${id}/summary`),

  /** A main chat's threads, most recently active first. */
  threads: (id: string) => api.get<ConversationSummary[]>(`/api/conversations/${id}/threads`),

  guide: (id: string) => api.get<ThreadGuide>(`/api/conversations/${id}/guide`),

  updateGuide: (id: string, markdown: string) => api.put<ThreadGuide>(`/api/conversations/${id}/guide`, { markdown }),

  /** Saves a thread's learning file as a .md file. */
  downloadGuide: (id: string, title: string | undefined) =>
    save(`/api/conversations/${id}/guide/download`, `${slug(title, 'thread')}-guide.md`),

  jobs: (id: string) =>
    api.get<JobDto[]>(`/api/conversations/${id}/jobs`),
}
