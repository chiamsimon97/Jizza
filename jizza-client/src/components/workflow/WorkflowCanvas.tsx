import { useRef, useState, type DragEvent, type PointerEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Minus, Plus, Trash2, X } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { api } from '@/lib/api'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { fieldClass } from '@/components/ui/SlideOver'
import type { AgentDto, ConnectionDto, GeneratedToolDto } from '@/types'
import {
  NODE_H, NODE_META, NODE_W, PALETTE, addNode, edgeLabelAt, edgePath, exitsOf, removeNode, setNext, targetsFor, updateNode,
  type CanvasGraph, type CanvasNode, type NodeType,
} from './canvasModel'

interface Props {
  graph: CanvasGraph
  onChange: (graph: CanvasGraph) => void
  readOnly?: boolean
}

/** A service action the user can pick, with the service it belongs to. */
interface ToolOption { id: string; label: string }

/**
 * The drag-and-drop workflow canvas, ported from the original Jizza builder. Drag a step from the
 * palette (or click it to add after the selected step), drag steps to arrange them, and click one
 * to set it up and choose where it goes next.
 */
export function WorkflowCanvas({ graph, onChange, readOnly = false }: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const drag = useRef<{ id: string; dx: number; dy: number; moved: boolean } | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)

  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const { data: tools } = useQuery({
    queryKey: ['workflow-canvas-tools'],
    queryFn: loadTools,
    enabled: !readOnly,
    staleTime: 60_000,
  })

  const selected = graph.nodes.find(n => n.id === selectedId) ?? null
  const byId = new Map(graph.nodes.map(n => [n.id, n]))
  const width = Math.max(...graph.nodes.map(n => n.x + NODE_W), 600) + 80
  const height = Math.max(...graph.nodes.map(n => n.y + NODE_H), 360) + 120

  function point(clientX: number, clientY: number) {
    const el = scroller.current!
    const rect = el.getBoundingClientRect()
    return { x: (clientX - rect.left + el.scrollLeft) / zoom, y: (clientY - rect.top + el.scrollTop) / zoom }
  }

  function add(type: NodeType, at?: { x: number; y: number }) {
    const anchor = selected ?? graph.nodes[graph.nodes.length - 1]
    const x = at ? Math.max(0, Math.round(at.x - NODE_W / 2)) : (anchor ? anchor.x + 250 : 30)
    const y = at ? Math.max(0, Math.round(at.y - NODE_H / 2)) : (anchor ? anchor.y : 150)
    const result = addNode(graph, type, x, y, selected?.id)
    onChange(result.graph)
    setSelectedId(result.id)
    // A step added by clicking can land outside the visible area; bring it into view.
    if (!at) requestAnimationFrame(() => scroller.current?.scrollTo({ left: Math.max(0, (x - 120) * zoom), top: Math.max(0, (y - 80) * zoom), behavior: 'smooth' }))
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    const type = e.dataTransfer.getData('application/x-jizza-node') as NodeType
    if (type) add(type, point(e.clientX, e.clientY))
  }

  function startMove(e: PointerEvent, node: CanvasNode) {
    if (readOnly || (e.target as HTMLElement).closest('button')) return
    const p = point(e.clientX, e.clientY)
    drag.current = { id: node.id, dx: p.x - node.x, dy: p.y - node.y, moved: false }
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }

  function move(e: PointerEvent) {
    const d = drag.current
    if (!d) return
    const p = point(e.clientX, e.clientY)
    const x = Math.max(0, Math.round(p.x - d.dx))
    const y = Math.max(0, Math.round(p.y - d.dy))
    const node = byId.get(d.id)
    if (!node || (Math.abs(node.x - x) < 2 && Math.abs(node.y - y) < 2 && !d.moved)) return
    d.moved = true
    onChange(updateNode(graph, d.id, { x, y }))
  }

  function endMove(node: CanvasNode) {
    const d = drag.current
    drag.current = null
    // A press that didn't move (or Enter on the keyboard) selects the step.
    if (!d || !d.moved) setSelectedId(node.id)
  }

  const agentFor = (n: CanvasNode) => agents?.find(a => a.id === n.config.agentId)

  return (
    <div className="flex h-[calc(100vh-19rem)] min-h-[380px] overflow-hidden rounded-xl border border-gray-200 dark:border-gray-800">
      {!readOnly && (
        <aside className="hidden w-44 shrink-0 flex-col gap-1.5 overflow-y-auto border-r border-gray-200 bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-950 sm:flex">
          <p className="px-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Add a step</p>
          <p className="px-1 text-[11px] leading-snug text-gray-400">Drag onto the canvas, or click to add after the selected step.</p>
          {PALETTE.map(type => (
            <button key={type} type="button" draggable
              onDragStart={e => e.dataTransfer.setData('application/x-jizza-node', type)}
              onClick={() => add(type)}
              className="rounded-lg border border-gray-200 bg-white px-2.5 py-2 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 dark:border-gray-800 dark:bg-gray-900 dark:hover:border-brand-700">
              <span className="flex items-center gap-1.5 text-xs font-medium text-gray-800 dark:text-gray-200">
                <span className={`h-2 w-2 rounded-full ${NODE_META[type].tone}`} />{NODE_META[type].label}
              </span>
              <span className="mt-0.5 block text-[11px] text-gray-500 dark:text-gray-400">{NODE_META[type].desc}</span>
            </button>
          ))}
        </aside>
      )}

      <div className="relative min-w-0 flex-1">
        <div ref={scroller} onDragOver={e => e.preventDefault()} onDrop={onDrop}
          onClick={e => { if (e.target === e.currentTarget) setSelectedId(null) }}
          className="h-full overflow-auto bg-[radial-gradient(circle,rgb(156_163_175/0.35)_1px,transparent_1px)] [background-size:20px_20px]">
          <div className="relative origin-top-left" style={{ width, height, transform: `scale(${zoom})` }}
            onClick={e => { if (e.target === e.currentTarget) setSelectedId(null) }}>
            <svg className="pointer-events-none absolute inset-0" width={width} height={height}>
              {graph.edges.map((e, i) => {
                const from = byId.get(e.from)
                const to = byId.get(e.to)
                if (!from || !to) return null
                return (
                  <path key={`${e.from}-${e.to}-${i}`} d={edgePath(from, to)} fill="none" strokeWidth={1.75}
                    className={e.branch === 'false' ? 'stroke-gray-400' : 'stroke-gray-400 dark:stroke-gray-500'}
                    strokeDasharray={e.branch === 'false' ? '5,4' : undefined} />
                )
              })}
            </svg>
            {graph.edges.filter(e => e.branch).map((e, i) => {
              const from = byId.get(e.from)
              const to = byId.get(e.to)
              if (!from || !to) return null
              return (
                <span key={`label-${i}`} className="absolute rounded bg-white px-1 text-[10px] font-semibold uppercase text-gray-500 shadow-sm dark:bg-gray-900"
                  style={(({ x, y }) => ({ left: x, top: y }))(edgeLabelAt(from, to))}>
                  {e.branch === 'true' ? 'yes' : 'no'}
                </span>
              )
            })}
            {graph.nodes.map(node => (
              <NodeCard key={node.id} node={node} agent={agentFor(node)} selected={node.id === selectedId} readOnly={readOnly}
                onPointerDown={e => startMove(e, node)} onPointerMove={move} onPointerUp={() => endMove(node)}
                onRemove={() => { onChange(removeNode(graph, node.id)); setSelectedId(null) }} />
            ))}
          </div>
        </div>
        <div className="absolute bottom-3 left-3 flex items-center gap-1 rounded-lg border border-gray-200 bg-white px-1 py-0.5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
          <ZoomButton label="Zoom out" onClick={() => setZoom(z => Math.max(0.5, +(z - 0.1).toFixed(2)))}><Minus className="h-3.5 w-3.5" /></ZoomButton>
          <span className="w-10 text-center text-xs text-gray-500">{Math.round(zoom * 100)}%</span>
          <ZoomButton label="Zoom in" onClick={() => setZoom(z => Math.min(1.5, +(z + 0.1).toFixed(2)))}><Plus className="h-3.5 w-3.5" /></ZoomButton>
        </div>
      </div>

      {selected && (
        <Inspector node={selected} graph={graph} agents={agents} tools={tools} readOnly={readOnly}
          onChange={onChange} onClose={() => setSelectedId(null)} />
      )}
    </div>
  )
}

