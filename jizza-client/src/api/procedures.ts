import { api } from '@/lib/api'
import type { ProcedureSummary, TaskDto, WorkflowDto } from '@/types'

/** Workflows an agent learned from a repeating task, and their review (approve, roll back, …). */
export const proceduresApi = {
  list: () =>
    api.get<ProcedureSummary[]>('/api/procedures'),

  /** Adds a sample repeating task and the procedure learned from it, waiting for review. */
  addExample: () =>
    api.post<{ task: TaskDto; procedure: WorkflowDto }>('/api/procedures/example'),

  /** Every version, oldest first. */
  history: (id: string) =>
    api.get<WorkflowDto[]>(`/api/procedures/${id}/history`),

  approve: (id: string) =>
    api.post<{ message: string }>(`/api/procedures/${id}/approve`),

  reject: (id: string, feedback?: string) =>
    api.post<{ message: string }>(`/api/procedures/${id}/reject`, { feedback }),

  rollback: (id: string, toVersion: number) =>
    api.post<{ message: string }>(`/api/procedures/${id}/rollback?toVersion=${toVersion}`),

  disable: (id: string) =>
    api.post<{ message: string }>(`/api/procedures/${id}/disable`),
}
