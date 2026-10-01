import { api } from '@/lib/api'
import type { ConnectionDto, GeneratedToolDto } from '@/types'

export const connectionsApi = {
  list: () =>
    api.get<ConnectionDto[]>('/api/connections'),

  create: (data: unknown) =>
    api.post<ConnectionDto>('/api/connections', data),

  test: (id: string) =>
    api.post<{ ok: boolean; detail?: string }>(`/api/connections/${id}/test`),

  revoke: (id: string) =>
    api.post<void>(`/api/connections/${id}/revoke`),

  authorize: (id: string) =>
    api.post<{ redirectUrl: string }>(`/api/oauth/authorize/${id}`),

  tools: (connectionId: string) =>
    api.get<GeneratedToolDto[]>(`/api/connections/${connectionId}/tools`),

  generateTools: (connectionId: string, fileId: string) =>
    api.post<GeneratedToolDto[]>(`/api/connections/${connectionId}/tools/generate`, { fileId }),

  toggleTool: (connectionId: string, toolId: string, enabled: boolean) =>
    api.patch<void>(`/api/connections/${connectionId}/tools/${toolId}`, { enabled }),
}
