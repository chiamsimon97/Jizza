import { api } from '@/lib/api'
import type { NotificationDto, NotificationSummary } from '@/types'

/** GO_BACK: back to an earlier step of the run (its id as `instruction`) to choose again. */
export type AnswerKind = 'APPROVE' | 'DENY' | 'SUBMIT_INSTRUCTION' | 'GO_BACK'

export const notificationsApi = {
  list: (unreadOnly = false) =>
    api.get<NotificationDto[]>(`/api/notifications${unreadOnly ? '?unreadOnly=true' : ''}`),

  summary: () => api.get<NotificationSummary>('/api/notifications/summary'),

  get: (id: string) =>
    api.get<NotificationDto>(`/api/notifications/${id}`),

  markRead: (id: string) =>
    api.post<void>(`/api/notifications/${id}/read`),

  markUnread: (id: string) =>
    api.post<void>(`/api/notifications/${id}/unread`),

  markAllRead: () =>
    api.post<{ message: string }>('/api/notifications/read-all'),

  archive: (id: string) =>
    api.post<NotificationDto>(`/api/notifications/${id}/archive`),

  unarchive: (id: string) =>
    api.post<NotificationDto>(`/api/notifications/${id}/unarchive`),

  /** Hides the item until then; null brings it back now. */
  snooze: (id: string, until: string | null) =>
    api.post<NotificationDto>(`/api/notifications/${id}/snooze`, { until }),

  /** Asks for a fresh step-up: approving can release a paused run or bind a procedure. */
  /** `inputs`: on an approval, the values set on a workflow step's approval card. */
  answer: (id: string, kind: AnswerKind, instruction?: string, feedback?: string, inputs?: Record<string, unknown>) =>
    api.post<NotificationDto>(`/api/notifications/${id}/answer`, { kind, instruction, feedback, inputs }),
}
