import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowDown, Check, ChevronDown, Copy, Globe, Pencil, Play, Plus, Scissors, Search, Sparkles, Tag, Trash2, X } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { mcpApi } from '@/api/mcp'
import { functionsApi, type ScriptFunction } from '@/api/functions'
import { api } from '@/lib/api'
import type { AgentDto, McpServer, RunDto, WorkflowDto } from '@/types'
import {
  NODE_META, PALETTE_GROUPS, addNode, applyStepEdit, cloneNodes, exitsOf, mainExitsOf, freeSpot, insertOnEdge, issues as findIssues, newNode, setNext, targetsFor,
  unreachable, updateNode, type CanvasEdge, type CanvasGraph, type ExitKey, type NodeType, type PaletteEntry,
} from './canvasModel'
import { NODE_ICON, StepPicker, exitLabel, summarise, type StepRun } from './canvasParts'
import { CanvasInspector, type WorkflowOption } from './CanvasInspector'
import { RUN_LABEL, loadTools, runState } from './WorkflowCanvas'
import { lockedChange, lockedGroupOf, updateGroup } from './groupModel'
import {
  CARD_H, CARD_W, autoAlign, deleteStep, distanceTo, dropOnStep, exitPoint, inPoint, isArranged, keepWiresSettings, placeStep, previewPath, route, setWireLabel, stepNumbers,
  wireKey, wireLabel, wiresLayout, type Route,
} from './wiresModel'

interface Props {
  graph: CanvasGraph
  onChange: (graph: CanvasGraph) => void
  readOnly?: boolean
  /** A run to show: arrows from Start along the steps it went through. */
  run?: RunDto | null
  /** A step to open the settings of, such as the one a fix or a failed run points at. */
  focusNode?: string | null
}

/** Where a step being dragged in would go. */
type Suggestion = { kind: 'step'; id: string } | { kind: 'wire'; edge: CanvasEdge }

/** What the board shows of a run, real or previewed. */
interface RunView { nodes: Map<string, StepRun>; travelled: Set<string>; live: boolean }

const HOVER_BLUE = '#2f6fde'
const WIRE_TONE = (b: ExitKey) => (b === 'true' || b === 'approved'
  ? { line: '#9db9ec', head: '#7fa3e6', text: 'text-blue-700 dark:text-blue-300' }
  : b === 'false' || b === 'rejected'
    ? { line: '#ebbd92', head: '#e2a268', text: 'text-orange-700 dark:text-orange-300' }
    : { line: '#c8bdb3', head: '#ab9d91', text: 'text-gray-600 dark:text-gray-300' })

const ARROW_HEAD = (b: [number, number], big = false) => {
  const w = big ? 6 : 4.5
  const h = big ? 11 : 8
  return `${b[0] - w},${b[1] - h} ${b[0] + w},${b[1] - h} ${b[0]},${b[1]}`
}

