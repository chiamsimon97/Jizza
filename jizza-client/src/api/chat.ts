import { api } from '@/lib/api'
import type { AssistantReply } from '@/types'

export const chatApi = {
  send: (message: string, conversationId?: string) =>
    api.post<AssistantReply>('/api/chat', { message, conversationId }),

  sendIncognito: (message: string) =>
    api.post<AssistantReply>('/api/chat?incognito=true', { message }),
}
