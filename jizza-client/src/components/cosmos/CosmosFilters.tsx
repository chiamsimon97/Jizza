import type { MemoryCategory, MemoryNode, MemorySearchFilters, NodeCategory, NodeClassification, NodeStatus } from '@/types'
import { fieldClass } from '@/components/ui/SlideOver'

export interface CosmosAgentOption { id: string; key: string; name: string; domain?: string; bodyId: string }

export const CATEGORIES: MemoryCategory[] =
  ['PROFILE', 'PREFERENCE', 'RELATIONSHIP', 'FINANCE', 'HEALTH', 'PROFESSIONAL', 'PROJECT', 'TASK', 'GENERAL']
const KINDS: NodeCategory[] = [...CATEGORIES.map(c => c.toLowerCase() as NodeCategory), 'note']
const CLASSIFICATIONS: NodeClassification[] = ['none', 'pii', 'health', 'financial']
const STATUSES: NodeStatus[] = ['active', 'pending', 'retired', 'superseded']

export const label = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase()

export function filterCount(f: MemorySearchFilters) {
  return (f.agentIds?.length ? 1 : 0) + (f.categories?.length ? 1 : 0) + (f.classification ? 1 : 0)
    + (f.status ? 1 : 0) + (f.from || f.to ? 1 : 0)
}

/** The same filters, applied to what's already on screen so the sky dims what doesn't match. */
export function matchesFilters(f: MemorySearchFilters) {
  const from = f.from ? Date.parse(f.from) : null
  const to = f.to ? Date.parse(f.to) : null
  return (n: MemoryNode) => {
    if (f.categories?.length && (!n.category || !f.categories.includes(n.category))) return false
    if (f.classification && n.classification !== f.classification) return false
    if (f.status && n.status !== f.status) return false
    const at = Date.parse(n.createdAt)
    if (from != null && at < from) return false
    if (to != null && at > to) return false
    return true
  }
}

interface Props {
  value: MemorySearchFilters
  onChange: (f: MemorySearchFilters) => void
  agents: CosmosAgentOption[]
}

function Chip({ on, onClick, children, color }: { on: boolean; onClick: () => void; children: React.ReactNode; color?: string }) {
  return (
    <button type="button" aria-pressed={on} onClick={onClick}
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
        on ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/40 dark:text-brand-200'
          : 'border-gray-200 text-gray-600 hover:border-gray-300 dark:border-gray-700 dark:text-gray-300'}`}>
      {color && <span className="h-2 w-2 rounded-full" style={{ background: color }} />}
      {children}
    </button>
  )
}

const toggle = <T,>(list: T[] | undefined, v: T) =>
  list?.includes(v) ? list.filter(x => x !== v) : [...(list ?? []), v]

/** Narrow the sky and the search to some agents, kinds of memory, sensitivity, state or dates. */
export function CosmosFilters({ value, onChange, agents }: Props) {
  const set = (patch: Partial<MemorySearchFilters>) => onChange({ ...value, ...patch })
  // The date pickers work in the user's own time zone.
  const day = (iso?: string) => {
    if (!iso) return ''
    const d = new Date(iso)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <fieldset className="flex flex-wrap items-center gap-2">
        <legend className="mb-1 w-full text-xs font-semibold uppercase tracking-wider text-gray-500">Agents</legend>
        {agents.map(a => (
          <Chip key={a.id} on={!!value.agentIds?.includes(a.id)} onClick={() => set({ agentIds: toggle(value.agentIds, a.id) })}
            color={`rgb(var(--agent-${a.key}, var(--agent-other)))`}>{a.name}</Chip>
        ))}
      </fieldset>
      <fieldset className="flex flex-wrap items-center gap-2">
        <legend className="mb-1 w-full text-xs font-semibold uppercase tracking-wider text-gray-500">Kind</legend>
        {KINDS.map(c => (
          <Chip key={c} on={!!value.categories?.includes(c)} onClick={() => set({ categories: toggle(value.categories, c) })}>{label(c)}</Chip>
        ))}
      </fieldset>
      <div className="grid gap-3 sm:grid-cols-4">
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          Sensitivity
          <select className={fieldClass} value={value.classification ?? ''}
            onChange={e => set({ classification: (e.target.value || undefined) as NodeClassification | undefined })}>
            <option value="">Any</option>
            {CLASSIFICATIONS.map(c => <option key={c} value={c}>{c === 'none' ? 'Not sensitive' : c === 'pii' ? 'Personal' : label(c)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          State
          <select className={fieldClass} value={value.status ?? ''}
            onChange={e => set({ status: (e.target.value || undefined) as NodeStatus | undefined })}>
            <option value="">Any</option>
            {STATUSES.map(s => <option key={s} value={s}>{s === 'pending' ? 'Waiting for you' : label(s)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          From
          <input type="date" className={fieldClass} value={day(value.from)}
            onChange={e => set({ from: e.target.value ? new Date(`${e.target.value}T00:00:00`).toISOString() : undefined })} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          To
          <input type="date" className={fieldClass} value={day(value.to)}
            onChange={e => set({ to: e.target.value ? new Date(`${e.target.value}T23:59:59`).toISOString() : undefined })} />
        </label>
      </div>
      {filterCount(value) > 0 && (
        <button type="button" onClick={() => onChange({})} className="self-start text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
          Clear filters
        </button>
      )}
    </div>
  )
}
