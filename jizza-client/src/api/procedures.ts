import { api } from '@/lib/api'
import type { ProcedureHistoryEntry, WorkflowDto } from '@/types'

export const proceduresApi = {
  history: (taskId: string) =>
    api.get<ProcedureHistoryEntry[]>(`/api/tasks/${taskId}/procedure/history`),

  currentGraph: (taskId: string) =>
    api.get<WorkflowDto>(`/api/tasks/${taskId}/procedure`),

  approve: (taskId: string) =>
    api.post<void>(`/api/tasks/${taskId}/procedure/approve`),

  reject: (taskId: string, feedback?: string) =>
    api.post<void>(`/api/tasks/${taskId}/procedure/reject`, { feedback }),

  revise: (taskId: string, graph: unknown) =>
    api.post<void>(`/api/tasks/${taskId}/procedure/revise`, { graph }),

  rollback: (taskId: string, version: number) =>
    api.post<void>(`/api/tasks/${taskId}/procedure/rollback/${version}`),

  disable: (taskId: string) =>
    api.post<void>(`/api/tasks/${taskId}/procedure/disable`),
}
