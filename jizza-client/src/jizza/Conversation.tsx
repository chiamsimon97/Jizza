import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { ArrowUpRight, Check, ChevronRight, Mic, MicOff, Send, ShieldQuestion, Square, X } from 'lucide-react'
import { notificationsApi } from '@/api/notifications'
import { useQuery } from '@tanstack/react-query'
import { agentsApi } from '@/api/agents'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { Button } from '@/components/ui/Button'
import { loadJob, useAssistantStore, type AssistantMessage } from './assistantStore'
import { JizzaOrb } from './JizzaOrb'
import { WorkflowPlanCard } from './WorkflowPlanCard'
import { AgentProposalCard } from './AgentProposalCard'
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
  /** Opens a reply's job in the Activity panel ("See how this was done"). */
  onOpenJob?: (jobId: string) => void
  /** Replaces the greeting shown before the first message, e.g. in the workflow planner. */
  intro?: { title: string; text: string; suggestions: string[] }
  placeholder?: string
  /** Shows what Jizza and the specialists said to each other under each reply, in place. */
  inlineDiscussion?: boolean
}

/** The conversation with Jizza, shared by the Chat page and the dock so it is one thread everywhere. */
export function Conversation({ compact = false, autoFocus = false, onOpenJob, intro, placeholder, inlineDiscussion = false }: ConversationProps) {
  const { messages, thinking, incognito, send, stop } = useAssistantStore()
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
                  {intro?.title ?? (incognito ? 'Nothing here is saved' : `${greeting()}. What shall we do?`)}
                </h3>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  {intro?.text ?? (incognito
                    ? 'Incognito chats are not stored in your history.'
                    : 'Tell me what you need. I can open pages, draft workflows and tasks, and only stop when I need you.')}
                </p>
              </div>
              <div className={`stagger grid w-full gap-2 ${compact ? '' : 'max-w-xl sm:grid-cols-2'}`}>
                {(intro?.suggestions ?? SUGGESTIONS).map(text => (
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

          {messages.map(msg => (
            <MessageBubble key={msg.id} msg={msg} compact={compact} onChoose={t => void send(t)} onOpenJob={onOpenJob}
              inlineDiscussion={inlineDiscussion} onRevise={() => inputRef.current?.focus()} />
          ))}

          {/* Between sending and the job starting there is no reply bubble yet to show progress in. */}
          {thinking && messages[messages.length - 1]?.role === 'user' && (
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
            placeholder={dictation.listening ? 'Listening…' : incognito ? 'Incognito — not saved…' : placeholder ?? 'Ask Jizza to do something…'}
            readOnly={dictation.listening}
            className="max-h-40 flex-1 resize-none bg-transparent px-2 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none dark:text-gray-100"
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit() } }}
          />
          {thinking ? (
            <Button type="button" variant="secondary" onClick={() => void stop()} className="h-9 w-9 shrink-0 rounded-xl !p-0" aria-label="Stop" title="Stop">
              <Square className="h-3.5 w-3.5 fill-current" />
            </Button>
          ) : (
            <Button type="submit" disabled={!input.trim()} className="h-9 w-9 shrink-0 rounded-xl !p-0" aria-label="Send">
              <Send className="h-4 w-4" />
            </Button>
          )}
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

function MessageBubble({ msg, compact, onChoose, onOpenJob, inlineDiscussion, onRevise }: {
  msg: AssistantMessage; compact: boolean; onChoose: (text: string) => void; onOpenJob?: (jobId: string) => void
  inlineDiscussion?: boolean; onRevise?: () => void
}) {
  if (msg.role === 'event') {
    return (
      <div className="flex items-center gap-2 py-1 animate-fade-in" role="note">
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
        <span className="max-w-[80%] text-center text-xs text-gray-500 dark:text-gray-400">{msg.text}</span>
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
      </div>
    )
  }
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
          {msg.streaming && !msg.text && <TypingDots />}
          {msg.streaming && msg.text && <span className="ml-0.5 inline-block h-3.5 w-1.5 translate-y-0.5 animate-pulse rounded-sm bg-brand-500/70" />}
          {msg.stopped && <span className="mt-1 block text-xs text-gray-400 dark:text-gray-500">Stopped</span>}
        </div>
        {msg.plan && <WorkflowPlanCard plan={msg.plan} onRevise={onRevise} />}
        {msg.proposal && <AgentProposalCard proposal={msg.proposal} />}
        {msg.jobId && !msg.streaming && <Approvals jobId={msg.jobId} />}
        {msg.jobId && inlineDiscussion && !msg.streaming && <Discussion jobId={msg.jobId} />}
        {msg.jobId && onOpenJob && !inlineDiscussion && !msg.streaming && (
          <button
            type="button"
            onClick={() => onOpenJob(msg.jobId!)}
            className="inline-flex w-fit items-center gap-0.5 text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300"
          >
            See how this was done
            <ChevronRight className="h-3 w-3" />
          </button>
        )}
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

/**
 * Calls held for the user's approval while Jizza answered, each with Approve and Deny. Answering
 * here is the same as answering on the Notifications page; either way Jizza carries on in this
 * conversation and the card turns into the line saying what was decided.
 */
function Approvals({ jobId }: { jobId: string }) {
  const job = useAssistantStore(s => s.jobs[jobId])
  const answeredIds = useAssistantStore(s => s.messages.filter(m => m.role === 'event' && m.notificationId).map(m => m.notificationId!).join(','))
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (job && job.status === 'awaiting_user' && !job.loaded) void loadJob(jobId)
  }, [job, jobId])

  if (!job) return null
  const answered = new Set(answeredIds.split(',').filter(Boolean))
  const pending = job.events.filter(e => e.type === 'approval.required' && !answered.has(e.payload.notificationId))
  if (pending.length === 0) return null

  const answer = async (id: string, kind: 'APPROVE' | 'DENY') => {
    setBusy(id)
    setError(null)
    try {
      await notificationsApi.answer(id, kind)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not go through.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {pending.map(e => (
        <div key={e.seq} className="rounded-2xl border border-amber-300 bg-amber-50/70 p-3 animate-fade-in-up dark:border-amber-500/40 dark:bg-amber-500/10">
          <p className="flex items-start gap-2 text-sm text-gray-900 dark:text-gray-100">
            <ShieldQuestion className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-300" />
            <span><span className="font-medium">{e.agentName ?? 'An agent'}</span> needs your approval to run <span className="font-medium">{e.payload.label ?? e.payload.tool}</span>.</span>
          </p>
          {e.payload.reason && <p className="ml-6 mt-0.5 text-xs text-gray-600 dark:text-gray-400">{e.payload.reason}</p>}
          <div className="ml-6 mt-2 flex gap-2">
            <Button size="sm" loading={busy === e.payload.notificationId} disabled={busy !== null} onClick={() => void answer(e.payload.notificationId, 'APPROVE')}>
              <Check className="h-3.5 w-3.5" /> Approve
            </Button>
            <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => void answer(e.payload.notificationId, 'DENY')}>
              <X className="h-3.5 w-3.5" /> Deny
            </Button>
          </div>
          {error && <p className="ml-6 mt-1.5 text-xs text-red-600 dark:text-red-400">{error}</p>}
        </div>
      ))}
    </div>
  )
}

/**
 * What Jizza asked the specialists and what they answered for this reply, folded by default. In
 * the workflow planner this is the discussion the plan came out of.
 */
function Discussion({ jobId }: { jobId: string }) {
  const job = useAssistantStore(s => s.jobs[jobId])
  const [open, setOpen] = useState(false)
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })

  useEffect(() => { if (open) void loadJob(jobId) }, [open, jobId])

  const lines = job?.events.filter(e => e.type === 'agent.message') ?? []
  if (job?.loaded && lines.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="inline-flex w-fit items-center gap-0.5 text-xs font-medium text-gray-500 transition-colors hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300"
      >
        {open ? 'Hide the discussion with the agents' : 'Show the discussion with the agents'}
        <ChevronRight className={`h-3 w-3 transition-transform ${open ? 'rotate-90' : ''}`} />
      </button>
      {open && (
        <ul className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-gray-50 p-2.5 animate-fade-in dark:border-gray-800 dark:bg-gray-950">
          {lines.length === 0 && <li className="text-xs text-gray-500 dark:text-gray-400">Loading…</li>}
          {lines.map(e => (
            <li key={e.seq} className="flex gap-2">
              <span className="shrink-0"><AgentLogo agentKey={agents?.find(a => a.id === e.agentId)?.key ?? 'other'} size={18} /></span>
              <div className="min-w-0">
                <p className="text-xs font-medium text-gray-700 dark:text-gray-200">
                  {e.agentName ?? 'Agent'} to {e.payload.toAgentName ?? 'Agent'}{e.payload.kind === 'failed' ? ' · could not finish' : ''}
                </p>
                <p className="whitespace-pre-wrap text-xs text-gray-600 dark:text-gray-400">{e.payload.text}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function TypingDots() {
  return (
    <span className="inline-flex items-center gap-1 py-1" aria-label="Jizza is working">
      {[0, 1, 2].map(i => (
        <span key={i} className="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400 dark:bg-gray-500" style={{ animationDelay: `${i * 150}ms` }} />
      ))}
    </span>
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
