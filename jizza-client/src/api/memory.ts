import { api } from '@/lib/api'
import type { AgentMemory, MemoryFile } from '@/types'

const base = (agentId: string) => `/api/agents/${agentId}/memory`

export const memoryApi = {
  get: (agentId: string) =>
    api.get<AgentMemory>(base(agentId)),

  upload: (agentId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload<MemoryFile>(`${base(agentId)}/files`, form)
  },

  content: (agentId: string, fileId: string) =>
    api.blob(`${base(agentId)}/files/${fileId}/content`),

  deleteFile: (agentId: string, fileId: string) =>
    api.delete<void>(`${base(agentId)}/files/${fileId}`),

  deleteNote: (agentId: string, noteId: string) =>
    api.delete<void>(`${base(agentId)}/notes/${noteId}`),
}

/** How an import went: new facts, ones Jizza already knew, conflicts held for review, and notes. */
export interface MemoryImportResult {
  created: number
  duplicates: number
  pending: number
  notes: number
  skipped: number
  problems: string[]
}

/** Taking the user's memory out as Markdown, and bringing memory in from another assistant. */
export const memoryTransferApi = {
  /** A .zip of Markdown files: facts by category, people and things, each agent's notes. */
  export: () => api.blob('/api/memory/export'),

  importPrompt: () => api.get<{ prompt: string }>('/api/memory/import/prompt'),

  importText: (text: string, provider?: string) =>
    api.post<MemoryImportResult>('/api/memory/import', { text, provider: provider || undefined }),

  importFile: (file: File, provider?: string) => {
    const form = new FormData()
    form.append('file', file)
    if (provider) form.append('provider', provider)
    return api.upload<MemoryImportResult>('/api/memory/import/file', form)
  },
}
