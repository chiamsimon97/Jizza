import type { ReactNode } from 'react'
import { AgentLogo, AVATAR_COLOURS, type AgentState } from '@/components/brand/AgentLogo'
import { Badge } from '@/components/ui/Badge'
import { ACTION_LABELS, ACTION_TYPES, MODE_LABELS } from '@/components/guardrails/labels'
import type { ActionType, AgentBlueprint, AgentCreate, AgentDto, AgentGrant, GuardrailMode } from '@/types'

/** The profile an agent you make starts from: reads and drafts alone, asks for the rest. */
export const CREATED_PROFILE: Record<ActionType, GuardrailMode> = {
  READ: 'ALLOW', DRAFT: 'ALLOW', CREATE: 'ASK', UPDATE: 'ASK', DELETE: 'ASK', SEND: 'ASK', MONEY: 'DENY',
}

const STRICTNESS: Record<GuardrailMode, number> = { ALLOW: 0, JIZZA: 1, ASK: 2, DENY: 3 }
export const looser = (a: GuardrailMode, b: GuardrailMode) => STRICTNESS[a] < STRICTNESS[b]

export const SHARED_DOMAINS = [
  { id: 'finance', label: 'Finance', warning: 'This agent will be able to see financial information about you.' },
  { id: 'health', label: 'Health', warning: 'This agent will be able to see health information about you.' },
  { id: 'professional', label: 'Work', warning: 'This agent will be able to see information about your work.' },
]

export const COLOURS = Object.keys(AVATAR_COLOURS)

/** What the agent form edits; the same shape whether it started blank, from a template or from Jizza. */
export interface AgentDraft {
  name: string
  description: string
  instructions: string
  modelTier: 'standard' | 'deep'
  colour: string
  memoryDomains: string[]
  parentAgentId: string
  responsibilities: string[]
  handsOffTo: string
  monthlyCostLimit: string
  tools: AgentGrant[]
  toolHints: string[]
  modes: Record<ActionType, GuardrailMode>
  locks: ActionType[]
}

export function emptyDraft(parentAgentId = ''): AgentDraft {
  return {
    name: '', description: '', instructions: '', modelTier: 'standard', colour: 'purple', memoryDomains: [],
    parentAgentId, responsibilities: [], handsOffTo: '', monthlyCostLimit: '', tools: [], toolHints: [],
    modes: { ...CREATED_PROFILE }, locks: [],
  }
}

export function draftFrom(b: AgentBlueprint, parentAgentId = ''): AgentDraft {
  return {
    ...emptyDraft(parentAgentId),
    name: b.name ?? '',
    description: b.description ?? '',
    instructions: b.instructions ?? '',
    modelTier: b.modelTier === 'deep' ? 'deep' : 'standard',
    colour: b.avatar?.colour && AVATAR_COLOURS[b.avatar.colour] ? b.avatar.colour : 'purple',
    memoryDomains: b.memoryDomains ?? [],
    responsibilities: b.responsibilities ?? [],
    handsOffTo: b.handsOffTo ?? '',
    tools: (b.tools ?? []).map(t => ({ type: t.type, ref: t.ref })),
    toolHints: b.toolHints ?? [],
    modes: { ...CREATED_PROFILE, ...(b.modes ?? {}) },
    locks: b.locks ?? [],
  }
}

/** What the server is sent; modes equal to the profile are left for the server to fill in. */
export function toCreate(d: AgentDraft, status: 'active' | 'draft' = 'active'): AgentCreate {
  const modes: Partial<Record<ActionType, GuardrailMode>> = {}
  for (const t of ACTION_TYPES) if (d.modes[t] !== CREATED_PROFILE[t]) modes[t] = d.modes[t]
  const limit = d.monthlyCostLimit.trim() === '' ? null : Number(d.monthlyCostLimit)
  return {
    name: d.name.trim(),
    description: d.description.trim() || undefined,
    instructions: d.instructions.trim(),
    modelTier: d.modelTier,
    memoryDomains: d.memoryDomains,
    avatar: { style: 'robot', colour: d.colour },
    monthlyCostLimit: limit != null && !Number.isNaN(limit) ? limit : null,
    parentAgentId: d.parentAgentId || null,
    responsibilities: d.responsibilities.filter(r => r.trim()),
    handsOffTo: d.handsOffTo.trim() || undefined,
    status,
    tools: d.tools,
    modes,
    locks: d.locks,
  }
}

