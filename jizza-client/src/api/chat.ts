import { api } from '@/lib/api'
import type { JobDto, TurnRef } from '@/types'

export const chatApi = {
  /**
   * Starts a turn and returns at once; what it does arrives on the socket. {@code screen} is the
   * page the user is on, so Jizza can act on the app; {@code context}
   * describes what is open there (the Builder, a step's settings) for this turn only. {@code clientMessageId} makes a retry safe.
   */
  send: (body: { message: string; conversationId?: string; clientMessageId: string; screen?: string; context?: Record<string, unknown>; incognito?: boolean }) =>
    api.post<TurnRef>('/api/chat/turns', body),

  /** A job and its events after {@code afterSeq}. */
  job: (jobId: string, afterSeq = 0) =>
    api.get<JobDto>(`/api/chat/jobs/${jobId}?afterSeq=${afterSeq}`),

  cancel: (jobId: string) => api.post<void>(`/api/chat/jobs/${jobId}/cancel`),

  /** Stores a PDF or photo for a message to refer to; {@code text} is what could be read from it. */
  uploadFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload<ChatFileUpload>('/api/chat/files', form)
  },
}

export interface ChatFileUpload { id: string; name: string; contentType: string; sizeBytes: number; text: string }
