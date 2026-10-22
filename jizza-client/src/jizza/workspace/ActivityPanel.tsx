import { useEffect, useRef } from 'react'
import {
  AlertTriangle, ArrowRight, Brain, Check, ChevronDown, CircleSlash, Globe, Loader2, MousePointerClick, ShieldQuestion, Wrench, X,
} from 'lucide-react'
import { AgentLogo } from '@/components/brand/AgentLogo'
import type { JobEvent, JobStatus } from '@/types'
import { useAssistantStore, type JobView } from '../assistantStore'
import { useAgentKeys } from './useAgentKeys'

/**
 * The top half of the work panel: one card per job in this conversation. A running job is open and
 * fills in live; a finished one folds down to its one-line summary, and the chevron opens it again.
 */
export function ActivityPanel() {
  const { jobs, jobOrder, expanded, selectedJobId, toggleJob, selectJob } = useAssistantStore()
  const selectedRef = useRef<HTMLDivElement>(null)

  // "See how this was done" lands here; bring that job into view.
  useEffect(() => { selectedRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }) }, [selectedJobId])

  const ordered = [...jobOrder].reverse().map(id => jobs[id]).filter(Boolean)

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PanelTitle title="Activity" hint={ordered.length ? `${ordered.length} job${ordered.length === 1 ? '' : 's'}` : undefined} />
      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        {ordered.length === 0 ? (
          <p className="px-1 py-2 text-xs leading-relaxed text-gray-500 dark:text-gray-400">
            When Jizza works on something, the plan, who it asked and what it ran show up here.
          </p>
        ) : (
          <div className="flex flex-col gap-2">
            {ordered.map(job => (
              <div key={job.id} ref={job.id === selectedJobId ? selectedRef : undefined}>
                <JobCard
                  job={job}
                  open={expanded[job.id] ?? false}
                  selected={job.id === selectedJobId}
                  onToggle={() => toggleJob(job.id)}
                  onSelect={() => selectJob(job.id)}
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export function PanelTitle({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between px-4 pb-2 pt-3">
      <h3 className="text-[11px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">{title}</h3>
      {hint && <span className="text-[11px] text-gray-400 dark:text-gray-500">{hint}</span>}
    </div>
  )
}

function StatusIcon({ status }: { status: JobStatus }) {
  switch (status) {
    case 'running': return <Loader2 className="h-3.5 w-3.5 animate-spin text-brand-500" />
    case 'completed': return <Check className="h-3.5 w-3.5 text-emerald-500" />
    case 'failed': return <AlertTriangle className="h-3.5 w-3.5 text-red-500" />
    case 'cancelled': return <CircleSlash className="h-3.5 w-3.5 text-gray-400" />
    case 'awaiting_user': return <ShieldQuestion className="h-3.5 w-3.5 text-amber-500" />
  }
}

function elapsed(job: JobView): string | undefined {
  if (!job.finishedAt) return undefined
  const ms = new Date(job.finishedAt).getTime() - new Date(job.startedAt).getTime()
  if (!(ms >= 0)) return undefined
  return ms < 1000 ? `${ms} ms` : ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.round(ms / 60_000)} min`
}

function JobCard({ job, open, selected, onToggle, onSelect }: {
  job: JobView; open: boolean; selected: boolean; onToggle: () => void; onSelect: () => void
}) {
  const running = job.status === 'running'
  const took = elapsed(job)
  return (
    <div
      className={`rounded-xl border bg-white transition-colors dark:bg-gray-900 ${
        selected ? 'border-brand-300 ring-1 ring-brand-500/20 dark:border-brand-700' : 'border-gray-200 dark:border-gray-800'
      }`}
    >
      <div className="flex items-start gap-2 px-3 py-2.5">
        <span className="mt-0.5 shrink-0"><StatusIcon status={job.status} /></span>
        <button type="button" onClick={onSelect} className="min-w-0 flex-1 text-left">
          <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{job.title || 'Turn'}</p>
          {!open && (
            <p className="mt-0.5 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">
              {running ? 'Working…' : job.summary || statusLine(job.status)}
            </p>
          )}
        </button>
        {took && !open && <span className="mt-0.5 shrink-0 text-[11px] tabular-nums text-gray-400">{took}</span>}
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={open}
          aria-label={open ? 'Fold this job' : 'Show what happened'}
          className="-mr-1 grid h-6 w-6 shrink-0 place-items-center rounded-md text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200"
        >
          <ChevronDown className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`} />
        </button>
      </div>
      {open && (
        <div className="border-t border-gray-100 px-3 pb-3 pt-2 dark:border-gray-800">
          {!job.loaded ? (
            <p className="py-1 text-xs text-gray-400">Loading…</p>
          ) : (
            <Timeline events={job.events} running={running} />
          )}
          {!running && job.summary && (
            <p className="mt-2 rounded-lg bg-gray-50 px-2.5 py-2 text-xs text-gray-600 dark:bg-gray-800/60 dark:text-gray-300">{job.summary}</p>
          )}
        </div>
      )}
    </div>
  )
}

function statusLine(status: JobStatus) {
  return status === 'failed' ? 'This one failed.' : status === 'cancelled' ? 'Stopped before it finished.' : status === 'awaiting_user' ? 'Waiting on you.' : 'Done.'
}

/**
 * Collapses the raw event stream into the steps a person would describe: a tool call is one line
 * that changes state rather than a "started" line followed by a "done" line.
 */
function steps(events: JobEvent[]): JobEvent[] {
  const out: JobEvent[] = []
  const toolIndex = new Map<string, number>()
  for (const e of events) {
    if (e.type === 'delta' || e.type === 'job.started' || e.type === 'segment.preamble' || e.type === 'job.completed') continue
    if (e.type === 'tool.call' && e.payload.id) {
      const at = toolIndex.get(e.payload.id)
      if (at !== undefined) { out[at] = e; continue }
      toolIndex.set(e.payload.id, out.length)
    }
    out.push(e)
  }
  return out
}

function Timeline({ events, running }: { events: JobEvent[]; running: boolean }) {
  const rows = steps(events)
  if (rows.length === 0) {
    return <p className="py-1 text-xs text-gray-500 dark:text-gray-400">{running ? 'Thinking…' : 'Jizza answered this directly.'}</p>
  }
  return (
    <ol className="relative flex flex-col gap-2.5 before:absolute before:bottom-1 before:left-[9px] before:top-1 before:w-px before:bg-gray-200 dark:before:bg-gray-800">
      {rows.map(e => <Step key={e.seq} e={e} />)}
      {running && (
        <li className="relative flex items-center gap-2 pl-0">
          <span className="z-10 grid h-[19px] w-[19px] place-items-center rounded-full bg-white dark:bg-gray-900"><Loader2 className="h-3 w-3 animate-spin text-brand-500" /></span>
          <span className="text-xs text-gray-500 dark:text-gray-400">Working…</span>
        </li>
      )}
    </ol>
  )
}

function Dot({ children, tone = 'gray' }: { children: React.ReactNode; tone?: 'gray' | 'brand' | 'green' | 'red' | 'amber' }) {
  const tones = {
    gray: 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400',
    brand: 'bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300',
    green: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-500/15 dark:text-emerald-300',
    red: 'bg-red-50 text-red-600 dark:bg-red-500/15 dark:text-red-300',
    amber: 'bg-amber-50 text-amber-600 dark:bg-amber-500/15 dark:text-amber-300',
  }
  return <span className={`z-10 grid h-[19px] w-[19px] shrink-0 place-items-center rounded-full ring-2 ring-white dark:ring-gray-900 ${tones[tone]}`}>{children}</span>
}

function Step({ e }: { e: JobEvent }) {
  const keys = useAgentKeys()
  const p = e.payload
  switch (e.type) {
    case 'agent.message': {
      const from = e.agentName ?? 'Agent'
      const to = p.toAgentName ?? 'Agent'
      const fromKey = (e.agentId && keys[e.agentId]) || 'other'
      return (
        <li className="relative flex gap-2">
          <span className="z-10 shrink-0 rounded-full ring-2 ring-white dark:ring-gray-900"><AgentLogo agentKey={fromKey} size={19} /></span>
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1 text-xs font-medium text-gray-700 dark:text-gray-200">
              {from} <ArrowRight className="h-3 w-3 text-gray-400" /> {to}
              {p.kind === 'failed' && <span className="text-red-500"> · couldn't finish</span>}
            </p>
            <Quote text={p.text} />
          </div>
        </li>
      )
    }
    case 'tool.call': {
      const tone = p.state === 'failed' ? 'red' : p.state === 'done' ? 'green' : 'brand'
      return (
        <li className="relative flex gap-2">
          <Dot tone={tone}>{p.state === 'started' ? <Loader2 className="h-2.5 w-2.5 animate-spin" /> : p.state === 'failed' ? <X className="h-2.5 w-2.5" /> : <Wrench className="h-2.5 w-2.5" />}</Dot>
          <div className="min-w-0 flex-1 text-xs text-gray-700 dark:text-gray-300">
            <span className="font-medium">{e.agentName ?? 'Agent'}</span> used <span className="font-medium">{p.label ?? p.tool}</span>
            {typeof p.ms === 'number' && <span className="text-gray-400"> · {p.ms} ms</span>}
            {p.error && <p className="mt-0.5 text-red-600 dark:text-red-400">{p.error}</p>}
          </div>
        </li>
      )
    }
    case 'tool.api':
      return (
        <li className="relative flex gap-2">
          <Dot tone={p.status >= 400 ? 'red' : 'gray'}><Globe className="h-2.5 w-2.5" /></Dot>
          <div className="min-w-0 flex-1 text-xs text-gray-700 dark:text-gray-300">
            Called <span className="font-medium">{p.service}</span>{' '}
            <code className="rounded bg-gray-100 px-1 py-px font-mono text-[11px] text-gray-600 dark:bg-gray-800 dark:text-gray-300">{p.method} {p.path}</code>
            <span className={p.status >= 400 ? 'text-red-500' : 'text-gray-400'}> · {p.status}</span>
            {typeof p.ms === 'number' && <span className="text-gray-400"> · {p.ms} ms</span>}
          </div>
        </li>
      )
    case 'memory.used':
      return (
        <li className="relative flex gap-2">
          <Dot tone="brand"><Brain className="h-2.5 w-2.5" /></Dot>
          <p className="text-xs text-gray-700 dark:text-gray-300">
            <span className="font-medium">{e.agentName ?? 'Agent'}</span>{' '}
            {p.purpose === 'instructions'
              ? (p.count === 0 ? 'found no standing instruction for this' : `checked ${p.count} standing ${p.count === 1 ? 'instruction' : 'instructions'}`)
              : `recalled ${p.count} ${p.count === 1 ? 'memory' : 'memories'}`}
          </p>
        </li>
      )
    case 'memory.saved':
      return (
        <li className="relative flex gap-2">
          <Dot tone="green"><Brain className="h-2.5 w-2.5" /></Dot>
          <p className="text-xs text-gray-700 dark:text-gray-300">Saved to memory: <span className="text-gray-500 dark:text-gray-400">{p.text}</span></p>
        </li>
      )
    case 'approval.required':
      return (
        <li className="relative flex gap-2">
          <Dot tone="amber"><ShieldQuestion className="h-2.5 w-2.5" /></Dot>
          <div className="text-xs text-gray-700 dark:text-gray-300">
            <span className="font-medium">{e.agentName ?? 'Agent'}</span> needs your approval to run <span className="font-medium">{p.label ?? p.tool}</span>
            {p.reason && <p className="mt-0.5 text-gray-500 dark:text-gray-400">{p.reason}</p>}
          </div>
        </li>
      )
    case 'ui.action':
      return (
        <li className="relative flex gap-2">
          <Dot><MousePointerClick className="h-2.5 w-2.5" /></Dot>
          <p className="text-xs text-gray-700 dark:text-gray-300">{uiActionLabel(p.type, p.payload)}</p>
        </li>
      )
    case 'job.failed':
      return (
        <li className="relative flex gap-2">
          <Dot tone="red"><AlertTriangle className="h-2.5 w-2.5" /></Dot>
          <p className="text-xs text-red-600 dark:text-red-400">{p.detail ?? p.title ?? 'Failed'}</p>
        </li>
      )
    case 'job.cancelled':
      return (
        <li className="relative flex gap-2">
          <Dot><CircleSlash className="h-2.5 w-2.5" /></Dot>
          <p className="text-xs text-gray-500 dark:text-gray-400">You stopped it here.</p>
        </li>
      )
    default:
      return null
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function uiActionLabel(type: string, payload: any): string {
  switch (type) {
    case 'navigate': return `Opened ${payload?.page ?? 'a page'}`
    case 'open_form': return payload?.form === 'new_workflow' ? 'Drafted a new workflow' : 'Drafted a new task'
    case 'set_theme': return `Switched to the ${payload?.theme} theme`
    case 'ask_user': return `Asked you: ${payload?.question ?? ''}`
    case 'propose_workflow': return `Proposed the workflow "${payload?.name ?? ''}"`
    case 'reused_workflow': return `Ran your saved workflow "${payload?.name ?? ''}"`
    default: return type
  }
}

/** An agent's message, folded to a few lines; long answers open in place. */
function Quote({ text }: { text?: string }) {
  if (!text) return null
  return (
    <details className="group mt-0.5">
      <summary className="cursor-pointer list-none text-xs leading-relaxed text-gray-500 marker:hidden dark:text-gray-400 [&::-webkit-details-marker]:hidden">
        <span className="line-clamp-3 whitespace-pre-wrap group-open:line-clamp-none">{text}</span>
      </summary>
    </details>
  )
}
