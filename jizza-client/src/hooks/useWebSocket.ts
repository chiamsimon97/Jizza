import { useCallback, useEffect, useRef } from 'react'
import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useAuthStore } from '@/store/authStore'

interface UseWebSocketOptions {
  onChatChunk?: (conversationId: string, text: string, complete: boolean) => void
  onNotification?: (payload: unknown) => void
}

export function useWebSocket({ onChatChunk, onNotification }: UseWebSocketOptions = {}) {
  const clientRef = useRef<Client | null>(null)
  const { isAuthenticated } = useAuthStore()

  const handleChatMessage = useCallback(
    (msg: IMessage) => {
      try {
        const data = JSON.parse(msg.body) as { conversationId: string; text: string; complete: boolean }
        onChatChunk?.(data.conversationId, data.text, data.complete)
      } catch { /* ignore malformed frames */ }
    },
    [onChatChunk],
  )

  const handleNotification = useCallback(
    (msg: IMessage) => {
      try {
        onNotification?.(JSON.parse(msg.body))
      } catch { /* ignore */ }
    },
    [onNotification],
  )

  useEffect(() => {
    if (!isAuthenticated) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/user/queue/chat', handleChatMessage)
        client.subscribe('/user/queue/notifications', handleNotification)
      },
    })

    client.activate()
    clientRef.current = client

    return () => {
      client.deactivate()
    }
  }, [isAuthenticated, handleChatMessage, handleNotification])

  return clientRef.current
}
