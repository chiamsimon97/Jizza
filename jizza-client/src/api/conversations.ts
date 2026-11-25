import { api } from '@/lib/api'
import type { ChatChoice } from '@/api/aiModels'
import type { ConversationSummary, EarlierSummary, JobDto, LearnedFact, MessageDto, ReplyQuality, ThreadGuide } from '@/types'

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

  create: (body: { title?: string; threads?: boolean } = {}) =>
    api.post<ConversationSummary>('/api/conversations', body),

  update: (id: string, patch: { title?: string; pinned?: boolean; archived?: boolean; threads?: boolean }) =>
    api.patch<ConversationSummary>(`/api/conversations/${id}`, patch),

  remove: (id: string) => api.delete<void>(`/api/conversations/${id}`),

  messages: (id: string) =>
    api.get<MessageDto[]>(`/api/conversations/${id}/messages`),

  /** Saves the conversation as a file: Markdown to read, or JSON with every message's details. */
  download: (id: string, title: string | undefined, format: 'md' | 'json') =>
    save(`/api/conversations/${id}/export?format=${format}`, `${slug(title, 'conversation')}.${format}`),

  /** One conversation's row: for a thread, its lead, topic and main chat. */
  summary: (id: string) => api.get<ConversationSummary>(`/api/conversations/${id}/summary`),

  /** A main chat's threads with their stage, most recently active first. Only that chat's. */
  threads: (id: string) => api.get<ConversationSummary[]>(`/api/conversations/${id}/threads`),

  /** What Jizza learned from what was said here: for a thread, its own memory. */
  learned: (id: string) => api.get<LearnedFact[]>(`/api/conversations/${id}/learned`),

  /** Removes the user's message and everything after it, so its edit can be sent in its place. */
  rewind: (id: string, messageId: string) =>
    api.post<void>(`/api/conversations/${id}/messages/${messageId}/rewind`, {}),

  /** Thumbs up or down on a reply, with an optional reason; 'none' takes it back. */
  feedback: (id: string, messageId: string, rating: 'up' | 'down' | 'none', reason?: string) =>
    api.put<void>(`/api/conversations/${id}/messages/${messageId}/feedback`, { rating, ...(reason ? { reason } : {}) }),

  /** How replies were rated over the last {@code days} days: per agent, and the latest ones. */
  quality: (days = 30) => api.get<ReplyQuality>(`/api/conversations/feedback?days=${days}`),

  guide: (id: string) => api.get<ThreadGuide>(`/api/conversations/${id}/guide`),

  updateGuide: (id: string, markdown: string) => api.put<ThreadGuide>(`/api/conversations/${id}/guide`, { markdown }),

  /** Saves a thread's learning file as a .md file. */
  downloadGuide: (id: string, title: string | undefined) =>
    save(`/api/conversations/${id}/guide/download`, `${slug(title, 'thread')}-guide.md`),

  /** The summary standing in for a long chat's older messages; null while every message is sent as written. */
  earlier: async (id: string) =>
    (await api.get<EarlierSummary | undefined>(`/api/conversations/${id}/earlier`)) ?? null,

  /** The model and depth picked for this chat; empty means Jizza decides. */
  choice: (id: string) => api.get<ChatChoice>(`/api/conversations/${id}/choice`),

  setChoice: (id: string, choice: ChatChoice) => api.put<ChatChoice>(`/api/conversations/${id}/choice`, choice),

  /** Keeps an earlier answer to a question (by its place among the earlier versions); the chat continues from it. */
  keep: (id: string, messageId: string, index: number) =>
    api.post<void>(`/api/conversations/${id}/messages/${messageId}/keep`, { index }),

  jobs: (id: string) =>
    api.get<JobDto[]>(`/api/conversations/${id}/jobs`),
}
