import { api } from '@/lib/api'
import type { NotificationDto } from '@/types'

export const notificationsApi = {
  list: (unreadOnly = false) =>
    api.get<NotificationDto[]>(`/api/notifications${unreadOnly ? '?unread=true' : ''}`),

  get: (id: string) =>
    api.get<NotificationDto>(`/api/notifications/${id}`),

  markRead: (id: string) =>
    api.post<void>(`/api/notifications/${id}/read`),

  answer: (id: string, kind: string, instruction?: string, feedback?: string) =>
    api.post<void>(`/api/notifications/${id}/answer`, { kind, instruction, feedback }),
}
