import { useEffect, useRef } from 'react'
import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useAuthStore } from '@/store/authStore'
import type { MemoryRealtimeEvent } from '@/types'

interface UseWebSocketOptions {
  onChatChunk?: (conversationId: string, text: string, complete: boolean) => void
  onNotification?: (payload: unknown) => void
  /** Something in the user's memory changed. Carries ids only; refetch to see what. */
  onMemoryEvent?: (event: MemoryRealtimeEvent) => void
}

/**
 * The user's realtime pushes. The server names each socket after the signed-in user and sends to
 * that user's own queues, so nobody else's events can arrive here.
 */
export function useWebSocket(options: UseWebSocketOptions = {}) {
  const clientRef = useRef<Client | null>(null)
  // Handlers are read through a ref so a new callback doesn't tear down the connection.
  const handlers = useRef(options)
  handlers.current = options
  const { isAuthenticated } = useAuthStore()

  useEffect(() => {
    if (!isAuthenticated) return

    const parse = (msg: IMessage) => {
      try { return JSON.parse(msg.body) } catch { return null /* ignore malformed frames */ }
    }
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/user/queue/chat', msg => {
          const data = parse(msg) as { conversationId: string; chunk: string; last: boolean } | null
          if (data) handlers.current.onChatChunk?.(data.conversationId, data.chunk, data.last)
        })
        client.subscribe('/user/queue/notifications', msg => {
          const data = parse(msg)
          if (data) handlers.current.onNotification?.(data)
        })
        client.subscribe('/user/queue/memory', msg => {
          const data = parse(msg) as MemoryRealtimeEvent | null
          if (data) handlers.current.onMemoryEvent?.(data)
        })
      },
    })

    client.activate()
    clientRef.current = client

    return () => {
      clientRef.current = null
      client.deactivate()
    }
  }, [isAuthenticated])

  return clientRef
}
