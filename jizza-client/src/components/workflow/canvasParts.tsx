import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { BaseEdge, EdgeLabelRenderer, Handle, Position, getBezierPath, type EdgeProps, type NodeProps } from 'reactflow'
import {
  AlertCircle, Bell, Bot, Braces, Check, CircleSlash, Clock, Code2, Database, Filter, Flag, GitBranch, GitMerge, Hand, Loader2, MoreVertical, Play, Plug, Plus, Repeat, Search,
  Server, ShieldCheck, Shuffle, Signpost, Split, Trash2, Webhook, Workflow, X,
  MonitorSmartphone,
} from 'lucide-react'
import { AgentLogo } from '@/components/brand/AgentLogo'
import type { AgentDto } from '@/types'
import { NODE_META, NODE_W, PALETTE, casesOf, exitsOf, groupOf, type CanvasNode, type ExitKey, type NodeType, type PaletteEntry } from './canvasModel'
import { fieldsOf } from './moduleContract'

export type Exit = ExitKey
/** How a step did in the run shown on the canvas. */
export type StepRun = 'done' | 'failed' | 'skipped' | 'running' | 'waiting'

export const handleOf = (b: Exit) => b ?? 'out'
export const exitOf = (handle: string | null | undefined): Exit => (handle && handle !== 'out' && handle !== 'in' ? handle : null)

/** What an exit is called next to its dot. A paths step's cases go by their labels, so it needs the node. */
export function exitLabel(b: Exit, node?: CanvasNode): string {
  if (b === null) return ''
  if (b === 'true') return 'yes'
  if (b === 'false') return 'no'
  if (b === 'else') return 'otherwise'
  if (b === 'approved' || b === 'rejected') return b
  if (node?.type === 'SWITCH') {
    const cases = casesOf(node.config)
    const i = cases.findIndex(c => c.id === b)
    return i < 0 ? b : cases[i].label.trim() || `path ${i + 1}`
  }
  return 'abc'.includes(b) ? `path ${'abc'.indexOf(b) + 1}` : b
}

/** Whether an exit is the "go" or "stop" side of a choice, for its colour. */
const exitTone = (b: Exit): 'yes' | 'no' | 'other' => (b === 'true' || b === 'approved' ? 'yes' : b === 'false' || b === 'rejected' ? 'no' : 'other')

export const NODE_ICON: Record<NodeType, typeof Bot> = {
  TRIGGER: Play, AGENT_STEP: Bot, HUMAN_INTERVENTION: Hand, CONNECTOR_TOOL: Plug, TRANSFORM: Shuffle, BRANCH: GitBranch, END: Flag,
  PARALLEL: Split, JOIN: GitMerge, WAIT: Clock, SUB_WORKFLOW: Workflow, FOR_EACH: Repeat,
  APPROVAL: ShieldCheck, HTTP_REQUEST: Webhook, FILTER: Filter, SWITCH: Signpost, SET_VARIABLE: Braces, STORAGE: Database, NOTIFY: Bell,
  MCP_TOOL: Server, SCRIPT: Code2, DEVICE_ACTION: MonitorSmartphone,
}

const EXIT_TOP: Record<number, string[]> = { 1: ['50%'], 2: ['48%', '76%'], 3: ['40%', '62%', '84%'] }
/** Past three exits (a paths step), each gets this many pixels below the header and the card grows to fit. */
const EXIT_GAP = 22
const EXIT_FIRST = 40

/** Where each of a node's exits sits, from the top of the card. */
const exitTops = (n: number): string[] => EXIT_TOP[n] ?? Array.from({ length: n }, (_, i) => `${EXIT_FIRST + i * EXIT_GAP}px`)