function NodeCard({ node, agent, selected, readOnly, onPointerDown, onPointerMove, onPointerUp, onRemove }: {
  node: CanvasNode; agent?: AgentDto; selected: boolean; readOnly: boolean
  onPointerDown: (e: PointerEvent) => void; onPointerMove: (e: PointerEvent) => void; onPointerUp: () => void; onRemove: () => void
}) {
  const meta = NODE_META[node.type]
  const summary = summarise(node, agent)
  return (
    <div role="button" tabIndex={0} aria-label={`${meta.label}: ${node.label}`}
      onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPointerUp() } }}
      className={`group absolute flex select-none flex-col overflow-hidden rounded-xl border bg-white shadow-sm transition-shadow dark:bg-gray-900 ${
        readOnly ? 'cursor-pointer' : 'cursor-grab active:cursor-grabbing'} ${
        selected ? 'border-brand-500 ring-4 ring-brand-500/20' : 'border-gray-200 hover:shadow-md dark:border-gray-700'}`}
      style={{ left: node.x, top: node.y, width: NODE_W, height: NODE_H, touchAction: 'none' }}>
      <div className={`flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold ${meta.tone}`}>
        {node.type === 'AGENT_STEP' && agent && <AgentLogo agentKey={agent.key} size={14} />}
        <span className="truncate">{node.type === 'AGENT_STEP' && agent ? agent.name : meta.label}</span>
        {!readOnly && node.type !== 'TRIGGER' && (
          <button type="button" aria-label={`Remove ${node.label}`} onClick={e => { e.stopPropagation(); onRemove() }}
            className="ml-auto rounded p-0.5 opacity-0 transition-opacity hover:bg-black/15 group-hover:opacity-100 focus:opacity-100">
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      <div className="min-h-0 flex-1 px-2.5 py-1.5">
        <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{node.label}</p>
        {summary && <p className="line-clamp-2 text-[11px] leading-snug text-gray-500 dark:text-gray-400">{summary}</p>}
      </div>
    </div>
  )
}