/** Whether saving this draft will ask for the password again, and why. */
export function stepUpReasons(d: AgentDraft): string[] {
  const out: string[] = []
  if (d.tools.length > 0) out.push('it is given tools')
  if (d.memoryDomains.length > 0) out.push('it can read shared memory')
  if (ACTION_TYPES.some(t => looser(d.modes[t], CREATED_PROFILE[t]))) out.push('a rule is looser than the safe default')
  return out
}

/** One line per kind of action, in the words the review screen uses. */
export function describeModes(modes: Record<ActionType, GuardrailMode>): { alone: string[]; asks: string[]; never: string[] } {
  const alone: string[] = []
  const asks: string[] = []
  const never: string[] = []
  for (const t of ACTION_TYPES) {
    const label = ACTION_LABELS[t].label.toLowerCase()
    if (modes[t] === 'ALLOW') alone.push(label)
    else if (modes[t] === 'DENY') never.push(label)
    else asks.push(label)
  }
  return { alone, asks, never }
}

export const modeLabel = (m: GuardrailMode) => MODE_LABELS[m]

export function agentState(agent: AgentDto, busy = false): AgentState {
  if (!agent.enabled || agent.status !== 'active') return 'off'
  return busy ? 'working' : 'idle'
}

export function AgentAvatar({ agent, size = 40, state }: { agent: Pick<AgentDto, 'key' | 'avatar' | 'enabled' | 'status'>; size?: number; state?: AgentState }) {
  return <AgentLogo agentKey={agent.key} avatar={agent.avatar} size={size}
    state={state ?? (agent.enabled && (agent.status ?? 'active') === 'active' ? 'idle' : 'off')} />
}

/** Draft, Paused, Archived or Ready, and Custom for agents the user made. */
export function AgentBadges({ agent }: { agent: AgentDto }) {
  const status = agent.status ?? 'active'
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {agent.custom && <Badge variant="info">Custom</Badge>}
      {status === 'draft' && <Badge>Draft</Badge>}
      {status === 'archived' && <Badge>Archived</Badge>}
      {status === 'active' && !agent.enabled && <Badge>Paused</Badge>}
    </span>
  )
}

/** Agents grouped by the team they're in, for pickers: Jizza's team first, then each manager's. */
export function groupByTeam(agents: AgentDto[]): { label: string; agents: AgentDto[] }[] {
  const byId = new Map(agents.map(a => [a.id, a]))
  const jizza = agents.find(a => a.orchestrator)
  const groups = new Map<string, AgentDto[]>()
  const pathOf = (a: AgentDto): string => {
    const names: string[] = []
    let p = a.parentAgentId ? byId.get(a.parentAgentId) : undefined
    while (p && !p.orchestrator && names.length < 6) { names.unshift(p.name); p = p.parentAgentId ? byId.get(p.parentAgentId) : undefined }
    return names.length === 0 ? `${jizza?.name ?? 'Jizza'}'s team` : `${names.join(' › ')}'s team`
  }
  for (const a of agents) {
    if (a.orchestrator || a.status === 'archived' || a.status === 'draft') continue
    const label = pathOf(a)
    groups.set(label, [...(groups.get(label) ?? []), a])
  }
  return [...groups.entries()].map(([label, list]) => ({ label, agents: list }))
}

export function Section({ title, hint, children, actions }: { title: string; hint?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <div className="mb-3 flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h2>
          {hint && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
        </div>
        {actions}
      </div>
      {children}
    </section>
  )
}

export function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}

/** Who handed work to whom during a run, indented by how far down the team it went. */
export function TeamTrailList({ steps }: { steps: { fromName: string; toName: string; depth: number; answer?: string | null; failed: boolean; tokens?: number }[] }) {
  return (
    <ul className="mt-1 flex flex-col gap-1">
      {steps.map((s, i) => (
        <li key={i} style={{ paddingLeft: `${(s.depth - 1) * 16}px` }} className="text-xs text-gray-700 dark:text-gray-300">
          <span className="font-medium">{s.fromName} → {s.toName}</span>
          {s.failed && <span className="text-red-600"> (failed)</span>}
          {s.answer && <span className="block truncate text-gray-500">{s.answer}</span>}
        </li>
      ))}
    </ul>
  )
}
