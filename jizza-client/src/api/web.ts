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
  /** The visible page with the element outlined (newer browser agents only). */
  pagePreview?: string | null
  url?: string | null
  error?: WebTestError | null
}

export interface RecordedStepData {
  action: string
  version?: number
  label?: string
  input?: Record<string, unknown>
  mappings?: Record<string, { name: string; type: string; default?: unknown }>
  secretRef?: string | null
  meta?: { url?: string; title?: string; window?: string; app?: string; preview?: string; pagePreview?: string; sensitive?: boolean; truncated?: boolean; list?: RecordedList }
}

/** A "Choose from a list" step (browser agent 0.4.0+): what the list is, and the input names offered. */
export interface RecordedList {
  title?: string
  kind?: 'date' | 'time' | 'text'
  count?: number
  inputName?: string
  withinTitle?: string
  withinInputName?: string
  /** Marked by the person while recording (Mark a list): read with its saved patterns. */
  marked?: boolean
  /** title and withinTitle are names the person gave the items and cards (browser agent 0.8.0+). */
  named?: boolean
  /** The item names the list showed, and each card's name with its items' names. */
  options?: string[]
  groups?: { label: string; items: string[] }[]
}

export interface RecordedStep {
  seq: number
  position: number
  step: RecordedStepData
  deleted: boolean
}

export interface Recording {
  id: string
  name: string
  status: 'recording' | 'review' | 'saved' | 'discarded' | 'failed' | string
  deviceId: string
  deviceName?: string
  /** Empty for a recording made in Windows apps. */
  startUrl?: string | null
  stepCount: number
  workflowId?: string
  updatedAt: string
  steps?: RecordedStep[]
  /** web: in Chrome; desktop: in Windows apps, started with the hotkey on the PC. */
  kind?: 'web' | 'desktop'
  /** The app a desktop recording started in. */
  app?: string | null
}

export interface RecordingEvent { recordingId: string; status: string; stepCount: number; lastStep?: RecordedStep }

export interface EditOp {
  op: 'delete' | 'move' | 'relabel' | 'map' | 'secret' | 'insertCheck'
  seq: number
  toIndex?: number
  label?: string
  field?: string
  input?: { name: string; type: string; default?: unknown } | null
  secretRef?: string | null
  check?: { action: string; afterSeq: number }
}

export interface FixCandidate {
  element: Record<string, unknown> & { label?: string; strategies?: Array<Record<string, unknown>> }
  score?: number
  text?: string
}

/** A suggested new element for a web step that could no longer find its own. */
export interface LocatorFix {
  id: string
  workflowId: string
  workflowName: string
  nodeId: string
  nodeLabel: string
  candidates: FixCandidate[]
  status: 'open' | 'applied' | 'dismissed' | string
  runId?: string | null
  createdAt: string
}

export const webApi = {
  startRecording: (body: { deviceId: string; url: string; name: string }) => api.post<Recording>('/api/web/recordings', body),
  listRecordings: (status?: string[]) =>
    api.get<Recording[]>(`/api/web/recordings${status?.length ? `?${status.map(s => `status=${s}`).join('&')}` : ''}`),
  getRecording: (id: string) => api.get<Recording>(`/api/web/recordings/${id}`),
  editRecording: (id: string, ops: EditOp[]) => api.patch<Recording>(`/api/web/recordings/${id}/steps`, { ops }),
  stopRecording: (id: string) => api.post<Recording>(`/api/web/recordings/${id}/stop`, {}),
  discardRecording: (id: string) => api.delete<void>(`/api/web/recordings/${id}`),
  saveRecording: (id: string, body: { name: string; description?: string }) =>
    api.post<{ workflowId: string; version: number }>(`/api/web/recordings/${id}/save`, body),
  insertRecording: (id: string) =>
    api.post<{ nodes: unknown[]; edges: unknown[] }>(`/api/web/recordings/${id}/insert`, {}),

  pick: (body: { deviceId: string; sessionId?: string; url?: string; prompt?: string }) =>
    api.post<{ commandId: string }>('/api/web/pick', body),
  pickResult: (commandId: string) => api.get<PickResult>(`/api/web/pick/${commandId}`),
  browsers: () => api.get<BrowserAgent[]>('/api/web/browsers'),
  fixes: (status?: string) => api.get<LocatorFix[]>(`/api/web/fixes${status ? `?status=${status}` : ''}`),
  applyFix: (id: string, candidateIndex: number) =>
    api.post<{ workflowId: string; version: number }>(`/api/web/fixes/${id}/apply`, { candidateIndex }),
  /** Applies the element the person picked on the page (the pick's command id) instead of a suggestion. */
  applyPickedFix: (id: string, pickCommandId: string) =>
    api.post<{ workflowId: string; version: number }>(`/api/web/fixes/${id}/apply`, { pickCommandId }),
  dismissFix: (id: string) => api.post<void>(`/api/web/fixes/${id}/dismiss`, {}),
  testStep: (body: { deviceId: string; action: string; version: number; inputs: Record<string, unknown> }) =>
    api.post<WebTestResult>('/api/web/test-step', body),
}
