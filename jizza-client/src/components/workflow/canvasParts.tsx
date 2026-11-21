import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  Bell, Bot, Braces, Clock, Code2, Database, Filter, Flag, GitBranch, GitMerge, Hand, Play, Plug, Repeat, Search,
  Server, ShieldCheck, Shuffle, Signpost, Split, Webhook, Workflow,
  MonitorSmartphone,
} from 'lucide-react'
import { AgentLogo } from '@/components/brand/AgentLogo'
import type { AgentDto } from '@/types'
import { NODE_META, PALETTE, casesOf, groupOf, isCheckStep, isLayerStep, layerButtonsOf, type CanvasNode, type ExitKey, type NodeType, type PaletteEntry } from './canvasModel'
import { fieldsOf } from './moduleContract'

export type Exit = ExitKey
/** How a step did in the run shown in the builder. */
export type StepRun = 'done' | 'failed' | 'skipped' | 'running' | 'waiting'

/** What an exit is called next to its dot. A paths step's cases go by their labels, so it needs the node. */
export function exitLabel(b: Exit, node?: CanvasNode): string {
  if (b === null) return isCheckStep(node) ? 'yes' : ''
  if (b === 'true') return 'yes'
  if (b === 'false') return 'no'
  if (b === 'else') return 'otherwise'
  if (b === 'approved' || b === 'rejected') return b
  if (isLayerStep(node)) {
    const buttons = layerButtonsOf(node)
    const i = buttons.findIndex(x => x.id === b)
    return i < 0 ? b : buttons[i].name.trim() || `button ${i + 1}`
  }
  if (node?.type === 'SWITCH') {
    const cases = casesOf(node.config)
    const i = cases.findIndex(c => c.id === b)
    return i < 0 ? b : cases[i].label.trim() || `path ${i + 1}`
  }
  return 'abc'.includes(b) ? `path ${'abc'.indexOf(b) + 1}` : b
}

export const NODE_ICON: Record<NodeType, typeof Bot> = {
  TRIGGER: Play, AGENT_STEP: Bot, HUMAN_INTERVENTION: Hand, CONNECTOR_TOOL: Plug, TRANSFORM: Shuffle, BRANCH: GitBranch, END: Flag,
  PARALLEL: Split, JOIN: GitMerge, WAIT: Clock, SUB_WORKFLOW: Workflow, FOR_EACH: Repeat,
  APPROVAL: ShieldCheck, HTTP_REQUEST: Webhook, FILTER: Filter, SWITCH: Signpost, SET_VARIABLE: Braces, STORAGE: Database, NOTIFY: Bell,
  MCP_TOOL: Server, SCRIPT: Code2, DEVICE_ACTION: MonitorSmartphone,
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
    case 'PARALLEL': return 'Every connected path runs at once'
    case 'JOIN': return c.timeout === 'none' ? 'Waits for every path that leads here'
      : `Waits up to ${describeDuration(String(c.timeout ?? 'PT1H'))} for every path${c.onTimeout === 'fail' ? ', then fails' : ''}`
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
  // Once it is drawn, move it up if it runs off the bottom of the window (a long menu, or one opened low).
  const [top, setTop] = useState(Math.min(at.y, window.innerHeight - items.length * 34 - 16))
  useLayoutEffect(() => {
    const h = box.current?.offsetHeight ?? 0
    setTop(Math.max(8, Math.min(at.y, window.innerHeight - h - 8)))
  }, [at.y, items.length])
  // In document.body, for the same reason as StepPicker: otherwise it opens away from the step.
  return createPortal(
    <div ref={box} role="menu" style={{ left: at.x + MENU_W + 12 > window.innerWidth && at.flipX !== undefined ? Math.max(8, at.flipX - MENU_W) : Math.min(at.x, window.innerWidth - MENU_W - 12), top }}
      className="fixed z-[70] w-52 rounded-xl border border-gray-200 bg-white p-1 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
      {items.map(item => (<div key={item.label}>
        {item.divider && <div role="separator" className="my-1 h-px bg-gray-200 dark:bg-gray-700" />}
        <button type="button" role="menuitem" disabled={item.disabled}
          onClick={() => { onClose(); item.onSelect() }}
          className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm disabled:opacity-40 ${item.danger
            ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-500/10'
            : 'text-gray-800 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
          <span className="flex-1">{item.label}</span>
          {item.shortcut && <kbd className="shrink-0 whitespace-nowrap text-[11px] text-gray-400">{item.shortcut}</kbd>}
        </button>
      </div>))}
    </div>,
    document.body,
  )
}
