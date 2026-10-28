import { api } from '@/lib/api'

export interface BrowserAgent {
  id: string
  workspaceId: string | null
  name: string
  online: boolean
  family: string | null
  version: string | null
  allowedSites: string[]
  sessions: number | null
  lastSeenAt: string | null
}

export interface WebTestError {
  kind: string | null
  detail: string | null
  candidates?: unknown
}

export interface WebTestResult {
  status: 'succeeded' | 'failed' | 'timed_out' | string
  output?: unknown
  error?: WebTestError | null
  files: string[]
  message?: string | null
}

export interface PickResult {
  status: 'waiting' | 'done' | 'failed'
  element?: Record<string, unknown>
  preview?: string | null
  url?: string | null
  error?: WebTestError | null
}

export const webApi = {
  pick: (body: { deviceId: string; sessionId?: string; url?: string; prompt?: string }) =>
    api.post<{ commandId: string }>('/api/web/pick', body),
  pickResult: (commandId: string) => api.get<PickResult>(`/api/web/pick/${commandId}`),
  browsers: () => api.get<BrowserAgent[]>('/api/web/browsers'),
  testStep: (body: { deviceId: string; action: string; version: number; inputs: Record<string, unknown> }) =>
    api.post<WebTestResult>('/api/web/test-step', body),
}
