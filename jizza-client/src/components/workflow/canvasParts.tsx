import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { BaseEdge, EdgeLabelRenderer, Handle, Position, getBezierPath, type EdgeProps, type NodeProps } from 'reactflow'
import { AlertCircle, Bot, Check, CircleSlash, Flag, GitBranch, Hand, Loader2, Play, Plug, Plus, Search, Shuffle, Trash2, X } from 'lucide-react'
import { AgentLogo } from '@/components/brand/AgentLogo'
import type { AgentDto } from '@/types'
import { NODE_META, NODE_W, PALETTE, type CanvasNode, type NodeType } from './canvasModel'

export type Exit = 'true' | 'false' | null
/** How a step did in the run shown on the canvas. */
export type StepRun = 'done' | 'failed' | 'skipped' | 'running' | 'waiting'

export const handleOf = (b: Exit) => b ?? 'out'
export const exitOf = (handle: string | null | undefined): Exit => (handle === 'true' ? 'true' : handle === 'false' ? 'false' : null)

export const NODE_ICON: Record<NodeType, typeof Bot> = {
  TRIGGER: Play, AGENT_STEP: Bot, HUMAN_INTERVENTION: Hand, CONNECTOR_TOOL: Plug, TRANSFORM: Shuffle, BRANCH: GitBranch, END: Flag,
}

export interface StepData {
  node: CanvasNode
  agent?: AgentDto
  issues: string[]
  orphan: boolean
  run?: StepRun
  /** Exits with nothing connected yet, which get an n8n-style "+" to add the next step. */
  freeExits: Exit[]
  readOnly: boolean
  onAddAfter: (branch: Exit, at: { x: number; y: number }) => void
}

const RUN_RING: Record<StepRun, string> = {
  done: 'ring-2 ring-emerald-500',
  failed: 'ring-2 ring-red-500',
  skipped: 'opacity-60',
  running: 'ring-2 ring-sky-500 animate-pulse',
  waiting: 'ring-2 ring-amber-500',
}