const STEP_RING: Record<StepRun, string> = {
  done: 'border-brand-500 ring-4 ring-brand-500/15 bg-brand-50/60 dark:bg-brand-500/10',
  running: 'border-brand-500 jw-running',
  waiting: 'border-amber-500 ring-4 ring-amber-500/15',
  failed: 'border-red-500 ring-4 ring-red-500/15',
  skipped: 'border-dashed opacity-50',
}
const STEP_BADGE: Record<StepRun, { text: string; tone: string }> = {
  done: { text: 'Done', tone: 'bg-brand-500 text-white' },
  running: { text: 'Running', tone: 'bg-brand-500 text-white' },
  waiting: { text: 'Waiting for you', tone: 'bg-amber-500 text-white' },
  failed: { text: 'Failed', tone: 'bg-red-500 text-white' },
  skipped: { text: 'Skipped', tone: 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-200' },
}

const assignAgent = (graph: CanvasGraph, id: string, agent: AgentDto) => {
  const node = graph.nodes.find(n => n.id === id)
  return updateNode(graph, id, { label: agent.name, config: { ...node?.config, agentId: agent.id } })
}

const parseEntry = (raw: string): NodeType | PaletteEntry | null => {
  if (!raw) return null
  if (raw.startsWith('{')) { try { return JSON.parse(raw) as PaletteEntry } catch { return null } }
  return raw in NODE_META ? raw as NodeType : null
}

/**
 * The Wires view of a workflow: the same graph as the canvas, laid out top to bottom with
 * circuit-style wires. Drag a step onto a step or a wire and it wires itself in; hover a wire to
 * delete it, put a step on it or label it; hover a step to edit, clone or delete it.
 */
export function WiresBoard({ graph, onChange, readOnly = false, run, focusNode }: Props) {
  const { data: agents } = useQuery({ queryKey: ['agents', 'all'], queryFn: () => agentsApi.list('all') })
  const { data: tools } = useQuery({ queryKey: ['workflow-canvas-tools'], queryFn: loadTools, enabled: !readOnly, staleTime: 60_000 })
  const { data: mcpServers } = useQuery({ queryKey: ['mcp-servers'], queryFn: () => mcpApi.list().catch(() => [] as McpServer[]), enabled: !readOnly, staleTime: 30_000 })
  const { data: functions } = useQuery({ queryKey: ['functions'], queryFn: () => functionsApi.list().catch(() => [] as ScriptFunction[]), staleTime: 30_000 })
  const { data: workflows } = useQuery({
    queryKey: ['workflow-canvas-workflows'], enabled: !readOnly, staleTime: 60_000,
    queryFn: () => api.get<WorkflowDto[]>('/api/workflows')
      .then(ws => ws.map((w): WorkflowOption => ({ id: w.id, name: w.name, status: w.status, graph: w.graph }))).catch(() => [] as WorkflowOption[]),
  })

  const content = useRef<HTMLDivElement>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [editing, setEditing] = useState<string | null>(focusNode ?? null)
  const unsaved = useRef(false)
  const onDirty = useCallback((d: boolean) => { unsaved.current = d }, [])
  const [hoverWire, setHoverWire] = useState<string | null>(null)
  const [hoverStep, setHoverStep] = useState<string | null>(null)
  const wireTimer = useRef<number>()
  const stepTimer = useRef<number>()
  const [labelFor, setLabelFor] = useState<CanvasEdge | null>(null)
  const [labelDraft, setLabelDraft] = useState('')
  const [picker, setPicker] = useState<{ edge: CanvasEdge; at: { x: number; y: number } } | null>(null)
  /** An exit clicked to wire it by hand; the next step clicked is where it goes. */
  const [pending, setPending] = useState<{ from: string; branch: ExitKey } | null>(null)
  const dragged = useRef<NodeType | PaletteEntry | null>(null)
  const [suggest, setSuggest] = useState<Suggestion | null>(null)
  const [dragging, setDragging] = useState(false)
  /** A step being moved by hand: where it is under the pointer. */
  const [moving, setMoving] = useState<{ id: string; x: number; y: number } | null>(null)
  const grab = useRef<{ id: string; sx: number; sy: number; ox: number; oy: number; moved: boolean } | null>(null)
  const justMoved = useRef(false)
  const [toast, setToast] = useState('')
  const toastTimer = useRef<number>()
  const [preview, setPreview] = useState<number | null>(null)
  const previewTimer = useRef<number>()
  const [showRun, setShowRun] = useState(true)
  const [paletteQuery, setPaletteQuery] = useState('')
  const [folded, setFolded] = useState<Set<string>>(() => new Set(['Web Automation', 'Data', 'Flow control']))

  useEffect(() => { if (focusNode) setEditing(focusNode) }, [focusNode])
  useEffect(() => () => {
    window.clearTimeout(wireTimer.current); window.clearTimeout(stepTimer.current)
    window.clearTimeout(toastTimer.current); window.clearInterval(previewTimer.current)
  }, [])

  const flash = useCallback((text: string) => {
    setToast(text)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(''), 3200)
  }, [])

  /** An edit to the graph. Any preview stops, as it no longer matches the flow. */
  const commit = useCallback((next: CanvasGraph) => {
    // Step groups are drawn on the Canvas; their locks hold here too.
    const why = lockedChange(graph, next)
    if (why) { flash(why); return }
    window.clearInterval(previewTimer.current)
    setPreview(null)
    onChange(next)
  }, [onChange, graph, flash])

  const mayLeave = useCallback(() => {
    if (!unsaved.current) return true
    if (!window.confirm('Leave this step without saving your changes?')) return false
    unsaved.current = false
    return true
  }, [])
  const openSettings = useCallback((id: string | null) => {
    if (id === editing || !mayLeave()) return
    setEditing(id)
    if (id) setSelected(id)
  }, [editing, mayLeave])

  // ---- Layout and wires -------------------------------------------------------------------

  // While a step is being moved, everything (its wires too) is laid out with it where the pointer is.
  const shown = useMemo(() => (moving ? placeStep(graph, moving.id, moving) : graph), [graph, moving])
  const layout = useMemo(() => wiresLayout(shown), [shown])
  const arranged = useMemo(() => isArranged(graph), [graph])
  const byId = useMemo(() => new Map(graph.nodes.map(n => [n.id, n])), [graph])
  const routes = useMemo(() => {
    const map = new Map<string, Route & { edge: CanvasEdge; end: [number, number] }>()
    for (const e of graph.edges) {
      const from = byId.get(e.from)
      const a = layout.at.get(e.from)
      const b = layout.at.get(e.to)
      if (!from || !a || !b) continue
      const end = inPoint(b)
      const nextRow = (layout.depth.get(e.to) ?? 0) === (layout.depth.get(e.from) ?? 0) + 1
      map.set(wireKey(e), { ...route(exitPoint(exitsOf(from), a, e.branch), end, nextRow), edge: e, end })
    }
    return map
  }, [graph, byId, layout])

  const numbers = useMemo(() => stepNumbers(graph), [graph])
  const issueList = useMemo(() => findIssues(graph), [graph])
  const issuesByNode = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const i of issueList) if (i.nodeId) map.set(i.nodeId, [...(map.get(i.nodeId) ?? []), i.message])
    return map
  }, [issueList])
  const orphans = useMemo(() => unreachable(graph), [graph])
  const startId = graph.nodes.find(n => n.type === 'TRIGGER')?.id

  // ---- What a run (or a preview) shows ------------------------------------------------------

  const plan = useMemo(() => (preview !== null ? previewPath(graph) : null), [graph, preview])
  const realRun = useMemo(() => (run && showRun ? runState(run, startId) : null), [run, showRun, startId])
  const view: RunView | null = useMemo(() => {
    if (plan && preview !== null) {
      const nodes = new Map<string, StepRun>()
      for (const [id, d] of plan.step) if (d < preview) nodes.set(id, 'done'); else if (d === preview) nodes.set(id, 'running')
      for (const e of graph.edges) if (nodes.get(e.from) === 'done' && !plan.step.has(e.to)) nodes.set(e.to, 'skipped')
      const travelled = new Set([...plan.edges].filter(([, d]) => d <= preview).map(([k]) => k))
      return { nodes, travelled, live: preview <= plan.last }
    }
    if (!realRun) return null
    const travelled = new Set<string>()
    for (const e of graph.edges) {
      const reached = realRun.nodes.get(e.to)
      if (realRun.ran.has(e.from) && (realRun.ran.has(e.to) || reached === 'running' || reached === 'waiting' || reached === 'failed')) travelled.add(wireKey(e))
    }
    return { nodes: realRun.nodes, travelled, live: realRun.live }
  }, [plan, preview, realRun, graph.edges])

  const startPreview = () => {
    window.clearInterval(previewTimer.current)
    const last = previewPath(graph).last
    setPreview(0)
    setPending(null)
    let t = 0
    previewTimer.current = window.setInterval(() => {
      t += 1
      setPreview(t)
      if (t > last) window.clearInterval(previewTimer.current)
    }, 800)
  }
  const stopPreview = () => { window.clearInterval(previewTimer.current); setPreview(null) }

  // ---- Hover --------------------------------------------------------------------------------

  const quiet = !readOnly && !dragging && !moving && preview === null && !pending && !labelFor && !picker
  const wireOn = (k: string) => { window.clearTimeout(wireTimer.current); setHoverWire(k) }
  const wireOff = () => { window.clearTimeout(wireTimer.current); wireTimer.current = window.setTimeout(() => setHoverWire(null), 200) }
  const stepOn = (id: string) => { window.clearTimeout(stepTimer.current); setHoverStep(id) }
  const stepOff = () => { window.clearTimeout(stepTimer.current); stepTimer.current = window.setTimeout(() => setHoverStep(null), 200) }

  // ---- Dragging a step in -------------------------------------------------------------------

  const boardPoint = (e: { clientX: number; clientY: number }) => {
    const r = content.current?.getBoundingClientRect()
    return r ? { x: e.clientX - r.left, y: e.clientY - r.top } : null
  }

  const suggestionAt = (x: number, y: number): Suggestion | null => {
    for (const n of graph.nodes) {
      const b = layout.at.get(n.id)
      if (!b || exitsOf(n).length === 0) continue
      if (x >= b.x - 16 && x <= b.x + CARD_W + 16 && y >= b.y - 16 && y <= b.y + CARD_H + 16) return { kind: 'step', id: n.id }
    }
    let best: { edge: CanvasEdge; d: number } | null = null
    for (const r of routes.values()) {
      const d = distanceTo(r.pts, x, y)
      if (d < 16 && (!best || d < best.d)) best = { edge: r.edge, d }
    }
    return best ? { kind: 'wire', edge: best.edge } : null
  }

  const sameSuggestion = (a: Suggestion | null, b: Suggestion | null) =>
    a?.kind === b?.kind && (a?.kind === 'step' ? a.id === (b as typeof a).id : a?.kind === 'wire' ? wireKey(a.edge) === wireKey((b as typeof a).edge) : true)

  const onDragOver = (e: DragEvent) => {
    if (readOnly || !dragged.current) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    const p = boardPoint(e)
    const next = p ? suggestionAt(p.x, p.y) : null
    if (!sameSuggestion(next, suggest)) setSuggest(next)
  }

  const endDrag = () => { dragged.current = null; setDragging(false); setSuggest(null) }

  /** Where a step not wired to anything goes on the canvas: right of everything else. */
  const looseSpot = () => freeSpot(graph, Math.max(30, ...graph.nodes.map(n => n.x)) + 250, 150)

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    const entry = dragged.current ?? parseEntry(e.dataTransfer.getData('application/x-jizza-node'))
    const p = boardPoint(e)
    const target = p ? suggestionAt(p.x, p.y) : null
    endDrag()
    if (!entry || readOnly) return
    if (target?.kind === 'wire') {
      const r = insertOnEdge(graph, target.edge, entry)
      commit(r.graph)
      setSelected(r.id)
      flash(`Added between “${byId.get(target.edge.from)?.label}” and “${byId.get(target.edge.to)?.label}”.`)
      return
    }
    if (target?.kind === 'step') {
      const r = dropOnStep(graph, target.id, entry)
      if (r) {
        commit(r.graph)
        setSelected(r.id)
        flash(`Wired in after “${byId.get(target.id)?.label}”.`)
        return
      }
    }
    const at = looseSpot()
    const node = newNode(entry, at.x, at.y)
    commit({ ...graph, nodes: [...graph.nodes, node] })
    setSelected(node.id)
    flash('Added. Click an exit dot, then this step, to wire it in.')
  }

  const addFromPalette = (entry: NodeType | PaletteEntry) => {
    const r = selected ? dropOnStep(graph, selected, entry) : null
    if (r) { commit(r.graph); setSelected(r.id); flash(`Wired in after “${byId.get(selected!)?.label}”.`); return }
    // Without a selected step it goes after the last step with a free exit.
    const last = [...graph.nodes].reverse().find(n => mainExitsOf(n).some(b => !graph.edges.some(e => e.from === n.id && e.branch === b)))
    const at = looseSpot()
    const added = addNode(graph, entry, at.x, at.y, last?.id ?? null)
    commit(added.graph)
    setSelected(added.id)
  }

  // ---- Wiring by hand -----------------------------------------------------------------------

  const validTargets = useMemo(() => (pending ? new Set(targetsFor(graph, pending.from).map(n => n.id)) : null), [graph, pending])
  const connectTo = (id: string) => {
    if (!pending || !validTargets?.has(id)) return
    commit(setNext(graph, pending.from, pending.branch, id))
    setPending(null)
    flash('Wired.')
  }

  useEffect(() => {
    if (!pending && !labelFor) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setPending(null); setLabelFor(null) } }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [pending, labelFor])

  // ---- Step and wire actions ----------------------------------------------------------------

  const cloneStep = (id: string) => {
    const r = cloneNodes(graph, [id], graph, 60)
    commit(r.graph)
    setSelected(r.ids[0] ?? null)
    setHoverStep(null)
    flash('Cloned. Click an exit dot, then the copy, to wire it in.')
  }
  const removeStep = (id: string) => {
    if (editing === id) { unsaved.current = false; setEditing(null) }
    const r = deleteStep(graph, id)
    commit(r.graph)
    setSelected(null)
    setHoverStep(null)
    flash(r.joined ? 'Deleted. The steps either side are still joined.' : 'Deleted.')
  }
  const saveLabel = () => {
    if (labelFor) commit(setWireLabel(graph, labelFor, labelDraft))
    setLabelFor(null)
  }

  // ---- Status -------------------------------------------------------------------------------

  const ranCount = view ? [...view.nodes.values()].filter(s => s === 'done').length : 0
  let status = toast || (readOnly ? 'Read-only. Click a step to see its settings.' : 'Drag a step to move it, or drop a new one onto a step or a wire. Hover for options.')
  if (moving) status = 'Let go to put the step here. Auto align lines everything up again.'
  if (pending) status = `Click the step that comes after “${byId.get(pending.from)?.label}”${pending.branch ? ` (${exitLabel(pending.branch, byId.get(pending.from))})` : ''}. Esc cancels.`
  if (dragging) status = suggest?.kind === 'step' ? `Drop to wire it in after “${byId.get(suggest.id)?.label}”.`
    : suggest?.kind === 'wire' ? 'Drop to put it on this wire. Steps below move down.' : 'Hover a step or a wire to wire it in.'
  if (preview !== null && plan) status = preview > plan.last ? `Preview done: ${ranCount} steps on the path. Nothing ran.` : 'Previewing the path from Start. Nothing runs.'

  const focusIds = useMemo(() => {
    if (!suggest) return null
    if (suggest.kind === 'step') return new Set([suggest.id])
    return new Set([suggest.edge.from, suggest.edge.to])
  }, [suggest])

  const palette = useMemo(() => {
    const q = paletteQuery.trim().toLowerCase()
    return PALETTE_GROUPS
      .map(g => ({ ...g, entries: g.entries.filter(e => !q || `${e.label} ${NODE_META[e.type].desc} ${g.title}`.toLowerCase().includes(q)) }))
      .filter(g => g.entries.length)
  }, [paletteQuery])

  const editNode = editing ? byId.get(editing) ?? null : null
  const hoveredRoute = quiet && hoverWire ? routes.get(hoverWire) : undefined
  const hoveredStep = quiet && hoverStep ? byId.get(hoverStep) : undefined
  const hoveredBox = hoveredStep ? layout.at.get(hoveredStep.id) : undefined
  const labelRoute = labelFor ? routes.get(wireKey(labelFor)) : undefined

  return (
    <div className="jizza-wires flex h-[calc(100vh-19rem)] min-h-[560px] overflow-hidden rounded-xl border border-gray-200 dark:border-gray-800">
      <style>{WIRES_CSS}</style>
      {!readOnly && (
        <aside className="hidden w-52 shrink-0 flex-col gap-1.5 overflow-y-auto border-r border-gray-200 bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-950 sm:flex">
          <p className="px-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Add a step</p>
          <label className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2 py-1 dark:border-gray-700 dark:bg-gray-900">
            <Search className="h-3.5 w-3.5 text-gray-400" />
            <input value={paletteQuery} onChange={e => setPaletteQuery(e.target.value)} placeholder="Search" aria-label="Search steps"
              className="w-full bg-transparent text-xs text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
          </label>
          <p className="px-1 text-[11px] leading-snug text-gray-400">Drag onto a step or a wire and it wires itself in. Or click to add after the selected step.</p>
          {palette.map(group => {
            const open = !!paletteQuery.trim() || !folded.has(group.title)
            return (
              <section key={group.title} className="flex flex-col gap-1.5">
                <button type="button" aria-expanded={open}
                  onClick={() => setFolded(f => { const n = new Set(f); if (n.has(group.title)) n.delete(group.title); else n.add(group.title); return n })}
                  className="mt-1 flex items-center gap-1 px-1 text-left text-[11px] font-semibold uppercase tracking-wide text-gray-500 hover:text-gray-800 dark:hover:text-gray-200">
                  <ChevronDown className={`h-3 w-3 transition-transform ${open ? '' : '-rotate-90'}`} />
                  {group.icon === 'globe' && <Globe className="h-3 w-3" />}
                  <span className="flex-1">{group.title}</span>
                  <span className="font-normal normal-case text-gray-400">{group.entries.length}</span>
                </button>
                {open && group.entries.map((entry, index) => {
                  const Icon = group.icon === 'globe' ? Globe : NODE_ICON[entry.type]
                  return (
                    <button key={`${entry.type}:${entry.label}:${index}`} type="button" draggable
                      onDragStart={e => {
                        dragged.current = entry.config ? entry : entry.type
                        e.dataTransfer.setData('application/x-jizza-node', entry.config ? JSON.stringify(entry) : entry.type)
                        e.dataTransfer.effectAllowed = 'move'
                        setDragging(true)
                        setHoverWire(null)
                        setHoverStep(null)
                      }}
                      onDragEnd={endDrag}
                      onClick={() => addFromPalette(entry.config ? entry : entry.type)}
                      className="flex cursor-grab items-start gap-2 rounded-lg border border-gray-200 bg-white px-2 py-2 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 dark:border-gray-800 dark:bg-gray-900 dark:hover:border-brand-700">
                      <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-md ${NODE_META[entry.type].tone}`}><Icon className="h-3.5 w-3.5" /></span>
                      <span className="min-w-0">
                        <span className="block text-xs font-medium text-gray-800 dark:text-gray-200">{entry.label}</span>
                        <span className="block text-[11px] leading-snug text-gray-500 dark:text-gray-400">{NODE_META[entry.type].desc}</span>
                      </span>
                    </button>
                  )
                })}
              </section>
            )
          })}
          {palette.length === 0 && <p className="px-1 text-xs text-gray-500">No step matches “{paletteQuery}”.</p>}
        </aside>
      )}

      <div className="relative min-w-0 flex-1">
        <div className="absolute inset-x-0 top-0 z-20 flex flex-wrap items-center justify-between gap-2 border-b border-gray-200 bg-white/95 px-3 py-2 backdrop-blur dark:border-gray-800 dark:bg-gray-900/95">
          <div className="flex min-w-0 items-center gap-2">
            <span className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-medium ring-1 ring-inset ${issueList.length
              ? 'bg-red-50 text-red-700 ring-red-500/20 dark:bg-red-500/10 dark:text-red-300'
              : 'bg-emerald-50 text-emerald-700 ring-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-300'}`}
              title={issueList.map(i => i.message).join('\n')}>
              {issueList.length ? `${issueList.length} ${issueList.length === 1 ? 'thing' : 'things'} to fix` : 'Ready to save'}
            </span>
            {run && preview === null && (
              <button type="button" onClick={() => setShowRun(v => !v)}
                className="shrink-0 rounded-full bg-white px-2.5 py-1 text-[11px] font-medium text-gray-700 ring-1 ring-inset ring-gray-200 hover:bg-gray-50 dark:bg-gray-900 dark:text-gray-300 dark:ring-gray-700">
                {RUN_LABEL[run.status] ?? run.status.toLowerCase()} · {showRun ? 'hide' : 'show'} on wires
              </button>
            )}
            <span className="truncate text-xs text-gray-600 dark:text-gray-400" aria-live="polite">{status}</span>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
          {!readOnly && (
            <button type="button" disabled={!arranged} onClick={() => { commit(autoAlign(graph)); flash('Lined up top to bottom again.') }}
              title={arranged ? 'Put every step back in a neat top-to-bottom layout' : 'Every step is already lined up'}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-xs font-medium text-gray-800 hover:bg-gray-50 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-white dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200 dark:disabled:hover:bg-gray-900">
              <Sparkles className="h-3.5 w-3.5" />Auto align
            </button>
          )}
          {preview === null
            ? <button type="button" onClick={startPreview} title="Walk the wires from Start without running anything"
                className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-gray-900 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-gray-800 dark:bg-gray-100 dark:text-gray-900 dark:hover:bg-white">
                <Play className="h-3.5 w-3.5" />Preview path
              </button>
            : <button type="button" onClick={stopPreview}
                className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-xs font-medium text-gray-800 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
                <X className="h-3.5 w-3.5" />Clear preview
              </button>}
          </div>
        </div>

        <div role="region" aria-label="Workflow wires" className="absolute inset-0 overflow-auto bg-gray-50 pt-11 dark:bg-gray-950"
          style={{ backgroundImage: 'radial-gradient(rgb(var(--gray-300)) 1px, transparent 1px)', backgroundSize: '18px 18px' }}
          onDragOver={onDragOver} onDrop={onDrop}
          onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as globalThis.Node | null)) setSuggest(null) }}
          onClick={e => { if (e.target === e.currentTarget || e.target === content.current) { setSelected(null); setPending(null) } }}>
          <div ref={content} className="relative mx-auto select-none" style={{ width: layout.width, height: layout.height }}>

            <svg width={layout.width} height={layout.height} className="pointer-events-none absolute inset-0 overflow-visible">
              {[...routes.entries()].map(([k, r]) => {
                const tone = WIRE_TONE(r.edge.branch)
                const sugHot = suggest?.kind === 'wire' && wireKey(suggest.edge) === k
                const hot = sugHot || (quiet && hoverWire === k) || (labelFor && wireKey(labelFor) === k) || (picker && wireKey(picker.edge) === k)
                const hotColor = sugHot ? 'rgb(var(--brand-500))' : HOVER_BLUE
                const skipped = !!view && !view.travelled.has(k) && view.nodes.get(r.edge.from) === 'done'
                const dim = (focusIds && !sugHot) || (view && !view.travelled.has(k))
                return (
                  <g key={k} style={{ opacity: dim ? 0.4 : 1 }}>
                    <path d={r.d} fill="none" stroke={tone.line} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={skipped ? '4 5' : undefined} />
                    <polygon points={ARROW_HEAD(r.end)} fill={tone.head} />
                    {hot && <>
                      <path d={r.d} fill="none" stroke={hotColor} strokeWidth={7} strokeOpacity={0.14} strokeLinecap="round" strokeLinejoin="round" />
                      <path d={r.d} fill="none" stroke={hotColor} strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round" />
                      <path d={r.d} fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" className="jw-current" />
                      <polygon points={ARROW_HEAD(r.end)} fill={hotColor} />
                    </>}
                    {!readOnly && (
                      <path d={r.d} fill="none" stroke="transparent" strokeWidth={18} strokeLinecap="round" className="cursor-pointer"
                        style={{ pointerEvents: quiet ? 'stroke' : 'none' }}
                        onMouseEnter={() => wireOn(k)} onMouseLeave={wireOff} />
                    )}
                  </g>
                )
              })}
              {view && [...routes.entries()].filter(([k]) => view.travelled.has(k)).map(([k, r]) => (
                <g key={`run:${k}`}>
                  <path d={r.d} pathLength={1} fill="none" stroke="rgb(var(--brand-500))" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" className="jw-draw" />
                  {view.live && <path d={r.d} fill="none" stroke="#fff" strokeWidth={1.5} strokeLinecap="round" className="jw-flow" />}
                  <polygon points={ARROW_HEAD(r.end, true)} fill="rgb(var(--brand-500))" className="jw-head" />
                </g>
              ))}
            </svg>

            {graph.nodes.map(n => {
              const box = layout.at.get(n.id)
              if (!box) return null
              const Icon = NODE_ICON[n.type]
              const meta = NODE_META[n.type]
              const agent = n.type === 'AGENT_STEP' ? agents?.find(a => a.id === n.config.agentId) : undefined
              const state = view?.nodes.get(n.id)
              const waiting = !!view && !state
              const target = suggest?.kind === 'step' && suggest.id === n.id
              const isSel = selected === n.id || editing === n.id
              const problems = issuesByNode.get(n.id)
              const canTarget = !!validTargets?.has(n.id)
              const num = numbers.get(n.id)
              const note = typeof n.config.note === 'string' && n.config.note.trim()
              return (
                <button key={n.id} type="button" aria-label={`${meta.label}: ${n.label}`}
                  onClick={() => {
                    if (justMoved.current) { justMoved.current = false; return }
                    if (pending) connectTo(n.id); else setSelected(s => (s === n.id ? null : n.id))
                  }}
                  onPointerDown={e => {
                    justMoved.current = false
                    if (readOnly || pending || preview !== null || e.button !== 0) return
                    grab.current = { id: n.id, sx: e.clientX, sy: e.clientY, ox: box.x, oy: box.y, moved: false }
                    e.currentTarget.setPointerCapture?.(e.pointerId)
                  }}
                  onPointerMove={e => {
                    const g = grab.current
                    if (!g || g.id !== n.id) return
                    const dx = e.clientX - g.sx
                    const dy = e.clientY - g.sy
                    if (!g.moved && Math.hypot(dx, dy) < 4) return
                    g.moved = true
                    setMoving({ id: n.id, x: g.ox + dx, y: g.oy + dy })
                  }}
                  onPointerUp={e => {
                    const g = grab.current
                    grab.current = null
                    if (!g?.moved) return
                    justMoved.current = true
                    setMoving(null)
                    commit(placeStep(graph, g.id, { x: g.ox + e.clientX - g.sx, y: g.oy + e.clientY - g.sy }))
                  }}
                  onPointerCancel={() => { grab.current = null; setMoving(null) }}
                  onDoubleClick={() => openSettings(n.id)}
                  onMouseEnter={() => stepOn(n.id)} onMouseLeave={stepOff}
                  title={problems?.join('\n')}
                  className={`absolute flex items-center gap-3 rounded-2xl border bg-white px-3.5 text-left transition-[box-shadow,opacity,transform,border-color] duration-150 dark:bg-gray-900 ${
                    state ? STEP_RING[state]
                    : target ? 'z-10 scale-[1.045] border-brand-500 shadow-2xl ring-[6px] ring-brand-500/20'
                    : isSel ? 'border-brand-500 ring-4 ring-brand-500/15 shadow-md'
                    : problems ? 'border-red-300 shadow-sm dark:border-red-500/50'
                    : orphans.has(n.id) ? 'border-dashed border-gray-300 shadow-sm dark:border-gray-600'
                    : 'border-gray-200 shadow-sm hover:shadow-md dark:border-gray-700'}`}
                  style={{
                    left: box.x, top: box.y, width: CARD_W, height: CARD_H,
                    opacity: (focusIds && !focusIds.has(n.id)) ? 0.35 : waiting ? 0.45 : pending && !canTarget && pending.from !== n.id ? 0.45 : 1,
                    cursor: pending ? (canTarget ? 'pointer' : 'not-allowed') : moving?.id === n.id ? 'grabbing' : readOnly ? 'pointer' : 'grab',
                    zIndex: moving?.id === n.id ? 20 : undefined,
                    boxShadow: moving?.id === n.id ? '0 18px 40px rgb(0 0 0 / 0.18)' : undefined,
                    touchAction: 'none',
                  }}>
                  <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${meta.tone}`}><Icon className="h-[18px] w-[18px]" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-[10.5px] font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">{meta.label}</span>
                    <span className="block truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{n.label}</span>
                    <span className="block truncate font-mono text-[11px] text-gray-500 dark:text-gray-400">{summarise(n, agent) || meta.desc}</span>
                  </span>
                  {state
                    ? <span className={`absolute -top-2.5 right-3 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold shadow ${STEP_BADGE[state].tone}`}>
                        {state === 'done' && <Check className="h-3 w-3" strokeWidth={3} />}
                        {state === 'running' && <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />}
                        {STEP_BADGE[state].text}
                      </span>
                    : <span className="absolute right-3 top-2 flex items-center gap-1.5 font-mono text-[11px] text-gray-400">
                        {problems && <span className="h-2 w-2 rounded-full bg-red-500" aria-label="Needs fixing" />}
                        {note && <span title={note}><Pencil className="h-3 w-3" aria-label="Has a note" /></span>}
                        {num ? String(num).padStart(2, '0') : '—'}
                      </span>}
                  {pending && canTarget && <span className="jw-pulse absolute -top-1.5 left-1/2 h-3 w-3 -translate-x-1/2 rounded-full border-2 border-brand-500 bg-brand-100" />}
                </button>
              )
            })}

            {/* Exit dots: click one, then a step, to wire it by hand. */}
            {graph.nodes.flatMap(n => {
              const box = layout.at.get(n.id)
              if (!box) return []
              const exits = exitsOf(n)
              return exits.map(b => {
                const [x, y] = exitPoint(exits, box, b)
                const tone = WIRE_TONE(b)
                const picking = pending?.from === n.id && pending.branch === b
                const name = exitLabel(b, n)
                return (
                  <div key={`${n.id}:${b ?? 'out'}`} className="absolute z-[5]" style={{ left: x, top: y }}>
                    <button type="button" disabled={readOnly} aria-label={`${name ? `"${name}" exit` : 'Exit'} of ${n.label}. Click, then click the step that comes next.`}
                      onClick={() => { setPending(picking ? null : { from: n.id, branch: b }); setSelected(null) }}
                      className={`absolute -left-1.5 -top-1.5 h-3 w-3 rounded-full border-[1.5px] bg-white transition-transform hover:scale-150 disabled:hover:scale-100 dark:bg-gray-900 ${picking ? 'jw-pulse border-brand-500 bg-brand-500' : ''}`}
                      style={picking ? undefined : { borderColor: tone.head }} />
                    {name && <span className={`pointer-events-none absolute left-2 top-1.5 max-w-[72px] truncate text-[10px] font-bold uppercase tracking-wide ${tone.text}`}>{name}</span>}
                  </div>
                )
              })
            })}

            {hoveredRoute && (
              <div className="absolute z-30 flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 animate-fade-in" style={{ left: hoveredRoute.mid[0], top: hoveredRoute.mid[1] }}
                onMouseEnter={() => wireOn(hoverWire!)} onMouseLeave={wireOff}>
                <div className="flex items-center gap-0.5 rounded-lg border-[1.5px] border-dashed bg-white p-0.5 shadow-md dark:bg-gray-900" style={{ borderColor: HOVER_BLUE }}>
                  <BarButton label="Delete wire" onClick={() => { commit(setNext(graph, hoveredRoute.edge.from, hoveredRoute.edge.branch, null)); setHoverWire(null); flash('Wire deleted.') }}>
                    <Scissors className="h-4 w-4" />
                  </BarButton>
                  <Divider />
                  <BarButton label="Add step" tone="blue" onClick={e => { setPicker({ edge: hoveredRoute.edge, at: { x: e.clientX + 12, y: e.clientY - 12 } }); setHoverWire(null) }}>
                    <Plus className="h-4 w-4" strokeWidth={2.5} />
                  </BarButton>
                  <Divider />
                  <BarButton label={wireLabel(graph, hoveredRoute.edge) ? 'Edit label' : 'Add label'}
                    onClick={() => { setLabelFor(hoveredRoute.edge); setLabelDraft(wireLabel(graph, hoveredRoute.edge)); setHoverWire(null) }}>
                    <Tag className="h-3.5 w-3.5" />
                  </BarButton>
                </div>
                {wireLabel(graph, hoveredRoute.edge) && (
                  <span className={`whitespace-nowrap rounded-full border border-gray-200 bg-white px-2 py-0.5 text-[11px] font-semibold dark:border-gray-700 dark:bg-gray-900 ${WIRE_TONE(hoveredRoute.edge.branch).text}`}>
                    {wireLabel(graph, hoveredRoute.edge)}
                  </span>
                )}
              </div>
            )}

            {labelFor && labelRoute && (
              <div role="dialog" aria-label="Wire label" className="absolute z-40 flex w-64 flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900"
                style={{ left: Math.min(labelRoute.mid[0] + 20, layout.width - 270), top: Math.max(4, labelRoute.mid[1] - 30) }}>
                <label className="flex flex-col gap-1 text-xs font-medium text-gray-700 dark:text-gray-300">Wire label
                  <input autoFocus value={labelDraft} maxLength={40} placeholder="e.g. seats picked"
                    onChange={e => setLabelDraft(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); saveLabel() } }}
                    className="rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-sm font-normal text-gray-900 outline-none focus:border-brand-500 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100" />
                </label>
                <div className="flex items-center justify-between">
                  <span className="text-[11px] text-gray-500">Shows when you hover the wire.</span>
                  <span className="flex gap-1">
                    <button type="button" onClick={() => setLabelFor(null)} className="rounded-md px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800">Cancel</button>
                    <button type="button" onClick={saveLabel} className="rounded-md bg-gray-900 px-2.5 py-1 text-xs font-medium text-white dark:bg-gray-100 dark:text-gray-900">Save</button>
                  </span>
                </div>
              </div>
            )}

            {hoveredStep && hoveredBox && (
              <div className="absolute z-30 flex items-center gap-0.5 rounded-lg border border-gray-200 bg-white p-0.5 shadow-md animate-fade-in dark:border-gray-700 dark:bg-gray-900"
                style={{ left: hoveredBox.x + CARD_W, top: hoveredBox.y - 40, transform: 'translateX(-100%)' }}
                onMouseEnter={() => stepOn(hoveredStep.id)} onMouseLeave={stepOff}>
                <BarButton label="Edit step" active onClick={() => openSettings(hoveredStep.id)}><Pencil className="h-4 w-4" /></BarButton>
                {hoveredStep.type !== 'TRIGGER' && <>
                  <Divider />
                  <BarButton label="Clone" onClick={() => cloneStep(hoveredStep.id)}><Copy className="h-4 w-4" /></BarButton>
                  <Divider />
                  <BarButton label="Delete" tone="red" onClick={() => removeStep(hoveredStep.id)}><Trash2 className="h-4 w-4" /></BarButton>
                </>}
              </div>
            )}

            {dragging && suggest?.kind === 'step' && layout.at.get(suggest.id) && (
              <div className="pointer-events-none absolute z-30 flex -translate-x-1/2 -translate-y-full items-center gap-1.5 whitespace-nowrap rounded-full bg-brand-500 px-3 py-1.5 text-xs font-semibold text-white shadow-lg"
                style={{ left: layout.at.get(suggest.id)!.x + CARD_W / 2, top: layout.at.get(suggest.id)!.y - 10 }}>
                <ArrowDown className="h-3.5 w-3.5" />Drop to wire it in after this step
              </div>
            )}
            {dragging && suggest?.kind === 'wire' && routes.get(wireKey(suggest.edge)) && (
              <div className="pointer-events-none absolute z-30 flex -translate-x-1/2 -translate-y-1/2 items-center gap-1.5 whitespace-nowrap rounded-full bg-brand-500 px-3 py-1.5 text-xs font-semibold text-white shadow-lg"
                style={{ left: routes.get(wireKey(suggest.edge))!.mid[0], top: routes.get(wireKey(suggest.edge))!.mid[1] }}>
                <Plus className="h-3.5 w-3.5" />Drop to put it on this wire
              </div>
            )}
          </div>
        </div>

        {graph.nodes.length === 1 && !readOnly && (
          <p className="pointer-events-none absolute left-1/2 top-24 -translate-x-1/2 rounded-lg bg-white/90 px-3 py-2 text-xs text-gray-600 shadow-sm dark:bg-gray-900/90 dark:text-gray-300">
            Drag a step from the left onto Start.
          </p>
        )}

        {editNode && (
          <CanvasInspector key={editNode.id} node={editNode} graph={graph} agents={agents} tools={tools} mcpServers={mcpServers} functions={functions} workflows={workflows}
            readOnly={readOnly || !!lockedGroupOf(graph, editNode.id)}
            lockedIn={readOnly ? undefined : lockedGroupOf(graph, editNode.id)?.title}
            onUnlock={() => { const g = lockedGroupOf(graph, editNode.id); if (g) commit(updateGroup(graph, g.id, { locked: false })) }}
            issues={[]} step={realRun?.steps.get(editNode.id)} runSteps={realRun?.steps} onDirty={onDirty}
            onSave={edited => { unsaved.current = false; commit(keepWiresSettings(applyStepEdit(graph, edited, editNode.id), graph, editNode.id)); setEditing(null) }}
            onClose={() => { unsaved.current = false; setEditing(null) }} />
        )}
      </div>

      {picker && (
        <StepPicker at={picker.at} title="Add a step on this wire" agents={agents} allowEnd={false} onClose={() => setPicker(null)}
          onPick={(entry, agent) => {
            const r = insertOnEdge(graph, picker.edge, entry)
            commit(agent ? assignAgent(r.graph, r.id, agent) : r.graph)
            setSelected(r.id)
            setPicker(null)
          }} />
      )}
    </div>
  )
}

function BarButton({ label, onClick, tone, active, children }: {
  label: string; onClick: (e: React.MouseEvent) => void; tone?: 'blue' | 'red'; active?: boolean; children: React.ReactNode
}) {
  return (
    <button type="button" aria-label={label} data-tip={label} onClick={onClick}
      className={`jw-tip relative grid h-8 w-8 place-items-center rounded-md transition-colors ${active
        ? 'bg-blue-50 text-blue-600 hover:bg-blue-100 dark:bg-blue-500/15 dark:text-blue-300'
        : tone === 'blue' ? 'text-blue-600 hover:bg-gray-100 dark:text-blue-300 dark:hover:bg-gray-800'
        : tone === 'red' ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-500/10'
        : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
      {children}
    </button>
  )
}

const Divider = () => <span className="h-4 w-px bg-gray-200 dark:bg-gray-700" />

const WIRES_CSS = `
.jizza-wires .jw-current{stroke-dasharray:1 9;animation:jw-flow 0.42s linear infinite}
.jizza-wires .jw-flow{stroke-dasharray:2 12;animation:jw-flow2 0.8s linear infinite}
@keyframes jw-flow{to{stroke-dashoffset:-10}}
@keyframes jw-flow2{to{stroke-dashoffset:-14}}
.jizza-wires .jw-draw{stroke-dasharray:1;stroke-dashoffset:1;animation:jw-draw 0.7s ease-out forwards}
@keyframes jw-draw{to{stroke-dashoffset:0}}
.jizza-wires .jw-head{opacity:0;animation:jw-fade 0.2s 0.55s forwards}
@keyframes jw-fade{to{opacity:1}}
.jizza-wires .jw-running{animation:jw-ring 1.1s ease-in-out infinite}
@keyframes jw-ring{0%,100%{box-shadow:0 0 0 0 rgb(var(--brand-500) / .45)}50%{box-shadow:0 0 0 10px rgb(var(--brand-500) / 0)}}
.jizza-wires .jw-pulse{animation:jw-pulse 1s ease-in-out infinite}
@keyframes jw-pulse{0%,100%{box-shadow:0 0 0 0 rgb(var(--brand-500) / .55)}50%{box-shadow:0 0 0 6px rgb(var(--brand-500) / 0)}}
.jizza-wires .jw-tip:hover::after{content:attr(data-tip);position:absolute;bottom:calc(100% + 8px);left:50%;transform:translateX(-50%);
  background:rgb(var(--gray-900));color:#fff;font-size:12px;font-weight:600;padding:5px 9px;border-radius:7px;white-space:nowrap;pointer-events:none;z-index:50}
@media (prefers-reduced-motion: reduce){.jizza-wires .jw-current,.jizza-wires .jw-flow,.jizza-wires .jw-running,.jizza-wires .jw-pulse{animation:none}
  .jizza-wires .jw-draw{animation:none;stroke-dashoffset:0}.jizza-wires .jw-head{animation:none;opacity:1}}
`
