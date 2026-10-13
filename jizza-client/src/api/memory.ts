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
