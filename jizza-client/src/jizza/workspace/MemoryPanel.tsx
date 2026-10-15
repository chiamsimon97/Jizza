import { useState } from 'react'
import { Brain, Check, Database, Globe, MessageSquareQuote, Sparkles, Trash2 } from 'lucide-react'
import { cosmosApi } from '@/api/cosmos'
import type { JobEvent } from '@/types'
import { useAssistantStore } from '../assistantStore'
import { PanelTitle } from './ActivityPanel'

interface ContextItem {
  id: string
  text: string
  category?: string
  reason?: string
  confirmed?: boolean
  agents: string[]
}

/** Why a memory was pulled in, in words. Mirrors the reasons the recall step reports. */
const REASON: Record<string, string> = {
  core: 'always on',
  subject: 'about this',
  related: 'related',
  meaning: 'similar meaning',
  keywords: 'matched words',
  instruction: 'your standing instruction',
}

function contextOf(events: JobEvent[]): ContextItem[] {
  const byId = new Map<string, ContextItem>()
  for (const e of events) {
    if (e.type !== 'memory.used') continue
    for (const item of (e.payload.items ?? []) as Omit<ContextItem, 'agents'>[]) {
      const known = byId.get(item.id)
      const agent = e.agentName ?? 'Agent'
      if (known) { if (!known.agents.includes(agent)) known.agents.push(agent) }
      else byId.set(item.id, { ...item, agents: [agent] })
    }
  }
  return [...byId.values()]
}

/**
 * The bottom half of the work panel, for the job selected above: what Jizza and the specialists
 * remembered going in, what came back from agents and services, and what was saved for next time.
 */
