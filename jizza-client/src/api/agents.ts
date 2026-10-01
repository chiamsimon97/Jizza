import { api } from '@/lib/api'
import type { AgentDto } from '@/types'

export const agentsApi = {
  list: () =>
    api.get<AgentDto[]>('/api/agents'),

  patch: (id: string, patch: { name?: string; enabled?: boolean }) =>
    api.patch<AgentDto>(`/api/agents/${id}`, patch),
}
