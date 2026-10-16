import { api } from '@/lib/api'
import type { RunDto, SkillDto, WorkflowDto } from '@/types'

export const workflowsApi = {
  list: () =>
    api.get<WorkflowDto[]>('/api/workflows'),

  save: (workflow: Partial<WorkflowDto>) =>
    api.post<WorkflowDto>('/api/workflows', workflow),

  run: (id: string, version?: number, input?: unknown) =>
    api.post<RunDto>(`/api/workflows/${id}/run`, { version, input }),

  runs: (id: string) =>
    api.get<RunDto[]>(`/api/workflows/${id}/runs`),

  cancelRun: (runId: string) =>
    api.post<void>(`/api/runs/${runId}/cancel`),

  resumeRun: (runId: string, instruction?: string, approved?: boolean) =>
    api.post<void>(`/api/runs/${runId}/resume`, { instruction, approved }),

  saveAsSkill: (id: string, name: string, description?: string) =>
    api.post<SkillDto>(`/api/workflows/${id}/save-skill`, { name, description }),

  skills: () =>
    api.get<SkillDto[]>('/api/skills'),
}
