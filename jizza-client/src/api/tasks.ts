import { api } from '@/lib/api'
import type { TaskDto } from '@/types'

export const tasksApi = {
  list: () =>
    api.get<TaskDto[]>('/api/tasks'),

  create: (task: Pick<TaskDto, 'title' | 'agentId' | 'scheduleType' | 'cronOrRrule' | 'runAt' | 'timezone' | 'repetitive'>) =>
    api.post<TaskDto>('/api/tasks', task),

  cancel: (id: string) =>
    api.delete<void>(`/api/tasks/${id}`),

  setRepetitive: (id: string, repetitive: boolean) =>
    api.post<TaskDto>(`/api/tasks/${id}/repetitive`, { repetitive }),

  unbindProcedure: (id: string) =>
    api.post<void>(`/api/tasks/${id}/unbind`),
}
