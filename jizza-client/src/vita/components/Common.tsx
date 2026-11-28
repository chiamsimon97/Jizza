import { formatDay } from '@/portfolio/components/AsOf'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Hourglass, MessageSquare } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { setChatDraft } from '@/jizza/chatDraft'
import { useAssistantStore } from '@/jizza/assistantStore'
import { PHASE_WORDS, type HealthView } from '../nav'

/** A view's heading, its one-line purpose, and its actions on the right. */
export function ViewTitle({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-2">
      <div className="min-w-0">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
        {sub && <p className="text-sm text-gray-500 dark:text-gray-400">{sub}</p>}
      </div>
      {children && <div className="flex flex-wrap items-center gap-2">{children}</div>}
    </div>
  )
}

/** "Ask Vita" at the foot of every view: the question starts with the view's context. */
export function AskBox({ agentName, view, personName }: { agentName: string; view: HealthView; personName: string }) {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const ask = () => {
    setChatDraft(`@${agentName} ${view.ask}${q.trim()}`)
    useAssistantStore.getState().newConversation()
    navigate('/chat')
  }
  return (
    <form onSubmit={e => { e.preventDefault(); ask() }}
      className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-gray-900 sm:flex-row sm:items-center">
      <span className="flex shrink-0 items-center gap-1.5 text-xs font-medium text-[rgb(var(--agent-health))]">
        <MessageSquare className="h-3.5 w-3.5" aria-hidden />Ask {agentName} · {view.ask.replace(/:\s*$/, '')}
      </span>
      <input aria-label={`Ask ${agentName}`} className={`${fieldClass} !py-1.5`} value={q} onChange={e => setQ(e.target.value)}
        placeholder={`Type a question about ${personName === 'You' ? 'your' : `${personName}’s`} record`} />
      <Button size="sm" type="submit">Ask</Button>
    </form>
  )
}

/** A view a later update brings: what it will hold, and when. */
export function NotYet({ view }: { view: HealthView }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-gray-300 py-14 text-center dark:border-gray-700">
      <div className="grid h-14 w-14 place-items-center rounded-2xl bg-rose-50 text-[rgb(var(--agent-health))] dark:bg-rose-900/20">
        <Hourglass className="h-7 w-7" strokeWidth={1.5} aria-hidden />
      </div>
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{view.label} comes with {PHASE_WORDS[view.phase] ?? 'a later update'}</h3>
      <p className="max-w-sm text-sm text-gray-500 dark:text-gray-400">{view.about}</p>
    </div>
  )
}

/** A chip that can be pressed on and off; the state is in words for screen readers too. */
export function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" aria-pressed={on} onClick={onClick}
      className={`rounded-full border px-3 py-1.5 text-sm transition-colors ${on
        ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:border-brand-400 dark:bg-brand-900/30 dark:text-brand-200'
        : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
      {children}
    </button>
  )
}

/** The read-only banner on a closed record (7.19). */
export function ClosedBanner({ name, on, onExport }: { name: string; on?: string | null; onExport?: () => void }) {
  return (
    <p role="status" className="flex flex-wrap items-center gap-x-2 rounded-lg bg-gray-100 px-3 py-2 text-sm text-gray-700 dark:bg-gray-800 dark:text-gray-200">
      <span>{on ? `Record closed on ${formatDay(on.slice(0, 10))}. ` : ''}{name}’s record stays here to read under “Past records”, and nothing can be added to it.</span>
      {onExport && <button type="button" onClick={onExport} className="text-sm text-brand-700 underline dark:text-brand-300">Export it from Profile</button>}
    </p>
  )
}

export function Disclaimer() {
  return <p className="text-center text-xs text-gray-500 dark:text-gray-400">Prepared by Vita from your own records. Not medical advice.</p>
}
