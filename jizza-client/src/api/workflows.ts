import { api } from '@/lib/api'
import type { LibraryEntry, ReuseProfile, RunDto, RunHistoryPage, SkillDto, WorkflowDto } from '@/types'

export const workflowsApi = {
  list: () =>
    api.get<WorkflowDto[]>('/api/workflows'),

  get: (id: string) =>
    api.get<WorkflowDto>(`/api/workflows/${id}`),

  save: (workflow: Partial<WorkflowDto>) =>
    api.post<WorkflowDto>('/api/workflows', workflow),

  run: (id: string, version?: number, input?: unknown) =>
    api.post<RunDto>(`/api/workflows/${id}/run`, { version, input }),

  /** The workflow's most recent run, newest first, from the run history. */
  latestRuns: (id: string, limit = 1) =>
    api.get<RunHistoryPage>(`/api/runs?workflowId=${encodeURIComponent(id)}&limit=${limit}`)
      .then(page => page.items),

  getRun: (runId: string) =>
    api.get<RunDto>(`/api/runs/${runId}`),

  cancelRun: (runId: string) =>
    api.post<void>(`/api/runs/${runId}/cancel`),

  resumeRun: (runId: string, instruction?: string, approved?: boolean) =>
    api.post<void>(`/api/runs/${runId}/resume`, { instruction, approved }),

  saveAsSkill: (id: string, name: string, description?: string) =>
    api.post<SkillDto>(`/api/workflows/${id}/save-skill`, { name, description }),

  skills: () =>
    api.get<SkillDto[]>('/api/skills'),

  /** The workflows Jizza may reuse, with their guardrails. */
  library: () =>
    api.get<LibraryEntry[]>('/api/workflows/library'),

  setReuse: (id: string, reuse: ReuseProfile) =>
    api.put<LibraryEntry>(`/api/workflows/${id}/reuse`, reuse),

  forgetReuse: (id: string) =>
    api.delete<LibraryEntry>(`/api/workflows/${id}/reuse`),
}
