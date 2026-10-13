import { api } from '@/lib/api'
import type { AssistantReply } from '@/types'

export const chatApi = {
  /** {@code screen} is the page the user is on, so Jizza can act on the app. */
  send: (message: string, conversationId?: string, screen?: string) =>
    api.post<AssistantReply>('/api/chat', { message, conversationId, screen }),

  sendIncognito: (message: string, screen?: string) =>
    api.post<AssistantReply>('/api/chat?incognito=true', { message, screen }),
}
