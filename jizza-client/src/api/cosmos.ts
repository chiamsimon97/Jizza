import { api } from '@/lib/api'
import type {
  CosmosSnapshot, ItemKind, ItemPage, MemoryCategory, MemorySearchFilters, MemorySearchHit, Passport,
} from '@/types'

const enc = encodeURIComponent

/** The Memory Cosmos: everything Jizza remembers, who can use it and who has. */
export const cosmosApi = {
  snapshot: () => api.get<CosmosSnapshot>('/api/memory/cosmos'),

  /** Forgets everything in one body's part of the sky (the sun's: all of it). Asks for step-up. */
  reset: (bodyId: string) =>
    api.post<{ facts: number; notes: number; subjects: number }>('/api/memory/cosmos/reset', { bodyId }),

  items: (bodyId: string, opts: { cursor?: string | null; limit?: number; includeInactive?: boolean } = {}) => {
    const q = new URLSearchParams()
    if (opts.cursor) q.set('cursor', opts.cursor)
    if (opts.limit) q.set('limit', String(opts.limit))
    if (opts.includeInactive) q.set('includeInactive', 'true')
    const qs = q.toString()
    return api.get<ItemPage>(`/api/memory/cosmos/bodies/${enc(bodyId)}/items${qs ? `?${qs}` : ''}`)
  },

  search: (text: string, f: MemorySearchFilters = {}) => {
    const q = new URLSearchParams({ q: text })
    f.agentIds?.forEach(id => q.append('agentIds', id))
    f.categories?.forEach(c => q.append('categories', c))
    if (f.classification) q.set('classification', f.classification)
    if (f.status) q.set('status', f.status)
    if (f.from) q.set('from', f.from)
    if (f.to) q.set('to', f.to)
    return api.get<MemorySearchHit[]>(`/api/memory/cosmos/search?${q}`)
  },

  passport: (kind: ItemKind, id: string, cursor?: string | null) =>
    api.get<Passport>(`/api/memory/cosmos/items/${kind}/${id}/passport${cursor ? `?cursor=${enc(cursor)}` : ''}`),

  /** Shows a restricted item's content; asks the user to confirm their password first. */
  reveal: (kind: ItemKind, id: string) =>
    api.get<Passport>(`/api/memory/cosmos/items/${kind}/${id}/reveal`),

  // facts
  correctFact: (id: string, content: string) => api.patch<unknown>(`/api/memory/${id}`, { content }),
  confirmFact: (id: string) => api.post<unknown>(`/api/memory/${id}/confirm`),
  retireFact: (id: string) => api.post<unknown>(`/api/memory/${id}/retire`),
  restoreFact: (id: string) => api.post<unknown>(`/api/memory/${id}/restore`),
  forgetFact: (id: string) => api.delete<unknown>(`/api/memory/${id}`),
  shareFact: (id: string, agentId: string) => api.post<unknown>(`/api/memory/${id}/share`, { agentId }),
  unshareFact: (id: string, agentId: string) => api.delete<unknown>(`/api/memory/${id}/share/${agentId}`),
  moveFact: (id: string, domains: string[]) => api.post<unknown>(`/api/memory/${id}/move`, { domains }),
  restrictFact: (id: string, restricted: boolean) =>
    api.post<unknown>(`/api/memory/${id}/${restricted ? 'restrict' : 'unrestrict'}`),

  // notes
  editNote: (agentId: string, noteId: string, title: string, content: string) =>
    api.patch<unknown>(`/api/agents/${agentId}/memory/notes/${noteId}`, { title, content }),
  moveNote: (agentId: string, noteId: string, toAgentId: string) =>
    api.post<unknown>(`/api/agents/${agentId}/memory/notes/${noteId}/move`, { toAgentId }),
  restrictNote: (agentId: string, noteId: string, restricted: boolean) =>
    api.post<unknown>(`/api/agents/${agentId}/memory/notes/${noteId}/${restricted ? 'restrict' : 'unrestrict'}`),
  promoteNote: (agentId: string, noteId: string, category: MemoryCategory, removeNote: boolean) =>
    api.post<unknown>(`/api/agents/${agentId}/memory/notes/${noteId}/promote`, { category, removeNote }),
  forgetNote: (agentId: string, noteId: string) =>
    api.delete<unknown>(`/api/agents/${agentId}/memory/notes/${noteId}`),
}
