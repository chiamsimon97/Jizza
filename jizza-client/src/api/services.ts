import { api } from '@/lib/api'
import type {
  ConnectionDetail, ServiceAction, ServiceApi, ServiceDetail, ServiceInfo, ServiceResource,
  ServiceRun, ServiceTrigger, ServiceVariable, ToolRunResult, TriggerSample,
} from '@/types'

const base = (id: string) => `/api/services/${id}`

/** Create when there is no id yet, update otherwise. */
function save<T>(path: string, id: string | undefined, body: unknown) {
  return id ? api.put<T>(`${path}/${id}`, body) : api.post<T>(path, body)
}

export const servicesApi = {
  list: () => api.get<ServiceInfo[]>('/api/services'),
  create: (body: { name: string; description?: string; category?: string; baseUrl?: string; authScheme?: string }) =>
    api.post<ServiceInfo>('/api/services', body),
  get: (id: string) => api.get<ServiceDetail>(base(id)),
  update: (id: string, patch: Partial<Pick<ServiceInfo, 'name' | 'description' | 'category' | 'baseUrl'>>) =>
    api.patch<ServiceInfo>(base(id), patch),
  remove: (id: string) => api.delete<void>(base(id)),
  publish: (id: string) => api.post<ServiceInfo>(`${base(id)}/publish`),
  unpublish: (id: string) => api.post<ServiceInfo>(`${base(id)}/unpublish`),

  saveApi: (id: string, apiId: string | undefined, body: unknown) => save<ServiceApi>(`${base(id)}/apis`, apiId, body),
  deleteApi: (id: string, apiId: string) => api.delete<void>(`${base(id)}/apis/${apiId}`),

  saveResource: (id: string, resourceId: string | undefined, body: unknown) =>
    save<ServiceResource>(`${base(id)}/resources`, resourceId, body),
  deleteResource: (id: string, resourceId: string) => api.delete<void>(`${base(id)}/resources/${resourceId}`),

  saveAction: (id: string, actionId: string | undefined, body: unknown) =>
    save<ServiceAction>(`${base(id)}/actions`, actionId, body),
  deleteAction: (id: string, actionId: string) => api.delete<void>(`${base(id)}/actions/${actionId}`),
  assignAgents: (id: string, actionId: string, agentIds: string[]) =>
    api.put<ServiceAction>(`${base(id)}/actions/${actionId}/agents`, { agentIds }),
  testAction: (id: string, actionId: string, input: unknown) =>
    api.post<ToolRunResult>(`${base(id)}/actions/${actionId}/test`, input ?? {}),

  saveTrigger: (id: string, triggerId: string | undefined, body: unknown) =>
    save<ServiceTrigger>(`${base(id)}/triggers`, triggerId, body),
  deleteTrigger: (id: string, triggerId: string) => api.delete<void>(`${base(id)}/triggers/${triggerId}`),
  testTrigger: (id: string, triggerId: string) => api.post<TriggerSample>(`${base(id)}/triggers/${triggerId}/test`),

  saveVariable: (id: string, variableId: string | undefined, body: unknown) =>
    save<ServiceVariable>(`${base(id)}/variables`, variableId, body),
  deleteVariable: (id: string, variableId: string) => api.delete<void>(`${base(id)}/variables/${variableId}`),

  history: (id: string) => api.get<ServiceRun[]>(`${base(id)}/history?limit=100`),

  // Authentication lives on the service's connection.
  connection: (id: string) => api.get<ConnectionDetail>(`/api/connections/${id}`),
  updateAuth: (id: string, body: unknown) => api.patch<ConnectionDetail>(`/api/connections/${id}`, body),
  authorize: (id: string) => api.post<{ url: string }>(`/api/connections/${id}/authorize`),
  testAuth: (id: string) => api.post<{ ok: boolean; httpStatus: number; message?: string }>(`/api/connections/${id}/test`),
  revoke: (id: string) => api.post<void>(`/api/connections/${id}/revoke`),
}
