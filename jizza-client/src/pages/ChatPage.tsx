import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Send, EyeOff } from 'lucide-react'
import { chatApi } from '@/api/chat'
import { useWebSocket } from '@/hooks/useWebSocket'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Badge } from '@/components/ui/Badge'
import type { AssistantReply } from '@/types'

interface Message {
  role: 'user' | 'assistant'
  text: string
  pending?: boolean
}

export function ChatPage() {
  const [searchParams] = useSearchParams()
  const initConvId = searchParams.get('conversationId') ?? undefined

  const [conversationId, setConversationId] = useState<string | undefined>(initConvId)
  const [incognito, setIncognito] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const pendingConvRef = useRef<string | undefined>(undefined)
  const bottomRef = useRef<HTMLDivElement>(null)

  const handleChunk = useCallback((convId: string, text: string, complete: boolean) => {
    if (convId !== (pendingConvRef.current ?? convId)) return
    setMessages(prev => {
      const last = prev[prev.length - 1]
      if (last?.pending) {
        const updated = [...prev]
        updated[updated.length - 1] = { ...last, text: last.text + text, pending: !complete }
        return updated
      }
      return [...prev, { role: 'assistant', text, pending: !complete }]
    })
  }, [])

  useWebSocket({ onChatChunk: handleChunk })

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const text = input.trim()
    if (!text || sending) return
    setInput('')
    setMessages(prev => [...prev, { role: 'user', text }])
    setSending(true)
    try {
      let reply: AssistantReply
      if (incognito) {
        reply = await chatApi.sendIncognito(text)
        setMessages(prev => [...prev, { role: 'assistant', text: reply.text }])
      } else {
        pendingConvRef.current = conversationId
        reply = await chatApi.send(text, conversationId)
        if (reply.conversationId) {
          setConversationId(reply.conversationId)
          pendingConvRef.current = reply.conversationId
        }
        if (reply.text) {
          setMessages(prev => {
            const last = prev[prev.length - 1]
            if (last?.pending) return prev
            return [...prev, { role: 'assistant', text: reply.text }]
          })
        }
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Request failed'
      setMessages(prev => [...prev, { role: 'assistant', text: `Error: ${msg}` }])
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 border-b border-gray-200 dark:border-gray-700 px-4 py-3">
        <h2 className="flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">
          {incognito ? 'Incognito Chat' : conversationId ? `Conversation ${conversationId.slice(0, 8)}…` : 'New Chat'}
        </h2>
        {incognito && <Badge variant="warning"><EyeOff className="mr-1 h-3 w-3 inline" />Incognito</Badge>}
        <button
          onClick={() => { setIncognito(v => !v); setMessages([]); setConversationId(undefined) }}
          className="flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-700 dark:hover:text-gray-300"
        >
          <EyeOff className="h-4 w-4" />
          {incognito ? 'Exit incognito' : 'Incognito'}
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
        {messages.length === 0 && (
          <div className="flex h-full items-center justify-center text-sm text-gray-400 dark:text-gray-500">
            Start a conversation
          </div>
        )}
        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[75%] rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap ${
                msg.role === 'user'
                  ? 'bg-brand-500 text-white'
                  : 'bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100'
              }`}
            >
              {msg.text}
              {msg.pending && <Spinner size="sm" className="ml-2 inline" />}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <form onSubmit={handleSubmit} className="border-t border-gray-200 dark:border-gray-700 p-4">
        <div className="flex gap-2">
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            placeholder={incognito ? 'Incognito — not saved…' : 'Message Jizza…'}
            disabled={sending}
            className="flex-1 rounded-xl border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-4 py-2.5 text-sm text-gray-900 dark:text-gray-100 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-50"
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(e as unknown as FormEvent) } }}
          />
          <Button type="submit" loading={sending} disabled={!input.trim()}>
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </form>
    </div>
  )
}
