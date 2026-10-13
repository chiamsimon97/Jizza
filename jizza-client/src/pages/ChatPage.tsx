import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Send, EyeOff, Sparkles, Plus, ArrowUpRight } from 'lucide-react'
import { chatApi } from '@/api/chat'
import { useWebSocket } from '@/hooks/useWebSocket'
import { Button } from '@/components/ui/Button'
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
  const inputRef = useRef<HTMLTextAreaElement>(null)

  // Grow the message box with its content, up to the max-height in CSS.
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [input])

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

  const suggestions = [
    'Summarise my unread notifications',
    'What tasks are scheduled this week?',
    'Draft a workflow for my weekly report',
    'Which connections need attention?',
  ]
  const waitingForReply = sending && messages[messages.length - 1]?.role === 'user'

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 border-b border-gray-200 bg-white/70 px-4 py-3 backdrop-blur dark:border-gray-800 dark:bg-gray-900/70 sm:px-6">
        <div className="grid h-8 w-8 place-items-center rounded-lg brand-gradient text-white shadow-glow">
          <Sparkles className="h-4 w-4" />
        </div>
        <h2 className="flex-1 truncate text-sm font-semibold text-gray-900 dark:text-gray-100">
          {incognito ? 'Incognito Chat' : conversationId ? `Conversation ${conversationId.slice(0, 8)}…` : 'New Chat'}
        </h2>
        {incognito && <Badge variant="warning"><EyeOff className="mr-1 h-3 w-3 inline" />Incognito</Badge>}
        {(messages.length > 0 || conversationId) && (
          <Button size="sm" variant="ghost" onClick={() => { setMessages([]); setConversationId(undefined) }}>
            <Plus className="h-4 w-4" />
            <span className="hidden sm:inline">New</span>
          </Button>
        )}
        <button
          onClick={() => { setIncognito(v => !v); setMessages([]); setConversationId(undefined) }}
          aria-pressed={incognito}
          className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all active:scale-95 ${
            incognito
              ? 'bg-yellow-100 text-yellow-800 hover:bg-yellow-200 dark:bg-yellow-500/15 dark:text-yellow-300'
              : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
          }`}
        >
          <EyeOff className="h-4 w-4" />
          <span className="hidden sm:inline">{incognito ? 'Exit incognito' : 'Incognito'}</span>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto flex min-h-full max-w-3xl flex-col gap-4 px-4 py-6 sm:px-6">
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center gap-6 py-10 text-center animate-fade-in-up">
              <div className="grid h-16 w-16 place-items-center rounded-2xl brand-gradient text-white shadow-glow">
                {incognito ? <EyeOff className="h-7 w-7" /> : <Sparkles className="h-7 w-7" />}
              </div>
              <div>
                <h3 className="text-2xl font-semibold tracking-tight text-gray-900 dark:text-gray-100">
                  {incognito ? 'Nothing here is saved' : 'How can I help today?'}
                </h3>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  {incognito ? 'Incognito chats are not stored in your history.' : 'Ask anything, or start with one of these.'}
                </p>
              </div>
              <div className="stagger grid w-full max-w-xl gap-2 sm:grid-cols-2">
                {suggestions.map(text => (
                  <button
                    key={text}
                    type="button"
                    onClick={() => { setInput(text); inputRef.current?.focus() }}
                    className="group flex items-center justify-between gap-2 rounded-xl border border-gray-200 bg-white px-4 py-3 text-left text-sm text-gray-700 shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-brand-700"
                  >
                    <span>{text}</span>
                    <ArrowUpRight className="h-4 w-4 shrink-0 text-gray-400 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-brand-500" />
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((msg, i) => (
            <div key={i} className={`flex items-end gap-2 animate-fade-in-up ${msg.role === 'user' ? 'flex-row-reverse' : ''}`}>
              {msg.role === 'assistant' && (
                <div className="mb-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full brand-gradient text-white">
                  <Sparkles className="h-3.5 w-3.5" />
                </div>
              )}
              <div
                className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap shadow-sm sm:max-w-[75%] ${
                  msg.role === 'user'
                    ? 'rounded-br-md bg-brand-500 text-white'
                    : 'rounded-bl-md border border-gray-200 bg-white text-gray-900 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-100'
                }`}
              >
                {msg.text}
                {msg.pending && <TypingDots className="ml-2" />}
              </div>
            </div>
          ))}
          {waitingForReply && (
            <div className="flex items-end gap-2 animate-fade-in">
              <div className="grid h-7 w-7 shrink-0 place-items-center rounded-full brand-gradient text-white">
                <Sparkles className="h-3.5 w-3.5" />
              </div>
              <div className="rounded-2xl rounded-bl-md border border-gray-200 bg-white px-4 py-3 dark:border-gray-800 dark:bg-gray-900">
                <TypingDots />
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      <form onSubmit={handleSubmit} className="px-4 pb-4 pt-2 sm:px-6">
        <div className="mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border border-gray-300 bg-white p-2 shadow-sm transition-all focus-within:border-brand-500 focus-within:ring-4 focus-within:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900">
          <textarea
            ref={inputRef}
            rows={1}
            value={input}
            onChange={e => setInput(e.target.value)}
            placeholder={incognito ? 'Incognito — not saved…' : 'Message Jizza…'}
            disabled={sending}
            className="max-h-40 flex-1 resize-none bg-transparent px-2 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none disabled:opacity-50 dark:text-gray-100"
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(e as unknown as FormEvent) } }}
          />
          <Button type="submit" loading={sending} disabled={!input.trim()} className="h-9 w-9 shrink-0 !p-0 rounded-xl" aria-label="Send">
            {!sending && <Send className="h-4 w-4" />}
          </Button>
        </div>
        <p className="mx-auto mt-1.5 max-w-3xl text-center text-[11px] text-gray-400 dark:text-gray-500">
          Enter to send · Shift+Enter for a new line
        </p>
      </form>
    </div>
  )
}

function TypingDots({ className = '' }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-1 align-middle ${className}`} aria-label="Jizza is typing">
      {[0, 1, 2].map(i => (
        <span key={i} className="h-1.5 w-1.5 rounded-full bg-current opacity-60 animate-typing" style={{ animationDelay: `${i * 150}ms` }} />
      ))}
    </span>
  )
}
