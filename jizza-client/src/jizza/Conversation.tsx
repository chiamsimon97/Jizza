import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { ArrowUpRight, Check, Mic, MicOff, Send } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { agentsApi } from '@/api/agents'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { Button } from '@/components/ui/Button'
import { useAssistantStore, type AssistantMessage } from './assistantStore'
import { JizzaOrb } from './JizzaOrb'
import { useDictation } from './useSpeech'

const SUGGESTIONS = [
  'Create a workflow that summarises my week every Friday',
  'Schedule a task to check my inbox every morning at 8',
  'Show me what my agents cost this month',
  'Switch to dark mode',
]

function greeting() {
  const h = new Date().getHours()
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'
}

interface ConversationProps {
  compact?: boolean
  autoFocus?: boolean
}

/** The conversation with Jizza, shared by the Chat page and the dock so it is one thread everywhere. */
export function Conversation({ compact = false, autoFocus = false }: ConversationProps) {
  const { messages, thinking, incognito, send } = useAssistantStore()
  const [input, setInput] = useState('')
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  const onDictated = useCallback((text: string) => { void send(text) }, [send])
  const dictation = useDictation(onDictated)

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [messages, thinking])
  useEffect(() => { if (autoFocus) inputRef.current?.focus() }, [autoFocus])

  // Grow the message box with its content, up to the max-height in CSS.
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [input, dictation.interim])

  function submit(e?: FormEvent) {
    e?.preventDefault()
    const text = input.trim()
    if (!text || thinking) return
    setInput('')
    void send(text)
  }

  const pad = compact ? 'px-3' : 'px-4 sm:px-6'
  const width = compact ? '' : 'mx-auto max-w-3xl'

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className={`${width} flex min-h-full flex-col gap-4 ${pad} py-5`}>
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center gap-5 py-6 text-center animate-fade-in-up">
              <JizzaOrb size={compact ? 52 : 72} state={dictation.listening ? 'listening' : 'idle'} />
              <div>
                <h3 className={`${compact ? 'text-lg' : 'text-2xl'} font-semibold tracking-tight text-gray-900 dark:text-gray-100`}>
                  {incognito ? 'Nothing here is saved' : `${greeting()}. What shall we do?`}
                </h3>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  {incognito
                    ? 'Incognito chats are not stored in your history.'
                    : 'Tell me what you need. I can open pages, draft workflows and tasks, and only stop when I need you.'}
                </p>
              </div>
              <div className={`stagger grid w-full gap-2 ${compact ? '' : 'max-w-xl sm:grid-cols-2'}`}>
                {SUGGESTIONS.map(text => (
                  <button
                    key={text}
                    type="button"
                    onClick={() => void send(text)}
                    className="group flex items-center justify-between gap-2 rounded-xl border border-gray-200 bg-white px-4 py-3 text-left text-sm text-gray-700 shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-brand-700"
                  >
                    <span>{text}</span>
                    <ArrowUpRight className="h-4 w-4 shrink-0 text-gray-400 transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-brand-500" />
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map(msg => <MessageBubble key={msg.id} msg={msg} compact={compact} onChoose={t => void send(t)} />)}

          {thinking && (
            <div className="flex items-center gap-2.5 animate-fade-in">
              <JizzaOrb size={28} state="thinking" tracking={false} />
              <span className="text-sm text-gray-500 dark:text-gray-400">Working on it…</span>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      <form onSubmit={submit} className={`${pad} pb-3 pt-2`}>
        <div className={`${width} flex items-end gap-2 rounded-2xl border border-gray-300 bg-white p-2 shadow-sm transition-all focus-within:border-brand-500 focus-within:ring-4 focus-within:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900`}>
          {dictation.supported && (
            <button
              type="button"
              onClick={dictation.listening ? dictation.stop : dictation.start}
              aria-label={dictation.listening ? 'Stop listening' : 'Speak to Jizza'}
              title={dictation.listening ? 'Stop listening' : 'Speak to Jizza'}
              className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl transition-all active:scale-90 ${
                dictation.listening
                  ? 'bg-red-500 text-white shadow-lg shadow-red-500/30'
                  : 'text-gray-500 hover:bg-gray-100 hover:text-brand-600 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-brand-300'
              }`}
            >
              {dictation.listening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </button>
          )}
          <textarea
            ref={inputRef}
            rows={1}
            value={dictation.listening ? dictation.interim : input}
            onChange={e => setInput(e.target.value)}
            placeholder={dictation.listening ? 'Listening…' : incognito ? 'Incognito — not saved…' : 'Ask Jizza to do something…'}
            readOnly={dictation.listening}
            className="max-h-40 flex-1 resize-none bg-transparent px-2 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none dark:text-gray-100"
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit() } }}
          />
          <Button type="submit" disabled={!input.trim() || thinking} className="h-9 w-9 shrink-0 rounded-xl !p-0" aria-label="Send">
            <Send className="h-4 w-4" />
          </Button>
        </div>
        {!compact && (
          <p className={`${width} mt-1.5 text-center text-[11px] text-gray-400 dark:text-gray-500`}>
            Enter to send · Shift+Enter for a new line · Ctrl+K opens Jizza anywhere
          </p>
        )}
      </form>
    </div>
  )
}

function MessageBubble({ msg, compact, onChoose }: { msg: AssistantMessage; compact: boolean; onChoose: (text: string) => void }) {
  if (msg.role === 'user') {
    return (
      <div className="flex justify-end animate-fade-in-up">
        <div className={`${compact ? 'max-w-[85%]' : 'max-w-[85%] sm:max-w-[75%]'} rounded-2xl rounded-br-md bg-brand-500 px-4 py-2.5 text-sm leading-relaxed text-white shadow-sm whitespace-pre-wrap`}>
          {msg.text}
        </div>
      </div>
    )
  }
  return (
    <div className="flex items-start gap-2.5 animate-fade-in-up">
      <JizzaOrb size={28} className="mt-0.5" tracking={false} />
      <div className={`flex min-w-0 flex-col gap-2 ${compact ? 'max-w-[88%]' : 'max-w-[85%] sm:max-w-[75%]'}`}>
        {msg.helpers && msg.helpers.length > 0 && <Helpers helpers={msg.helpers} />}
        {msg.steps && msg.steps.length > 0 && (
          <ul className="flex flex-wrap gap-1.5">
            {msg.steps.map((step, i) => (
              <li key={i} className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-2.5 py-1 text-xs font-medium text-brand-700 ring-1 ring-inset ring-brand-500/20 animate-fade-in dark:bg-brand-500/10 dark:text-brand-300" style={{ animationDelay: `${i * 120}ms` }}>
                <Check className="h-3 w-3" />
                {step.label}
              </li>
            ))}
          </ul>
        )}
        <div className={`rounded-2xl rounded-tl-md border px-4 py-2.5 text-sm leading-relaxed shadow-sm whitespace-pre-wrap ${
          msg.error
            ? 'border-red-200 bg-red-50 text-red-800 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200'
            : 'border-gray-200 bg-white text-gray-900 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-100'
        }`}>
          {msg.text}
        </div>
        {msg.question && (
          <div className="rounded-2xl border border-brand-300 bg-brand-50/60 p-3 animate-fade-in-up dark:border-brand-700 dark:bg-brand-500/10">
            <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{msg.question.text}</p>
            {msg.question.options.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-2">
                {msg.question.options.map(option => (
                  <button
                    key={option}
                    type="button"
                    onClick={() => onChoose(option)}
                    className="rounded-full border border-brand-300 bg-white px-3 py-1 text-sm font-medium text-brand-700 transition-all hover:-translate-y-0.5 hover:bg-brand-500 hover:text-white active:scale-95 dark:border-brand-700 dark:bg-gray-900 dark:text-brand-300 dark:hover:bg-brand-500 dark:hover:text-white"
                  >
                    {option}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/** "Vita and Kai helped": the specialists behind a reply, each shown by its logo. */
function Helpers({ helpers }: { helpers: { agentId: string; agentName: string }[] }) {
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })
  const names = helpers.map(h => h.agentName)
  const label = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return (
    <div className="flex items-center gap-2 animate-fade-in">
      <div className="flex -space-x-1.5">
        {helpers.map(h => (
          <span key={h.agentId} title={h.agentName} className="rounded-lg ring-2 ring-white dark:ring-gray-950">
            <AgentLogo agentKey={agents?.find(a => a.id === h.agentId)?.key ?? 'other'} size={22} />
          </span>
        ))}
      </div>
      <span className="text-xs text-gray-500 dark:text-gray-400">{label} helped</span>
    </div>
  )
}