/** One step on the canvas: a card with an input on the left and its exits on the right. */
export const StepNode = memo(function StepNode({ data, selected }: NodeProps<StepData>) {
  const { node, agent, issues, orphan, run, freeExits, readOnly, onAddAfter } = data
  const meta = NODE_META[node.type]
  const Icon = NODE_ICON[node.type]
  const exits: Exit[] = node.type === 'END' ? [] : node.type === 'BRANCH' ? ['true', 'false'] : [null]
  const summary = summarise(node, agent)

  return (
    <div className={`group relative rounded-xl border bg-white shadow-sm transition-shadow dark:bg-gray-900 ${
      selected ? 'border-brand-500 shadow-md ring-4 ring-brand-500/20' : 'border-gray-200 hover:shadow-md dark:border-gray-700'} ${
      run ? RUN_RING[run] : ''}`} style={{ width: NODE_W }}>
      {node.type !== 'TRIGGER' && (
        <Handle type="target" position={Position.Left} id="in"
          className="!h-3.5 !w-3.5 !rounded-full !border-2 !border-white !bg-gray-400 transition-transform hover:!scale-125 dark:!border-gray-900" />
      )}

      <div className={`flex items-center gap-1.5 rounded-t-[11px] px-2.5 py-1 text-[11px] font-semibold ${meta.tone}`}>
        {node.type === 'AGENT_STEP' && agent ? <AgentLogo agentKey={agent.key} avatar={agent.avatar} size={14} /> : <Icon className="h-3.5 w-3.5" />}
        <span className="truncate">{node.type === 'AGENT_STEP' && agent ? agent.name : meta.label}</span>
        <span className="ml-auto flex items-center gap-1">
          {run && <RunBadge run={run} />}
        </span>
      </div>
      <div className={`px-2.5 py-2 ${node.type === 'BRANCH' ? 'pb-5 pr-10' : ''}`}>
        <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{node.label}</p>
        {summary && <p className="mt-0.5 line-clamp-2 text-[11px] leading-snug text-gray-500 dark:text-gray-400">{summary}</p>}
      </div>

      {(issues.length > 0 || orphan) && (
        <span title={[...issues, ...(orphan ? ['Nothing leads here from Start, so this step will never run.'] : [])].join('\n')}
          className={`absolute -right-2 -top-2 grid h-5 w-5 place-items-center rounded-full text-white shadow ${issues.length ? 'bg-red-500' : 'bg-amber-500'}`}>
          <AlertCircle className="h-3.5 w-3.5" />
        </span>
      )}

      {exits.map((b, i) => {
        const top = exits.length === 1 ? '50%' : i === 0 ? '48%' : '76%'
        const free = freeExits.includes(b)
        return (
          <div key={handleOf(b)}>
            {b && (
              <span className={`pointer-events-none absolute right-2.5 -translate-y-1/2 text-[10px] font-semibold uppercase ${b === 'true' ? 'text-emerald-600' : 'text-red-500'}`}
                style={{ top }}>{b === 'true' ? 'yes' : 'no'}</span>
            )}
            <Handle type="source" position={Position.Right} id={handleOf(b)} style={{ top }}
              className={`!h-3.5 !w-3.5 !rounded-full !border-2 !border-white transition-transform hover:!scale-125 dark:!border-gray-900 ${
                b === 'true' ? '!bg-emerald-500' : b === 'false' ? '!bg-red-500' : '!bg-gray-500'}`} />
            {free && !readOnly && (
              <div className="nodrag nopan absolute flex items-center" style={{ top, left: '100%', transform: 'translateY(-50%)' }}>
                <span className="ml-2 h-px w-5 bg-gray-300 dark:bg-gray-600" />
                <button type="button" aria-label={`Add a step after ${node.label}${b ? (b === 'true' ? ' (yes)' : ' (no)') : ''}`}
                  title="Add the next step"
                  onClick={e => { e.stopPropagation(); onAddAfter(b, { x: e.clientX, y: e.clientY }) }}
                  className="grid h-6 w-6 place-items-center rounded-md border border-gray-300 bg-white text-gray-500 shadow-sm transition-colors hover:border-brand-500 hover:bg-brand-50 hover:text-brand-600 dark:border-gray-600 dark:bg-gray-900 dark:hover:bg-brand-500/15">
                  <Plus className="h-3.5 w-3.5" />
                </button>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
})

function RunBadge({ run }: { run: StepRun }) {
  const cls = 'grid h-4 w-4 place-items-center rounded-full bg-white/90 text-[10px]'
  if (run === 'done') return <span className={`${cls} text-emerald-600`} title="Done in the last run"><Check className="h-3 w-3" /></span>
  if (run === 'failed') return <span className={`${cls} text-red-600`} title="Failed in the last run"><X className="h-3 w-3" /></span>
  if (run === 'skipped') return <span className={`${cls} text-gray-500`} title="Skipped in the last run"><CircleSlash className="h-3 w-3" /></span>
  if (run === 'waiting') return <span className={`${cls} text-amber-600`} title="Waiting for you"><Hand className="h-3 w-3" /></span>
  return <span className={`${cls} text-sky-600`} title="Running now"><Loader2 className="h-3 w-3 animate-spin" /></span>
}

export function summarise(node: CanvasNode, agent?: AgentDto): string {
  const c = node.config
  switch (node.type) {
    case 'TRIGGER': return 'When the workflow is run'
    case 'AGENT_STEP': return String(c.instruction ?? (agent ? '' : 'Choose an agent'))
    case 'HUMAN_INTERVENTION': return String(c.prompt ?? 'Pauses and asks you')
    case 'CONNECTOR_TOOL': return c.toolName ? String(c.toolName) : c.toolId ? 'Service action set' : 'Choose a service action'
    case 'TRANSFORM': return String(c.expression ?? 'Set an expression')
    case 'BRANCH': return String(c.condition ?? 'Set a condition')
    default: return 'The run finishes here'
  }
}

export interface WireData {
  branch: Exit
  /** Whether the run shown on the canvas went along this wire. */
  travelled: boolean
  hovered: boolean
  readOnly: boolean
  onHover: (id: string | null) => void
  onInsert: (id: string, at: { x: number; y: number }) => void
  onDelete: (id: string) => void
}

/** A wire between two steps, with buttons to put a step on it or remove it, like n8n. */
export const Wire = memo(function Wire(props: EdgeProps<WireData>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data, selected, markerEnd } = props
  const [path, lx, ly] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  if (!data) return null
  const active = selected || data.hovered
  const stroke = selected ? 'rgb(var(--brand-500))' : data.travelled ? '#10b981' : data.branch === 'false' ? '#f87171' : data.branch === 'true' ? '#34d399' : '#9ca3af'
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} interactionWidth={22}
        style={{ stroke, strokeWidth: active ? 2.5 : 2, strokeDasharray: data.branch === 'false' ? '6 4' : undefined }} />
      <EdgeLabelRenderer>
        <div className="nodrag nopan absolute flex items-center gap-1"
          style={{ transform: `translate(-50%, -50%) translate(${lx}px, ${ly}px)`, pointerEvents: 'all' }}
          onMouseEnter={() => data.onHover(id)} onMouseLeave={() => data.onHover(null)}>
          {active && !data.readOnly && <>
            <WireButton label="Add a step here" onClick={e => data.onInsert(id, { x: e.clientX, y: e.clientY })}><Plus className="h-3.5 w-3.5" /></WireButton>
            <WireButton label="Delete this connection" danger onClick={() => data.onDelete(id)}><Trash2 className="h-3.5 w-3.5" /></WireButton>
          </>}
        </div>
      </EdgeLabelRenderer>
    </>
  )
})

function WireButton({ label, danger, onClick, children }: { label: string; danger?: boolean; onClick: (e: React.MouseEvent) => void; children: ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} onClick={e => { e.stopPropagation(); onClick(e) }}
      className={`grid h-6 w-6 place-items-center rounded-md border bg-white shadow-sm transition-colors dark:bg-gray-900 ${danger
        ? 'border-gray-300 text-gray-500 hover:border-red-400 hover:bg-red-50 hover:text-red-600 dark:border-gray-600 dark:hover:bg-red-500/15'
        : 'border-gray-300 text-gray-500 hover:border-brand-500 hover:bg-brand-50 hover:text-brand-600 dark:border-gray-600 dark:hover:bg-brand-500/15'}`}>
      {children}
    </button>
  )
}

/**
 * The "what comes next?" list n8n shows when you press + or drop a wire on empty canvas. Type to
 * filter, arrows to move, Enter to add.
 */
export function StepPicker({ at, title, allowEnd = true, agents, onPick, onClose }: {
  at: { x: number; y: number }; title: string; allowEnd?: boolean
  /** Agents offered as ready-made agent steps, under the step types. */
  agents?: AgentDto[]
  onPick: (type: NodeType, agent?: AgentDto) => void; onClose: () => void
}) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const options = useMemo(() => {
    const q = query.trim().toLowerCase()
    const steps: { type: NodeType; agent?: AgentDto }[] = PALETTE
      .filter(t => (allowEnd || t !== 'END') && (!q || `${NODE_META[t].label} ${NODE_META[t].desc}`.toLowerCase().includes(q)))
      .map(type => ({ type }))
    // Only agents that can take work now; drafts, paused and archived ones would just fail the run.
    const ready = (agents ?? []).filter(a => !a.orchestrator && a.enabled && (a.status ?? 'active') === 'active'
      && (!q || `${a.name} ${a.description ?? ''} ${a.domain ?? ''}`.toLowerCase().includes(q)))
    return [...steps, ...ready.map(agent => ({ type: 'AGENT_STEP' as NodeType, agent }))]
  }, [query, allowEnd, agents])

  useEffect(() => {
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) onClose() }
    window.addEventListener('pointerdown', away, true)
    return () => window.removeEventListener('pointerdown', away, true)
  }, [onClose])

  // Keep the list on screen when it opens near an edge.
  const left = Math.min(at.x, window.innerWidth - 272)
  const top = Math.min(at.y, window.innerHeight - 340)

  return (
    <div ref={box} role="dialog" aria-label={title} style={{ left, top }}
      className="fixed z-[70] w-64 rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
      <p className="px-2 pb-1 pt-0.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">{title}</p>
      <label className="mb-1 flex items-center gap-1.5 rounded-lg border border-gray-200 px-2 py-1 dark:border-gray-700">
        <Search className="h-3.5 w-3.5 text-gray-400" />
        <input autoFocus value={query} placeholder="Search steps"
          onChange={e => { setQuery(e.target.value); setActive(0) }}
          onKeyDown={e => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(options.length - 1, a + 1)) }
            if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(0, a - 1)) }
            if (e.key === 'Enter') { e.preventDefault(); if (options[active]) onPick(options[active].type, options[active].agent) }
            if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose() }
          }}
          className="w-full bg-transparent text-sm text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
      </label>
      <ul role="listbox" className="max-h-64 overflow-y-auto">
        {options.map(({ type: t, agent }, i) => {
          const Icon = NODE_ICON[t]
          const first = agent && !options[i - 1]?.agent
          return (
            <li key={agent ? `agent:${agent.id}` : t} role="option" aria-selected={i === active}>
              {first && <p className="px-2 pb-0.5 pt-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">Agents</p>}
              <button type="button" onMouseEnter={() => setActive(i)} onClick={() => onPick(t, agent)}
                className={`flex w-full items-start gap-2 rounded-lg px-2 py-1.5 text-left ${i === active ? 'bg-brand-50 dark:bg-brand-500/15' : ''}`}>
                {agent
                  ? <span className="mt-0.5 shrink-0"><AgentLogo agentKey={agent.key} avatar={agent.avatar} size={24} /></span>
                  : <span className={`mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-md ${NODE_META[t].tone}`}><Icon className="h-3.5 w-3.5" /></span>}
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-gray-900 dark:text-gray-100">{agent ? agent.name : NODE_META[t].label}</span>
                  <span className="block truncate text-[11px] text-gray-500 dark:text-gray-400">{agent ? agent.description ?? agent.domain ?? 'Agent step' : NODE_META[t].desc}</span>
                </span>
              </button>
            </li>
          )
        })}
        {options.length === 0 && <li className="px-2 py-3 text-center text-xs text-gray-500">No step matches “{query}”.</li>}
      </ul>
    </div>
  )
}

export interface MenuItem { label: string; shortcut?: string; danger?: boolean; disabled?: boolean; onSelect: () => void }

/** A right-click menu. */
export function ContextMenu({ at, items, onClose }: { at: { x: number; y: number }; items: MenuItem[]; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) onClose() }
    window.addEventListener('pointerdown', away, true)
    return () => window.removeEventListener('pointerdown', away, true)
  }, [onClose])
  return (
    <div ref={box} role="menu" style={{ left: Math.min(at.x, window.innerWidth - 220), top: Math.min(at.y, window.innerHeight - items.length * 34 - 16) }}
      className="fixed z-[70] w-52 rounded-xl border border-gray-200 bg-white p-1 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
      {items.map(item => (
        <button key={item.label} type="button" role="menuitem" disabled={item.disabled}
          onClick={() => { onClose(); item.onSelect() }}
          className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm disabled:opacity-40 ${item.danger
            ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-500/10'
            : 'text-gray-800 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
          <span className="flex-1">{item.label}</span>
          {item.shortcut && <kbd className="text-[11px] text-gray-400">{item.shortcut}</kbd>}
        </button>
      ))}
    </div>
  )
}
