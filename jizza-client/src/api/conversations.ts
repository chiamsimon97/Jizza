import { api } from '@/lib/api'
import type { ConversationDto, MessageDto } from '@/types'

export const conversationsApi = {
  list: (limit = 50) =>
    api.get<ConversationDto[]>(`/api/conversations?limit=${limit}`),

  get: (id: string) =>
    api.get<ConversationDto>(`/api/conversations/${id}`),

  messages: (id: string) =>
    api.get<MessageDto[]>(`/api/conversations/${id}/messages`),
}
