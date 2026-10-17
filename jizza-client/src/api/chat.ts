import { api } from '@/lib/api'
import type { JobDto, TurnRef } from '@/types'

export const chatApi = {
  /**
   * Starts a turn and returns at once; what it does arrives on the socket. {@code screen} is the
   * page the user is on, so Jizza can act on the app. {@code clientMessageId} makes a retry safe.
   */
  send: (body: { message: string; conversationId?: string; clientMessageId: string; screen?: string; incognito?: boolean }) =>
    api.post<TurnRef>('/api/chat/turns', body),

  /** A job and its events after {@code afterSeq}. */
  job: (jobId: string, afterSeq = 0) =>
    api.get<JobDto>(`/api/chat/jobs/${jobId}?afterSeq=${afterSeq}`),

  cancel: (jobId: string) => api.post<void>(`/api/chat/jobs/${jobId}/cancel`),
}