export function MemoryPanel() {
  const { jobs, selectedJobId, incognito } = useAssistantStore()
  const job = selectedJobId ? jobs[selectedJobId] : undefined

  if (!job) {
    return (
      <div className="flex h-full min-h-0 flex-col">
        <PanelTitle title="Memory" />
        <p className="px-4 text-xs leading-relaxed text-gray-500 dark:text-gray-400">
          Pick a job above to see what Jizza remembered for it, what it found out, and what it kept for next time.
        </p>
      </div>
    )
  }

  const context = contextOf(job.events)
  // A call whose result was summarised shows as that summary; a bare call line is only kept when
  // there is nothing better to show (an error, or a response with no readable values).
  const summarised = new Set(job.events.filter(e => e.type === 'output').map(e => e.payload.tool))
  const outputs = job.events.filter(e =>
    e.type === 'output' || (e.type === 'tool.api' && !summarised.has(e.payload.tool))
    || (e.type === 'agent.message' && e.payload.kind === 'answer'))
  const saved = job.events.filter(e => e.type === 'memory.saved')

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PanelTitle title="Memory" hint={job.title} />
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
        <Section icon={Brain} title="Context in" count={context.length}>
          {context.length === 0 ? (
            <Empty>{job.loaded ? 'No memories were needed for this.' : 'Loading…'}</Empty>
          ) : (
            <ul className="flex flex-col gap-1.5">
              {context.map(m => (
                <li key={m.id} className="rounded-lg border border-gray-200 bg-white px-2.5 py-2 dark:border-gray-800 dark:bg-gray-900">
                  <p className="text-xs leading-relaxed text-gray-800 dark:text-gray-200">{m.text}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-1">
                    {m.category && <Chip>{m.category.toLowerCase()}</Chip>}
                    {m.reason && <Chip tone="brand">{REASON[m.reason] ?? m.reason}</Chip>}
                    {m.confirmed === false && <Chip tone="amber">not confirmed</Chip>}
                    <span className="text-[10px] text-gray-400">· {m.agents.join(', ')}</span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section icon={Database} title="Output data" count={outputs.length}>
          {outputs.length === 0 ? (
            <Empty>Nothing was fetched; Jizza worked from the conversation and memory.</Empty>
          ) : (
            <ul className="flex flex-col gap-1.5">
              {outputs.map(e => <Output key={e.seq} e={e} />)}
            </ul>
          )}
        </Section>

        <Section icon={Sparkles} title="Saved to memory" count={saved.length}>
          {incognito ? (
            <Empty>Incognito: Jizza can read your memory but saves nothing from this chat.</Empty>
          ) : saved.length === 0 ? (
            <Empty>{job.status === 'running' ? 'Anything worth keeping shows up here.' : 'Nothing new to remember from this one.'}</Empty>
          ) : (
            <ul className="flex flex-col gap-1.5">
              {saved.map(e => <SavedFact key={e.seq} e={e} />)}
            </ul>
          )}
        </Section>
      </div>
    </div>
  )
}

const ORIGIN: Record<string, string> = {
  conversation: 'from what you said',
  agent: 'saved by an agent',
  user: 'you told Jizza',
}

function origin(e: JobEvent): string | undefined {
  const ref: string | undefined = e.payload.sourceRef
  if (e.payload.source === 'tool') return ref?.startsWith('tool:') ? `found in ${ref.slice(5)} data` : 'found in service data'
  return ORIGIN[e.payload.source]
}

/** A fact the job kept. Anything not confirmed can be confirmed or forgotten right here. */
function SavedFact({ e }: { e: JobEvent }) {
  const [state, setState] = useState<'idle' | 'busy' | 'confirmed' | 'forgotten' | 'error'>('idle')
  const p = e.payload
  const confirmed = p.confirmed === true || state === 'confirmed'
  const act = async (fn: () => Promise<unknown>, done: 'confirmed' | 'forgotten') => {
    setState('busy')
    try { await fn(); setState(done) } catch { setState('error') }
  }
  if (state === 'forgotten') {
    return <li className="text-xs text-gray-400 line-through">{p.text}</li>
  }
  return (
    <li className={`rounded-lg border px-2.5 py-2 ${confirmed
      ? 'border-emerald-200 bg-emerald-50/60 dark:border-emerald-500/30 dark:bg-emerald-500/10'
      : 'border-amber-200 bg-amber-50/50 dark:border-amber-500/30 dark:bg-amber-500/10'}`}>
      <p className="text-xs leading-relaxed text-gray-800 dark:text-gray-200">{p.text}</p>
      <div className="mt-1 flex flex-wrap items-center gap-1">
        {p.category && <Chip>{String(p.category).toLowerCase()}</Chip>}
        {!confirmed && <Chip tone="amber">{p.status === 'pending' ? 'conflicts with what you said' : 'not confirmed'}</Chip>}
        {origin(e) && <span className="text-[10px] text-gray-400">· {origin(e)}</span>}
      </div>
      {!confirmed && p.id && (
        <div className="mt-1.5 flex gap-1.5">
          <button type="button" disabled={state === 'busy'} onClick={() => void act(() => cosmosApi.confirmFact(p.id), 'confirmed')}
            className="inline-flex items-center gap-1 rounded-md bg-white px-2 py-0.5 text-[11px] font-medium text-emerald-700 ring-1 ring-inset ring-emerald-300 hover:bg-emerald-50 disabled:opacity-50 dark:bg-gray-900 dark:text-emerald-300 dark:ring-emerald-500/40">
            <Check className="h-3 w-3" /> Confirm
          </button>
          <button type="button" disabled={state === 'busy'} onClick={() => void act(() => cosmosApi.forgetFact(p.id), 'forgotten')}
            className="inline-flex items-center gap-1 rounded-md bg-white px-2 py-0.5 text-[11px] font-medium text-gray-600 ring-1 ring-inset ring-gray-300 hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-900 dark:text-gray-300 dark:ring-gray-700">
            <Trash2 className="h-3 w-3" /> Forget
          </button>
          {state === 'error' && <span className="text-[11px] text-red-500">That didn't work; try again.</span>}
        </div>
      )}
    </li>
  )
}

function Output({ e }: { e: JobEvent }) {
  const p = e.payload
  if (e.type === 'tool.api') {
    return (
      <li className="flex items-start gap-2 text-xs text-gray-700 dark:text-gray-300">
        <Globe className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />
        <span><span className="font-medium">{p.service}</span> returned {p.status} for <code className="font-mono text-[11px]">{p.method} {p.path}</code></span>
      </li>
    )
  }
  if (e.type === 'agent.message') {
    return (
      <li className="flex items-start gap-2 text-xs text-gray-700 dark:text-gray-300">
        <MessageSquareQuote className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />
        <span className="min-w-0"><span className="font-medium">{e.agentName}</span>: <span className="line-clamp-3 inline whitespace-pre-wrap text-gray-500 dark:text-gray-400">{p.text}</span></span>
      </li>
    )
  }
  const fields = (p.fields ?? []) as { key: string; value: string }[]
  return (
    <li className="rounded-lg border border-gray-200 bg-white px-2.5 py-2 text-xs dark:border-gray-800 dark:bg-gray-900">
      <p className="flex items-center gap-1.5 font-medium text-gray-700 dark:text-gray-200">
        <Database className="h-3.5 w-3.5 shrink-0 text-gray-400" />
        {p.service} · {p.label}
        {typeof p.items === 'number' && <span className="font-normal text-gray-400">· {p.items} items</span>}
      </p>
      {fields.length > 0 && (
        <dl className="mt-1 grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-3 gap-y-0.5">
          {fields.map(f => (
            <div key={f.key} className="contents">
              <dt className="truncate text-gray-500 dark:text-gray-400">{f.key}</dt>
              <dd className="truncate font-mono text-[11px] text-gray-800 dark:text-gray-200">{f.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  )
}

function Section({ icon: Icon, title, count, children }: { icon: typeof Brain; title: string; count: number; children: React.ReactNode }) {
  return (
    <section className="mt-3 first:mt-0">
      <h4 className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-gray-700 dark:text-gray-200">
        <Icon className="h-3.5 w-3.5 text-gray-400" />
        {title}
        {count > 0 && <span className="font-normal text-gray-400">{count}</span>}
      </h4>
      {children}
    </section>
  )
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-xs text-gray-500 dark:text-gray-400">{children}</p>
}

function Chip({ children, tone = 'gray' }: { children: React.ReactNode; tone?: 'gray' | 'brand' | 'amber' }) {
  const tones = {
    gray: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300',
    brand: 'bg-brand-50 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300',
    amber: 'bg-amber-50 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300',
  }
  return <span className={`rounded px-1.5 py-px text-[10px] font-medium ${tones[tone]}`}>{children}</span>
}
