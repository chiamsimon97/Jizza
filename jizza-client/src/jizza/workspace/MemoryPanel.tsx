import { Brain, Database, Globe, MessageSquareQuote, Sparkles } from 'lucide-react'
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
  const outputs = job.events.filter(e =>
    e.type === 'output' || e.type === 'tool.api' || (e.type === 'agent.message' && e.payload.kind === 'answer'))
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
            <Empty>Nothing new to remember from this one.</Empty>
          ) : (
            <ul className="flex flex-col gap-1.5">
              {saved.map(e => (
                <li key={e.seq} className="rounded-lg border border-emerald-200 bg-emerald-50/60 px-2.5 py-2 dark:border-emerald-500/30 dark:bg-emerald-500/10">
                  <p className="text-xs leading-relaxed text-gray-800 dark:text-gray-200">{e.payload.text}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-1">
                    {e.payload.category && <Chip>{String(e.payload.category).toLowerCase()}</Chip>}
                    {e.payload.status === 'pending' && <Chip tone="amber">waiting for your OK</Chip>}
                    {e.payload.source && <span className="text-[10px] text-gray-400">· from {e.payload.source}</span>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>
    </div>
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
  return (
    <li className="flex items-start gap-2 text-xs text-gray-700 dark:text-gray-300">
      <Database className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />
      <span className="min-w-0">{p.label && <span className="font-medium">{p.label}: </span>}{p.summary ?? p.text}</span>
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