function summarise(node: CanvasNode, agent?: AgentDto): string {
  const c = node.config
  switch (node.type) {
    case 'AGENT_STEP': return String(c.instruction ?? (agent ? '' : 'Choose an agent'))
    case 'HUMAN_INTERVENTION': return String(c.prompt ?? 'Pauses and asks you')
    case 'CONNECTOR_TOOL': return c.toolName ? String(c.toolName) : c.toolId ? 'Service action set' : 'Choose a service action'
    case 'TRANSFORM': return String(c.expression ?? 'Set an expression')
    case 'BRANCH': return String(c.condition ?? 'Set a condition')
    default: return ''
  }
}

function Inspector({ node, graph, agents, tools, readOnly, onChange, onClose }: {
  node: CanvasNode; graph: CanvasGraph; agents?: AgentDto[]; tools?: ToolOption[]; readOnly: boolean
  onChange: (g: CanvasGraph) => void; onClose: () => void
}) {
  const setConfig = (patch: Record<string, unknown>) => onChange(updateNode(graph, node.id, { config: { ...node.config, ...patch } }))
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  const targets = targetsFor(graph, node.id)
  const nextOf = (b: 'true' | 'false' | null) => graph.edges.find(e => e.from === node.id && e.branch === b)?.to ?? ''

  return (
    <aside className="flex w-72 shrink-0 flex-col gap-3 overflow-y-auto border-l border-gray-200 bg-white p-3 animate-fade-in dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center gap-2">
        <span className={`rounded-md px-2 py-0.5 text-[11px] font-semibold ${NODE_META[node.type].tone}`}>{NODE_META[node.type].label}</span>
        <span className="flex-1" />
        <button type="button" onClick={onClose} aria-label="Close step settings"
          className="grid h-7 w-7 place-items-center rounded-md text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><X className="h-4 w-4" /></button>
      </div>
      <fieldset disabled={readOnly} className="flex flex-col gap-3">
        <Labelled label="Name">
          <input className={fieldClass} value={node.label} onChange={e => onChange(updateNode(graph, node.id, { label: e.target.value }))} />
        </Labelled>

        {node.type === 'AGENT_STEP' && <>
          <Labelled label="Agent">
            <select className={fieldClass} value={str('agentId')} onChange={e => setConfig({ agentId: e.target.value || undefined })}>
              <option value="">Choose an agent…</option>
              {agents?.map(a => <option key={a.id} value={a.id}>{a.name}{a.domain ? ` · ${a.domain}` : ''}</option>)}
            </select>
          </Labelled>
          <Labelled label="What should the agent do?">
            <textarea className={fieldClass} rows={4} value={str('instruction')} onChange={e => setConfig({ instruction: e.target.value })} />
          </Labelled>
        </>}

        {node.type === 'HUMAN_INTERVENTION' && (
          <Labelled label="What should it ask you?">
            <textarea className={fieldClass} rows={3} value={str('prompt')} onChange={e => setConfig({ prompt: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'CONNECTOR_TOOL' && <>
          <Labelled label="Service action" hint={tools && tools.length === 0 ? 'No connected services have actions yet. Add one under Connectors.' : undefined}>
            <select className={fieldClass} value={str('toolId')}
              onChange={e => setConfig({ toolId: e.target.value || undefined, toolName: tools?.find(t => t.id === e.target.value)?.label })}>
              <option value="">Choose an action…</option>
              {str('toolId') && !tools?.some(t => t.id === str('toolId')) && <option value={str('toolId')}>{str('toolName') || 'Current action'}</option>}
              {tools?.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
            </select>
          </Labelled>
          <JsonField label="Arguments (JSON)" value={node.config.arguments} onChange={v => setConfig({ arguments: v })} />
        </>}

        {node.type === 'TRANSFORM' && (
          <Labelled label="Expression" hint="JEXL over input, nodes and instructions.">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={str('expression')} onChange={e => setConfig({ expression: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'BRANCH' && (
          <Labelled label="Condition" hint="A JEXL test, e.g. nodes.check.price < 2000">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value })} />
          </Labelled>
        )}

        {exitsOf(node.type).map(b => (
          <Labelled key={String(b)} label={b === 'true' ? 'If yes, go to' : b === 'false' ? 'If no, go to' : 'Then go to'}>
            <select className={fieldClass} value={nextOf(b)} onChange={e => onChange(setNext(graph, node.id, b, e.target.value || null))}>
              <option value="">Nothing (the run ends here)</option>
              {targets.map(t => <option key={t.id} value={t.id}>{t.label} · {NODE_META[t.type].label}</option>)}
            </select>
          </Labelled>
        ))}
      </fieldset>

      {!readOnly && node.type !== 'TRIGGER' && (
        <button type="button" onClick={() => { onChange(removeNode(graph, node.id)); onClose() }}
          className="mt-auto inline-flex items-center justify-center gap-1.5 rounded-lg border border-red-200 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:border-red-500/30 dark:text-red-400 dark:hover:bg-red-500/10">
          <Trash2 className="h-3.5 w-3.5" /> Remove step
        </button>
      )}
    </aside>
  )
}

/** Edits a JSON value as text, keeping the text while it doesn't parse. */
function JsonField({ label, value, onChange }: { label: string; value: unknown; onChange: (v: unknown) => void }) {
  const [textValue, setText] = useState(() => (value === undefined ? '' : JSON.stringify(value, null, 2)))
  const [bad, setBad] = useState(false)
  return (
    <Labelled label={label} hint={bad ? 'This is not valid JSON yet.' : 'Values can use ${...} to read earlier steps.'}>
      <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={textValue}
        onChange={e => {
          setText(e.target.value)
          if (!e.target.value.trim()) { setBad(false); onChange(undefined); return }
          try { onChange(JSON.parse(e.target.value)); setBad(false) } catch { setBad(true) }
        }} />
    </Labelled>
  )
}

function Labelled({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{label}</span>
      {children}
      {hint && <span className="text-[11px] text-gray-400">{hint}</span>}
    </label>
  )
}

function ZoomButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} onClick={onClick}
      className="grid h-6 w-6 place-items-center rounded text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800">{children}</button>
  )
}

/** Every service action the user can put in a workflow. Without connector access the list is empty. */
async function loadTools(): Promise<ToolOption[]> {
  try {
    const connections = await api.get<ConnectionDto[]>('/api/connections')
    const lists = await Promise.all(connections.map(c =>
      api.get<GeneratedToolDto[]>(`/api/connections/${c.id}/tools`)
        .then(ts => ts.filter(t => t.enabled).map(t => ({ id: t.id, label: `${c.serviceName} · ${t.name}` })))
        .catch(() => [] as ToolOption[])))
    return lists.flat()
  } catch {
    return []
  }
}
