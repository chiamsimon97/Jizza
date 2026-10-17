import { api } from '@/lib/api'
import type { TaskDto } from '@/types'

export const tasksApi = {
  list: () =>
    api.get<TaskDto[]>('/api/tasks'),

  create: (task: Omit<TaskDto, 'id' | 'status' | 'nextRunAt'>) =>
    api.post<TaskDto>('/api/tasks', task),

  cancel: (id: string) =>
    api.post<void>(`/api/tasks/${id}/cancel`),

  setRepetitive: (id: string, repetitive: boolean) =>
    api.patch<void>(`/api/tasks/${id}/repetitive`, { repetitive }),

  unbindProcedure: (id: string) =>
    api.post<void>(`/api/tasks/${id}/unbind`),
}