/** Room in the card body for the exit labels on the right and the dots below the text. */
function bodyPad(node: CanvasNode, exits: number): string {
  if (node.type === 'BRANCH') return 'pb-5 pr-10'
  if (node.type === 'PARALLEL') return 'pb-7 pr-14'
  if (node.type === 'APPROVAL') return 'pb-5 pr-20'
  if (node.type === 'SWITCH') return exits === 2 ? 'pb-5 pr-20' : 'pb-7 pr-20'
  return ''
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
  /** Whether this step's options menu is open, which keeps its button showing. */
  menuOpen: boolean
  onAddAfter: (branch: Exit, at: { x: number; y: number }) => void
  /** Opens the step's options menu next to its button; none on a read-only canvas. */
  onMenu?: (at: MenuAt) => void
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
  const { node, agent, issues, orphan, run, freeExits, readOnly, menuOpen, onAddAfter, onMenu } = data
  const meta = NODE_META[node.type]
  const Icon = NODE_ICON[node.type]
  const exits: Exit[] = exitsOf(node)
  const tops = exitTops(exits.length)
  const summary = summarise(node, agent)

  return (
    <div className={`group relative rounded-xl border bg-white shadow-sm transition-shadow dark:bg-gray-900 ${
      selected ? 'border-brand-500 shadow-md ring-4 ring-brand-500/20' : 'border-gray-200 hover:shadow-md dark:border-gray-700'} ${
      run ? RUN_RING[run] : ''}`} style={{ width: NODE_W, minHeight: exits.length > 3 ? EXIT_FIRST + exits.length * EXIT_GAP : undefined }}>
      {node.type !== 'TRIGGER' && (
        <Handle type="target" position={Position.Left} id="in"
          className="!h-3.5 !w-3.5 !rounded-full !border-2 !border-white !bg-gray-400 transition-transform hover:!scale-125 dark:!border-gray-900" />
      )}

      <div className={`flex items-center gap-1.5 rounded-t-[11px] px-2.5 py-1 text-[11px] font-semibold ${meta.tone}`}>
        {node.type === 'AGENT_STEP' && agent ? <AgentLogo agentKey={agent.key} avatar={agent.avatar} size={14} /> : <Icon className="h-3.5 w-3.5" />}
        <span className="truncate">{node.type === 'AGENT_STEP' && agent ? agent.name : meta.label}</span>
        <span className="ml-auto flex items-center gap-1">
          {run && <RunBadge run={run} />}
          {onMenu && (
            <button type="button" aria-label={`More options for ${node.label}`} title="More options" aria-haspopup="menu" aria-expanded={menuOpen}
              onClick={e => {
                e.stopPropagation()
                const r = e.currentTarget.getBoundingClientRect()
                // Beside the button, or on its left when there is no room on the right.
                onMenu({ x: r.right + 6, y: r.top, flipX: r.left - 6 })
              }}
              className={`nodrag -my-0.5 -mr-1 grid h-5 w-5 place-items-center rounded transition-opacity hover:bg-black/15 focus-visible:opacity-100 ${
                menuOpen ? 'bg-black/15 opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
              <MoreVertical className="h-3.5 w-3.5" />
            </button>
          )}
        </span>
      </div>
      <div className={`px-2.5 py-2 ${bodyPad(node, exits.length)}`}>
        <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{node.label}</p>
        {summary && <p className="mt-0.5 line-clamp-2 text-[11px] leading-snug text-gray-500 dark:text-gray-400">{summary}</p>}
      </div>
      {typeof node.config.note === 'string' && node.config.note.trim() && (
        <p title={node.config.note} className="mx-2 mb-2 line-clamp-3 whitespace-pre-line rounded-md bg-yellow-50 px-2 py-1 text-[11px] leading-snug text-yellow-900 ring-1 ring-inset ring-yellow-200 dark:bg-yellow-500/10 dark:text-yellow-200 dark:ring-yellow-500/30">
          {node.config.note}
        </p>
      )}

      {(issues.length > 0 || orphan) && (
        <span title={[...issues, ...(orphan ? ['Nothing leads here from Start, so this step will never run.'] : [])].join('\n')}
          className={`absolute -right-2 -top-2 grid h-5 w-5 place-items-center rounded-full text-white shadow ${issues.length ? 'bg-red-500' : 'bg-amber-500'}`}>
          <AlertCircle className="h-3.5 w-3.5" />
        </span>
      )}

      {exits.map((b, i) => {
        const top = tops[i]
        const free = freeExits.includes(b)
        const tone = exitTone(b)
        return (
          <div key={handleOf(b)}>
            {b && (
              <span className={`pointer-events-none absolute right-2.5 max-w-[72px] -translate-y-1/2 truncate text-[10px] font-semibold uppercase ${
                tone === 'yes' ? 'text-emerald-600' : tone === 'no' ? 'text-red-500' : b === 'else' ? 'text-gray-500' : 'text-teal-600 dark:text-teal-400'}`}
                style={{ top }}>{exitLabel(b, node)}</span>
            )}
            <Handle type="source" position={Position.Right} id={handleOf(b)} style={{ top }}
              className={`!h-3.5 !w-3.5 !rounded-full !border-2 !border-white transition-transform hover:!scale-125 dark:!border-gray-900 ${
                tone === 'yes' ? '!bg-emerald-500' : tone === 'no' ? '!bg-red-500' : '!bg-gray-500'}`} />
            {free && !readOnly && (
              <div className="nodrag nopan absolute flex items-center" style={{ top, left: '100%', transform: 'translateY(-50%)' }}>
                <span className="ml-2 h-px w-5 bg-gray-300 dark:bg-gray-600" />
                <button type="button" aria-label={`Add a step after ${node.label}${b ? ` (${exitLabel(b, node)})` : ''}`}
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
  return readable(describe(node, agent))
}

/** Mapped values as the field they read, [body.price], rather than the whole {{ nodes.… }} reference. */
function readable(text: string): string {
  return text.replace(/\{\{\s*(nodes\.[A-Za-z0-9_]+|input|vars)\.?([A-Za-z0-9_.[\]]*)\s*}}/g,
    (_, root: string, path: string) => `[${path || (root === 'input' ? 'input' : 'result')}]`)
}

function describe(node: CanvasNode, agent?: AgentDto): string {
  const c = node.config
  switch (node.type) {
    case 'TRIGGER': return 'When the workflow is run'
    case 'AGENT_STEP': return String(c.instruction ?? (agent ? '' : 'Choose an agent'))
    case 'HUMAN_INTERVENTION': return String(c.prompt ?? 'Pauses and asks you')
    case 'CONNECTOR_TOOL': return c.toolName ? String(c.toolName) : c.toolId ? 'Service action set' : 'Choose a service action'
    case 'MCP_TOOL': return c.tool ? `${c.serverName ? `${String(c.serverName)} · ` : ''}${String(c.toolLabel ?? c.tool)}` : c.serverId ? 'Choose a tool' : 'Choose an MCP server and tool'
    case 'DEVICE_ACTION': return c.action ? `${String(c.actionTitle ?? c.action)}${c.deviceName ? ` on ${String(c.deviceName)}` : ''}` : 'Choose what to do on a device'
    case 'TRANSFORM': return String(c.expression ?? 'Set an expression')
    case 'SCRIPT': {
      if (c.functionName) return `Runs fn.${String(c.functionName)}`
      if (typeof c.script !== 'string') return 'Choose a function'
      const outs = fieldsOf(c, 'outputs').map(f => f.name).filter(Boolean)
      return !c.script.trim() ? 'Write a script' : outs.length ? `Gives ${outs.join(', ')}` : 'Runs a script'
    }
    case 'BRANCH': return String(c.condition ?? 'Set a condition')
    case 'PARALLEL': return 'Every connected path runs'
    case 'JOIN': return 'Waits for every path that leads here'
    case 'WAIT': return c.duration ? `Waits ${describeDuration(String(c.duration))}` : c.until ? `Waits until ${String(c.until).replace('T', ' ')}` : 'Set how long to wait'
    case 'SUB_WORKFLOW': return c.workflowName ? `Runs "${String(c.workflowName)}"` : 'Choose a workflow'
    case 'FOR_EACH': return c.workflowName ? `Runs "${String(c.workflowName)}" for each of ${String(c.items ?? 'a list')}` : 'Choose a workflow and a list'
    case 'APPROVAL': return String(c.prompt || 'Waits for you to approve or reject')
    case 'HTTP_REQUEST': return c.url ? `${String(c.method ?? 'POST')} ${String(c.url)}` : 'Set a URL'
    case 'FILTER': return c.condition ? `Only if ${String(c.condition)}` : 'Set a condition'
    case 'SWITCH': {
      const cases = casesOf(c)
      return cases.length ? [...cases.map((k, i) => k.label.trim() || `Path ${i + 1}`), 'otherwise'].join(' · ') : 'Add a path'
    }
    case 'SET_VARIABLE': {
      const names = (Array.isArray(c.variables) ? c.variables as Array<{ name?: unknown }> : []).map(v => String(v?.name ?? '').trim()).filter(Boolean)
      return names.length ? `Sets ${names.map(v => `vars.${v}`).join(', ')}` : 'Add a variable'
    }
    case 'STORAGE': return c.key ? `${STORAGE_VERB[String(c.action ?? 'get')] ?? 'Uses'} "${String(c.key)}"` : 'Set a key'
    case 'NOTIFY': return String(c.title || c.message || 'Set a message')
    default: return 'The run finishes here'
  }
}

const STORAGE_VERB: Record<string, string> = { get: 'Reads', set: 'Saves', increment: 'Counts up', append: 'Adds to', delete: 'Forgets' }

/** "PT2H" as "2 hours", or the text itself when it isn't a simple duration. */
function describeDuration(iso: string): string {
  const m = /^P(?:(\d+)D|T(\d+)([HM]))$/.exec(iso)
  if (!m) return iso
  const [n, unit] = m[1] ? [Number(m[1]), 'day'] : [Number(m[2]), m[3] === 'H' ? 'hour' : 'minute']
  return `${n} ${unit}${n === 1 ? '' : 's'}`
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
  const tone = exitTone(data.branch)
  const stroke = selected ? 'rgb(var(--brand-500))' : data.travelled ? '#10b981' : tone === 'no' ? '#f87171' : tone === 'yes' ? '#34d399' : '#9ca3af'
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} interactionWidth={22}
        style={{ stroke, strokeWidth: active ? 2.5 : 2, strokeDasharray: tone === 'no' ? '6 4' : undefined }} />
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
export function StepPicker({ at, title, allowEnd = true, agents, onPick, onRecord, onClose }: {
  at: { x: number; y: number }; title: string; allowEnd?: boolean
  /** Agents offered as ready-made agent steps, under the step types. */
  agents?: AgentDto[]
  /** Offers "Record steps here" under Web Automation. */
  onRecord?: () => void
  onPick: (entry: NodeType | PaletteEntry, agent?: AgentDto) => void; onClose: () => void
}) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const options = useMemo(() => {
    const q = query.trim().toLowerCase()
    const steps: { type: NodeType; entry?: PaletteEntry; agent?: AgentDto }[] = PALETTE
      .filter(e => (allowEnd || e.type !== 'END') && (!q || `${e.label} ${NODE_META[e.type].desc}`.toLowerCase().includes(q)))
      .map(entry => ({ type: entry.type, entry }))
    // Only agents that can take work now; drafts, paused and archived ones would just fail the run.
    const ready = (agents ?? []).filter(a => !a.orchestrator && a.enabled && (a.status ?? 'active') === 'active'
      && (!q || `${a.name} ${a.description ?? ''} ${a.domain ?? ''}`.toLowerCase().includes(q)))
    return [...steps, ...ready.map(agent => ({ type: 'AGENT_STEP' as NodeType, entry: undefined, agent }))]
  }, [query, allowEnd, agents])

  useEffect(() => {
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) onClose() }
    window.addEventListener('pointerdown', away, true)
    return () => window.removeEventListener('pointerdown', away, true)
  }, [onClose])

  // Keep the list on screen when it opens near an edge.
  const left = Math.min(at.x, window.innerWidth - 272)
  const top = Math.min(at.y, window.innerHeight - 340)

  // In document.body: the slide-over around the canvas keeps a transform from its open animation,
  // which would place a "fixed" element against the panel instead of the window.
  return createPortal(
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
            if (e.key === 'Enter') { e.preventDefault(); if (options[active]) onPick(options[active].entry ?? options[active].type, options[active].agent) }
            if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose() }
          }}
          className="w-full bg-transparent text-sm text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
      </label>
      {onRecord && (!query.trim() || 'record steps here web automation'.includes(query.trim().toLowerCase())) && (
        <button type="button" onClick={onRecord}
          className="mb-1 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm font-medium text-gray-900 hover:bg-brand-50 dark:text-gray-100 dark:hover:bg-brand-500/15">
          <span className="grid h-6 w-6 place-items-center rounded-md bg-red-500 text-white"><span className="h-2 w-2 rounded-full bg-white" /></span>
          Record steps here
        </button>
      )}
      <ul role="listbox" className="max-h-64 overflow-y-auto">
        {options.map(({ type: t, entry, agent }, i) => {
          const Icon = NODE_ICON[t]
          const group = agent ? 'Your agents' : entry?.config?.preset === 'web' ? 'Web Automation' : groupOf(t)
          const prev = options[i - 1]
          const previousGroup = prev?.agent ? 'Your agents' : prev?.entry?.config?.preset === 'web' ? 'Web Automation' : prev ? groupOf(prev.type) : ''
          const heading = !prev || previousGroup !== group
          return (
            <li key={agent ? `agent:${agent.id}` : `${t}:${entry?.label}:${i}`} role="option" aria-selected={i === active}>
              {heading && <p className="px-2 pb-0.5 pt-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">{group}</p>}
              <button type="button" onMouseEnter={() => setActive(i)} onClick={() => onPick(entry ?? t, agent)}
                className={`flex w-full items-start gap-2 rounded-lg px-2 py-1.5 text-left ${i === active ? 'bg-brand-50 dark:bg-brand-500/15' : ''}`}>
                {agent
                  ? <span className="mt-0.5 shrink-0"><AgentLogo agentKey={agent.key} avatar={agent.avatar} size={24} /></span>
                  : <span className={`mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-md ${NODE_META[t].tone}`}><Icon className="h-3.5 w-3.5" /></span>}
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-gray-900 dark:text-gray-100">{agent ? agent.name : entry?.label ?? NODE_META[t].label}</span>
                  <span className="block truncate text-[11px] text-gray-500 dark:text-gray-400">{agent ? agent.description ?? agent.domain ?? 'Agent step' : NODE_META[t].desc}</span>
                </span>
              </button>
            </li>
          )
        })}
        {options.length === 0 && <li className="px-2 py-3 text-center text-xs text-gray-500">No step matches “{query}”.</li>}
      </ul>
    </div>,
    document.body,
  )
}

export interface MenuItem { label: string; shortcut?: string; danger?: boolean; disabled?: boolean; onSelect: () => void
  /** Draws a line above this item, to set it apart from the ones before. */
  divider?: boolean }

/** A right-click menu. */
/** Where a menu opens; {@code flipX} is where its right edge goes when it doesn't fit to the right of {@code x}. */
export interface MenuAt { x: number; y: number; flipX?: number }

const MENU_W = 208

export function ContextMenu({ at, items, onClose }: { at: MenuAt; items: MenuItem[]; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) onClose() }
    window.addEventListener('pointerdown', away, true)
    return () => window.removeEventListener('pointerdown', away, true)
  }, [onClose])
  // In document.body, for the same reason as StepPicker: otherwise it opens away from the step.
  return createPortal(
    <div ref={box} role="menu" style={{ left: at.x + MENU_W + 12 > window.innerWidth && at.flipX !== undefined ? Math.max(8, at.flipX - MENU_W) : Math.min(at.x, window.innerWidth - MENU_W - 12), top: Math.min(at.y, window.innerHeight - items.length * 34 - 16) }}
      className="fixed z-[70] w-52 rounded-xl border border-gray-200 bg-white p-1 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
      {items.map(item => (<div key={item.label}>
        {item.divider && <div role="separator" className="my-1 h-px bg-gray-200 dark:bg-gray-700" />}
        <button type="button" role="menuitem" disabled={item.disabled}
          onClick={() => { onClose(); item.onSelect() }}
          className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm disabled:opacity-40 ${item.danger
            ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-500/10'
            : 'text-gray-800 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
          <span className="flex-1">{item.label}</span>
          {item.shortcut && <kbd className="text-[11px] text-gray-400">{item.shortcut}</kbd>}
        </button>
      </div>))}
    </div>,
    document.body,
  )
}
