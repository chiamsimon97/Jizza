import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { createPortal } from 'react-dom'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle, ArrowDown, Cable, Check, ChevronDown, ChevronRight, Copy, Globe, Group, Keyboard, Lock, LockOpen, Maximize2, Minimize2, Pencil, Play, Plus, Redo2, Scissors, Search,
  Sparkles, Tag, Trash2, Undo2, Ungroup, X,
} from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { mcpApi } from '@/api/mcp'
import { functionsApi, type ScriptFunction } from '@/api/functions'
import { api } from '@/lib/api'
import type { AgentDto, McpServer, RunDto, WorkflowDto } from '@/types'
import {
  NODE_META, PALETTE_GROUPS, addAfter, addNode, applyStepEdit, branchByButton, buttonPathsAfter, cloneNodes, descendantsOf, exitsOf, groupButtonsOf, insertChain, mainExitsOf, freeSpot,
  insertOnEdge, issues as findIssues, newNode, reaches, setNext, unreachable, updateNode, type CanvasEdge, type CanvasGraph, type CanvasNode, type ExitKey, type NodeType, type PaletteEntry,
} from './canvasModel'
import { ContextMenu, NODE_ICON, StepPicker, exitLabel, summarise, type MenuAt, type MenuItem, type StepRun } from './canvasParts'
import { CanvasInspector, type WorkflowOption } from './CanvasInspector'
import { RUN_LABEL, groupRun, loadTools, runState } from './runState'
import { useHistory } from './history'
import { RecordFlow } from '@/components/web/RecordFlow'
import { canConfigureWithJizza } from '@/jizza/commands'
import { createGroup, foldedGraph, groupOfNode, groupsOf, isLockedEdge, lockedChange, lockedGroupOf, removeFromGroup, ungroup, updateGroup, type StepGroup } from './groupModel'
import {
  CARD_H, CARD_W, autoAlign, deleteStep, distanceTo, dropOnStep, exitPoint, inPoint, isArranged, keepWiresSettings, placeStep, previewPath, rewire, rewireTargets, route,
  setWireLabel, stepNumbers, wireKey, wireLabel, wiresLayout, type Box, type Point, type Route,
} from './wiresModel'

interface Props {
  graph: CanvasGraph
  onChange: (graph: CanvasGraph) => void
  readOnly?: boolean
  /** A run to show: arrows from Start along the steps it went through. */
  run?: RunDto | null
  /** A step to open the settings of, such as the one a fix or a failed run points at. */
  focusNode?: string | null
  /** A short board for showing a workflow inside a card, without the step list or full screen. */
  compact?: boolean
}

/** What was copied, kept across workflows so steps can be pasted into another one. */
let clipboard: { graph: CanvasGraph; ids: string[] } | null = null

/** Where a step being dragged in would go. */
type Suggestion = { kind: 'step'; id: string } | { kind: 'wire'; edge: CanvasEdge }

/**
 * A wire being dragged: out of an exit dot ('exit'), or by the end of a wire already there ('end').
 * {@code old} is the wire the exit had, which the new one replaces.
 */
interface WireDrag { kind: 'exit' | 'end'; from: string; branch: ExitKey; old: CanvasEdge | null; start: Point; at: Point; over: string | null }

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
const GROUP_RUN_TEXT: Record<StepRun, string> = {
  done: 'Done in the last run', failed: 'A step failed in the last run', skipped: 'Skipped in the last run',
  running: 'Running now', waiting: 'Waiting for you',
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
 * The workflow builder: the workflow's steps laid out top to bottom with circuit-style wires. Drag
 * a step onto a step or a wire and it wires itself in; drag from an exit dot to a step to wire it,
 * or drag a wire's end to another step to re-wire it (let go on empty space to take it off); hover
 * a wire to delete, re-wire, label it or put a step on it; hover a step to edit, clone or delete it.
 */
export function WiresBoard({ graph, onChange, readOnly = false, run, focusNode, compact = false }: Props) {
  const history = useHistory(graph, onChange)
  const [fullscreen, setFullscreen] = useState(false)
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
  /** The step picker: for a step on a wire, or a step after an exit (a wire let go on empty space). */
  const [picker, setPicker] = useState<{ edge?: CanvasEdge; after?: { from: string; branch: ExitKey }; at: { x: number; y: number } } | null>(null)
  const [wireDrag, setWireDrag] = useState<WireDrag | null>(null)
  const wireGrab = useRef<{ drag: WireDrag; sx: number; sy: number; moved: boolean } | null>(null)
  /** A click that ends a wire drag is not a click on the exit dot. */
  const justWired = useRef(false)
  /** An exit clicked to wire it by hand; the next step clicked is where it goes. */
  const [pending, setPending] = useState<{ from: string; branch: ExitKey } | null>(null)
  const dragged = useRef<NodeType | PaletteEntry | null>(null)
  const [suggest, setSuggest] = useState<Suggestion | null>(null)
  const [dragging, setDragging] = useState(false)
  /** Steps being moved by hand (one, or a group's): where each is under the pointer. */
  const [moving, setMoving] = useState<Map<string, Box> | null>(null)
  const grab = useRef<{ key: string; bases: Map<string, Box>; sx: number; sy: number; moved: boolean } | null>(null)
  /** Steps Shift+clicked to group them. */
  const [picked, setPicked] = useState<Set<string>>(() => new Set())
  /** A box being dragged on empty space to pick the steps it touches, in board coordinates. */
  const [marquee, setMarquee] = useState<{ x1: number; y1: number; x2: number; y2: number; add: boolean } | null>(null)
  const marqueeDone = useRef(false)
  /** The group whose title should open for typing, asked for at this moment. */
  const [renaming, setRenaming] = useState<{ id: string; at: number } | null>(null)
  /** The right-click menu that is open, if any. */
  const [menu, setMenu] = useState<{ at: MenuAt; items: MenuItem[] } | null>(null)
  /** On a read-only board folding a group is only for the viewer. */
  const [viewFolds, setViewFolds] = useState<Map<string, boolean>>(() => new Map())
  const justMoved = useRef(false)
  const [toast, setToast] = useState('')
  const toastTimer = useRef<number>()
  const [preview, setPreview] = useState<number | null>(null)
  const previewTimer = useRef<number>()
  const [showRun, setShowRun] = useState(true)
  const [paletteQuery, setPaletteQuery] = useState('')
  const [folded, setFolded] = useState<Set<string>>(() => new Set(['Web Automation', 'Data', 'Flow control']))
  /** Whether the open step settings start at the note, or with Jizza on them. */
  const [focusNote, setFocusNote] = useState(false)
  const [askJizza, setAskJizza] = useState(false)
  /** Where recorded steps go once the person has reviewed them. */
  const [recordAt, setRecordAt] = useState<{ from: string | null; branch: ExitKey } | null>(null)
  const [help, setHelp] = useState(false)
  /** The pointer was last in this board, so its keyboard shortcuts apply. */
  const active = useRef(false)
  const pastes = useRef(0)

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

  /**
   * Every edit goes through here, as its own undo step. A change to what is inside a locked group
   * is refused with a note saying why. Any preview stops, as it no longer matches the flow.
   */
  const commit = useCallback((next: CanvasGraph): boolean => {
    const why = lockedChange(graph, next)
    if (why) { flash(why); return false }
    window.clearInterval(previewTimer.current)
    setPreview(null)
    history.commit(next)
    return true
  }, [history, graph, flash])

  const mayLeave = useCallback(() => {
    if (!unsaved.current) return true
    if (!window.confirm('Leave this step without saving your changes?')) return false
    unsaved.current = false
    return true
  }, [])
  const openSettings = useCallback((id: string | null, how?: { note?: boolean; jizza?: boolean }) => {
    if (id !== editing && !mayLeave()) return
    setFocusNote(!!how?.note)
    setAskJizza(!!how?.jizza)
    setEditing(id)
    if (id) setSelected(id)
  }, [editing, mayLeave])

  // ---- Layout and wires -------------------------------------------------------------------

  // While a step is being moved, everything (its wires too) is laid out with it where the pointer is.
  const shown = useMemo(() => (moving ? [...moving].reduce((g, [id, at]) => placeStep(g, id, at), graph) : graph), [graph, moving])
  const groups = groupsOf(graph)
  const isFolded = useCallback((id: string) => {
    if (readOnly && viewFolds.has(id)) return viewFolds.get(id)!
    return !!groupsOf(graph).find(g => g.id === id)?.collapsed
  }, [graph, readOnly, viewFolds])
  // A folded group is laid out as one step standing in for its steps.
  const foldView = useMemo(() => foldedGraph(shown, isFolded), [shown, isFolded])
  const into = foldView.into
  const layout = useMemo(() => wiresLayout(foldView.graph), [foldView])
  const arranged = useMemo(() => isArranged(graph), [graph])
  const byId = useMemo(() => new Map(graph.nodes.map(n => [n.id, n])), [graph])
  const routes = useMemo(() => {
    const map = new Map<string, Route & { edge: CanvasEdge; end: [number, number]; fixed: boolean }>()
    for (const e of graph.edges) {
      const from = byId.get(e.from)
      const f = into.get(e.from) ?? e.from
      const t = into.get(e.to) ?? e.to
      if (f === t) continue
      const a = layout.at.get(f)
      const b = layout.at.get(t)
      if (!from || !a || !b) continue
      const end = inPoint(b)
      const nextRow = (layout.depth.get(t) ?? 0) === (layout.depth.get(f) ?? 0) + 1
      const start: [number, number] = into.has(e.from) ? [a.x + CARD_W / 2, a.y + CARD_H] : exitPoint(exitsOf(from), a, e.branch)
      // Wires inside a locked group, and wires meeting a folded one, can't be changed from here.
      const fixed = isLockedEdge(graph, e) || into.has(e.from) || into.has(e.to)
      map.set(wireKey(e), { ...route(start, end, nextRow), edge: e, end, fixed })
    }
    return map
  }, [graph, byId, layout, into])

  const numbers = useMemo(() => stepNumbers(graph), [graph])
  const issueList = useMemo(() => findIssues(graph), [graph])
  // Problems with the agent a step points at, which its settings can't show by themselves.
  const agentIssues = useMemo(() => {
    const map = new Map<string, string[]>()
    if (agents) {
      for (const n of graph.nodes) {
        const id = n.type === 'AGENT_STEP' ? n.config.agentId : undefined
        if (typeof id !== 'string' || !id) continue
        const a = agents.find(x => x.id === id)
        const problem = !a ? 'The agent for this step no longer exists. Choose another.'
          : a.status === 'archived' ? `${a.name} is archived. Restore it or choose another agent.`
          : a.status === 'draft' ? `${a.name} is still a draft. Activate it before this runs.`
          : !a.enabled ? `${a.name} is paused, so this step will fail until it is resumed.`
          : null
        if (problem) map.set(n.id, [problem])
      }
    }
    return map
  }, [agents, graph.nodes])
  const issuesByNode = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const i of issueList) if (i.nodeId) map.set(i.nodeId, [...(map.get(i.nodeId) ?? []), i.message])
    for (const [id, list] of agentIssues) map.set(id, [...(map.get(id) ?? []), ...list])
    return map
  }, [issueList, agentIssues])
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

  const quiet = !readOnly && !dragging && !moving && !marquee && !wireDrag && preview === null && !pending && !labelFor && !picker
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

  const addFromPalette = (entry: NodeType | PaletteEntry, agent?: AgentDto) => {
    const withAgent = (r: { graph: CanvasGraph; id: string }) => (agent ? assignAgent(r.graph, r.id, agent) : r.graph)
    const r = selected ? dropOnStep(graph, selected, entry) : null
    if (r) { if (commit(withAgent(r))) { setSelected(r.id); flash(`Wired in after “${byId.get(selected!)?.label}”.`) } return }
    // Without a selected step it goes after the last step with a free exit.
    const last = [...graph.nodes].reverse().find(n => mainExitsOf(n).some(b => !graph.edges.some(e => e.from === n.id && e.branch === b)))
    const at = looseSpot()
    const added = addNode(graph, entry, at.x, at.y, last?.id ?? null)
    if (commit(withAgent(added))) setSelected(added.id)
  }

  // ---- Wiring by hand -----------------------------------------------------------------------

  const wiredAt = useCallback((from: string, branch: ExitKey) => graph.edges.find(e => e.from === from && e.branch === branch) ?? null, [graph])
  const validTargets = useMemo(() => {
    if (wireDrag) return rewireTargets(graph, wireDrag.from, wireDrag.branch, wireDrag.old)
    return pending ? rewireTargets(graph, pending.from, pending.branch, wiredAt(pending.from, pending.branch)) : null
  }, [graph, pending, wireDrag, wiredAt])
  const connectTo = (id: string) => {
    if (!pending || !validTargets?.has(id)) return
    commit(setNext(graph, pending.from, pending.branch, id))
    setPending(null)
    flash('Wired.')
  }

  /** Why a wire can't go to a step, said when it is let go there. */
  const whyNot = (d: WireDrag, id: string): string => {
    const node = byId.get(id)
    if (!node || id === d.from) return ''
    if (node.type === 'TRIGGER') return 'Nothing can be wired into Start.'
    if (lockedGroupOf(graph, d.from) && (isLockedEdge(graph, { from: d.from, to: id }) || (d.old && isLockedEdge(graph, d.old)))) {
      return 'That wire is inside a locked group. Unlock the group to change it.'
    }
    if (reaches(graph, id, d.from)) return 'That would make a loop, which a workflow can’t run.'
    return 'That step can’t be wired here.'
  }

  /** The step under a point while a wire is dragged, with a little room around each card. */
  const stepAt = (x: number, y: number): string | null => {
    for (const n of graph.nodes) {
      const b = layout.at.get(n.id)
      if (b && x >= b.x - 12 && x <= b.x + CARD_W + 12 && y >= b.y - 12 && y <= b.y + CARD_H + 12) return n.id
    }
    return null
  }

  /** Starts dragging a wire; moves and the let-go are followed on the window, so they keep going if the hover toolbar goes. */
  const startWire = (drag: Omit<WireDrag, 'at' | 'over'>, e: React.PointerEvent) => {
    if (readOnly || preview !== null || e.button !== 0) return
    e.stopPropagation()
    wireGrab.current = { drag: { ...drag, at: drag.start, over: null }, sx: e.clientX, sy: e.clientY, moved: false }
    const onMove = (ev: PointerEvent) => {
      const g = wireGrab.current
      if (!g) return
      if (!g.moved && Math.hypot(ev.clientX - g.sx, ev.clientY - g.sy) < 4) return
      g.moved = true
      const p = boardPoint(ev)
      if (!p) return
      g.drag = { ...g.drag, at: [p.x, p.y], over: stepAt(p.x, p.y) }
      setWireDrag(g.drag)
      setHoverWire(null)
      setPending(null)
    }
    const onUp = (ev: PointerEvent) => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      const g = wireGrab.current
      wireGrab.current = null
      setWireDrag(null)
      if (!g?.moved) return
      justWired.current = true
      window.setTimeout(() => { justWired.current = false }, 0)
      const p = boardPoint(ev)
      finishWire(g.drag, p ? stepAt(p.x, p.y) : null, { x: ev.clientX, y: ev.clientY })
    }
    const onCancel = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      wireGrab.current = null
      setWireDrag(null)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onCancel)
  }

  /** Where a dragged wire was let go: on a step it is wired there; on empty space its end comes off, or a new step is offered. */
  const finishWire = (d: WireDrag, over: string | null, at: { x: number; y: number }) => {
    const fromLabel = byId.get(d.from)?.label
    if (over) {
      if (over === d.from || (d.old && d.old.to === over)) return
      if (!rewireTargets(graph, d.from, d.branch, d.old).has(over)) { flash(whyNot(d, over)); return }
      commit(rewire(graph, d.from, d.branch, over, d.old))
      flash(d.old ? `Re-wired: “${fromLabel}” now goes to “${byId.get(over)?.label}”.` : `Wired “${fromLabel}” to “${byId.get(over)?.label}”.`)
      return
    }
    if (d.kind === 'end' && d.old) {
      if (isLockedEdge(graph, d.old)) return
      commit(rewire(graph, d.from, d.branch, null, d.old))
      flash('Wire taken off.')
      return
    }
    // A wire from an exit let go on empty space asks what comes next, as on the canvas.
    setPicker(d.old ? { edge: d.old, at: { x: at.x + 12, y: at.y - 12 } } : { after: { from: d.from, branch: d.branch }, at: { x: at.x + 12, y: at.y - 12 } })
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
    const r = deleteStep(graph, id)
    if (!commit(r.graph)) return
    if (editing === id) { unsaved.current = false; setEditing(null) }
    setSelected(null)
    setHoverStep(null)
    flash(r.joined ? 'Deleted. The steps either side are still joined.' : 'Deleted.')
  }
  /** Deletes several steps in one undo step; Start and steps in a locked group stay. */
  const removeSteps = (ids: string[]) => {
    const removable = ids.filter(id => byId.get(id) && byId.get(id)!.type !== 'TRIGGER')
    const doomed = removable.filter(id => !lockedGroupOf(graph, id))
    if (!doomed.length) { if (removable.length) flash('Steps in a locked group were kept. Unlock the group to delete them.'); return }
    if (doomed.length === 1 && doomed.length === removable.length) { removeStep(doomed[0]); return }
    const next = doomed.reduce((g, id) => deleteStep(g, id).graph, graph)
    if (!commit(next)) return
    if (editing && doomed.includes(editing)) { unsaved.current = false; setEditing(null) }
    setSelected(null)
    setPicked(new Set())
    flash(doomed.length < removable.length
      ? `Deleted ${doomed.length} steps. Steps in a locked group were kept.`
      : `Deleted ${doomed.length} steps.`)
  }
  /** The picked steps, else the selected one. */
  const chosen = () => (picked.size ? [...picked] : selected ? [selected] : [])
  const copySteps = (ids = chosen()) => {
    const steps = ids.filter(id => byId.get(id)?.type !== 'TRIGGER')
    if (!steps.length) return
    clipboard = { graph, ids: steps }
    pastes.current = 0
    flash(`Copied ${steps.length} ${steps.length === 1 ? 'step' : 'steps'}. Paste with ${MOD}+V, here or in another workflow.`)
  }
  const pasteSteps = () => {
    if (readOnly || !clipboard) return
    pastes.current += 1
    const r = cloneNodes(graph, clipboard.ids, clipboard.graph, 60 * pastes.current)
    if (!r.ids.length || !commit(r.graph)) return
    setPicked(new Set(r.ids))
    setSelected(null)
    flash('Pasted. Click an exit dot, then a pasted step, to wire them in.')
  }
  const duplicateSteps = (ids = chosen()) => {
    const steps = ids.filter(id => byId.get(id)?.type !== 'TRIGGER')
    if (readOnly || !steps.length) return
    if (steps.length === 1) { cloneStep(steps[0]); return }
    const r = cloneNodes(graph, steps, graph, 60)
    if (!r.ids.length || !commit(r.graph)) return
    setPicked(new Set(r.ids))
    setSelected(null)
    flash(`Cloned ${r.ids.length} steps.`)
  }
  /** Takes every wire in and out of the steps off, leaving the steps where they are. */
  const disconnect = (ids: string[]) => {
    if (commit({ ...graph, edges: graph.edges.filter(e => !ids.includes(e.from) && !ids.includes(e.to)) })) flash('Disconnected.')
  }
  /** Adds a Paths step after a step that clicks one of several buttons: one path per button. */
  const branchOnButton = (id: string) => {
    const made = branchByButton(graph, id)
    if (made && commit(made.graph)) { setSelected(made.id); flash('Added a path for each button.') }
  }
  // ---- Step groups -------------------------------------------------------------------------

  const groupSteps = (ids: string[]) => {
    const made = createGroup(graph, ids)
    if (!made) { flash('Shift+click the steps to group first (not Start, and not steps in a locked group).'); return }
    commit(made.graph)
    setPicked(new Set())
    setRenaming({ id: made.id, at: Date.now() })
    flash('Grouped. Type a title and press Enter; click the padlock to lock it.')
  }
  const toggleFold = (id: string) => {
    if (readOnly) { setViewFolds(prev => new Map(prev).set(id, !isFolded(id))); return }
    commit(updateGroup(graph, id, { collapsed: !isFolded(id) }))
  }
  const toGroup = picked.size ? [...picked] : selected ? [selected] : []
  const canGroup = !readOnly && toGroup.some(id => byId.get(id)?.type !== 'TRIGGER' && !lockedGroupOf(graph, id))

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t.closest?.('input, textarea, select, [contenteditable="true"], [role="dialog"]')) return
      const mod = e.metaKey || e.ctrlKey
      const key = e.key.toLowerCase()
      // The rest of the page's shortcuts are left alone unless the pointer was last in the board.
      const here = active.current || fullscreen
      if (e.key === 'Escape') {
        if (menu || help || picker) { setMenu(null); setHelp(false); setPicker(null) }
        else if (pending || labelFor) return
        else if (picked.size) setPicked(new Set())
        else if (fullscreen) setFullscreen(false)
        else return
        // Handled here, before the slide-over around the board takes Escape as "close".
        e.preventDefault()
        e.stopPropagation()
        return
      }
      if (here && mod && key === 'c') { copySteps(); return }
      if (readOnly) return
      if (mod && !e.shiftKey && key === 'g' && toGroup.length) { e.preventDefault(); groupSteps(toGroup) }
      else if (mod && key === 'a' && (here || content.current?.closest('.jizza-wires')?.matches(':hover'))) { e.preventDefault(); pickAll() }
      else if (key === '/' && !mod) { e.preventDefault(); focusSearch() }
      else if (!here) return
      else if (mod && key === 'z' && !e.shiftKey) { e.preventDefault(); history.undo() }
      else if (mod && (key === 'y' || (key === 'z' && e.shiftKey))) { e.preventDefault(); history.redo() }
      else if (mod && key === 'v') { e.preventDefault(); pasteSteps() }
      else if (mod && key === 'd') { e.preventDefault(); duplicateSteps() }
      else if ((e.key === 'Delete' || e.key === 'Backspace') && chosen().length) { e.preventDefault(); removeSteps(chosen()) }
      else if (e.key === 'Tab' && !mod && !e.shiftKey) { e.preventDefault(); openPickerCentered() }
    }
    // Capture, so Escape is claimed before the slide-over around the board sees it.
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  // ---- Right-click menus --------------------------------------------------------------------

  const goToStart = () => {
    const box = startId ? layout.at.get(startId) : undefined
    const region = content.current?.parentElement
    if (!box || !region || !content.current) return
    region.scrollTo({ left: content.current.offsetLeft + box.x + CARD_W / 2 - region.clientWidth / 2, top: Math.max(0, box.y - 60), behavior: 'smooth' })
    setSelected(startId ?? null)
  }
  const focusSearch = () => {
    const input = content.current?.closest('.jizza-wires')?.querySelector<HTMLInputElement>('input[aria-label="Search steps"]')
    input?.focus()
    input?.select()
  }
  const openPickerCentered = () => {
    const rect = content.current?.closest('.jizza-wires')?.getBoundingClientRect()
    setPicker({ at: rect ? { x: rect.left + rect.width / 2 - 128, y: rect.top + 80 } : { x: 200, y: 200 } })
  }
  /** Picks a step and every step after it, such as one side of a choice. */
  const pickFrom = (id: string) => { setPicked(new Set([id, ...descendantsOf(graph, id)].filter(x => byId.get(x)?.type !== 'TRIGGER'))); setSelected(null) }
  const pickAll = () => { setPicked(new Set(graph.nodes.filter(n => n.type !== 'TRIGGER' && !into.has(n.id)).map(n => n.id))); setSelected(null) }

  /** The menu on empty space: getting around, arranging, and the picked steps. */
  const boardMenu = (e: React.MouseEvent) => {
    e.preventDefault()
    const view: MenuItem[] = [
      { label: 'Go to Start', onSelect: goToStart },
      { label: preview === null ? 'Preview path' : 'Clear preview', divider: true, onSelect: preview === null ? startPreview : stopPreview },
    ]
    const at = { x: e.clientX, y: e.clientY }
    setMenu({
      at: { ...at, flipX: e.clientX },
      items: readOnly ? view : [
        ...view,
        { label: 'Auto align', disabled: !arranged, onSelect: () => { commit(autoAlign(graph)); flash('Lined up top to bottom again.') } },
        { label: 'Undo', shortcut: `${MOD}+Z`, divider: true, disabled: !history.canUndo, onSelect: history.undo },
        { label: 'Redo', shortcut: `${MOD}+Shift+Z`, disabled: !history.canRedo, onSelect: history.redo },
        { label: toGroup.length > 1 ? `Group ${toGroup.length} steps` : 'Group steps', shortcut: `${MOD}+G`, divider: true, disabled: !canGroup, onSelect: () => groupSteps(toGroup) },
        { label: 'Pick all steps', shortcut: `${MOD}+A`, onSelect: pickAll },
        { label: 'Clear picked steps', shortcut: 'Esc', disabled: !picked.size && !selected, onSelect: () => { setPicked(new Set()); setSelected(null) } },
        { label: 'Paste', shortcut: `${MOD}+V`, disabled: !clipboard, onSelect: pasteSteps },
        { label: 'Add a step', shortcut: 'Tab', divider: true, onSelect: () => setPicker({ at }) },
        { label: 'Search steps', shortcut: '/', onSelect: focusSearch },
      ],
    })
  }

  /** The menu on a step: its settings, copies, its group. */
  const stepMenu = (e: React.MouseEvent, id: string) => {
    e.preventDefault()
    e.stopPropagation()
    const node = byId.get(id)
    if (!node) return
    const isStart = node.type === 'TRIGGER'
    const group = groupOfNode(graph, id)
    const ids = picked.has(id) ? [...picked] : [id]
    const locked = !!lockedGroupOf(graph, id)
    const note = typeof node.config.note === 'string' && node.config.note.trim()
    const items: MenuItem[] = [{ label: readOnly || locked ? 'Open settings' : 'Edit settings', onSelect: () => openSettings(id) }]
    if (readOnly) {
      if (!isStart) items.push({ label: 'Copy', shortcut: `${MOD}+C`, onSelect: () => copySteps(ids) })
    } else {
      if (canConfigureWithJizza(node.type) && !locked) items.push({ label: 'Configure using Jizza', onSelect: () => openSettings(id, { jizza: true }) })
      if (!isStart && !locked) items.push({ label: note ? 'Edit note' : 'Add note', onSelect: () => openSettings(id, { note: true }) })
      if (!isStart) {
        items.push(
          { label: ids.length > 1 ? `Clone ${ids.length} steps` : 'Clone', shortcut: `${MOD}+D`, divider: true, onSelect: () => duplicateSteps(ids) },
          { label: 'Copy', shortcut: `${MOD}+C`, onSelect: () => copySteps(ids) },
        )
      }
      items.push({ label: 'Wire to the next step', divider: isStart, onSelect: () => { setPending({ from: id, branch: exitsOf(node)[0] ?? null }); setSelected(null) }, disabled: exitsOf(node).length === 0 })
      if (ids.length === 1 && groupButtonsOf(node).length > 0 && !buttonPathsAfter(graph, id) && !locked) {
        items.push({ label: 'Branch by button clicked', onSelect: () => branchOnButton(id) })
      }
      if (exitsOf(node).length > 1 || graph.edges.filter(x => x.from === id).length > 1) items.push({ label: 'Pick steps from here', onSelect: () => pickFrom(id) })
      if (!isStart) items.push({ label: 'Disconnect', disabled: locked, onSelect: () => disconnect(ids) })
      if (!isStart) {
        items.push({ label: ids.length > 1 ? `Group ${ids.length} steps` : 'Group this step', shortcut: `${MOD}+G`, divider: true, disabled: locked, onSelect: () => groupSteps(ids) })
        if (group && !group.locked) items.push({ label: 'Remove from group', onSelect: () => commit(removeFromGroup(graph, id)) })
        if (group) items.push({ label: group.locked ? 'Unlock group' : 'Lock group', onSelect: () => commit(updateGroup(graph, group.id, { locked: !group.locked })) })
        items.push({ label: ids.length > 1 ? `Delete ${ids.length} steps` : 'Delete', shortcut: 'Del', danger: true, divider: true, disabled: locked, onSelect: () => removeSteps(ids) })
      }
    }
    setMenu({ at: { x: e.clientX, y: e.clientY, flipX: e.clientX }, items })
  }

  /** The menu on a group's title bar. */
  const groupMenu = (e: React.MouseEvent, g: StepGroup) => {
    e.preventDefault()
    e.stopPropagation()
    if (readOnly) return
    const folded = isFolded(g.id)
    setMenu({
      at: { x: e.clientX, y: e.clientY, flipX: e.clientX },
      items: [
        { label: 'Rename', onSelect: () => setRenaming({ id: g.id, at: Date.now() }) },
        { label: 'Pick its steps', onSelect: () => { if (folded) toggleFold(g.id); setPicked(new Set(g.nodes)); setSelected(null) } },
        { label: g.locked ? 'Unlock' : 'Lock', onSelect: () => commit(updateGroup(graph, g.id, { locked: !g.locked })) },
        { label: folded ? 'Unfold' : 'Fold', onSelect: () => toggleFold(g.id) },
        { label: 'Ungroup', divider: true, disabled: g.locked, onSelect: () => { if (commit(ungroup(graph, g.id))) flash('Ungrouped. The steps stay where they are.') } },
      ],
    })
  }

  /** Starts moving steps by hand: one step, or every step of its group when it is locked. */
  const startMove = (key: string, ids: string[], e: React.PointerEvent) => {
    const bases = new Map<string, Box>()
    for (const id of ids) { const b = layout.at.get(id); if (b) bases.set(id, b) }
    if (!bases.size) return
    grab.current = { key, bases, sx: e.clientX, sy: e.clientY, moved: false }
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }
  const dragMove = (key: string, e: React.PointerEvent) => {
    const g = grab.current
    if (!g || g.key !== key) return
    const dx = e.clientX - g.sx
    const dy = e.clientY - g.sy
    if (!g.moved && Math.hypot(dx, dy) < 4) return
    g.moved = true
    setMoving(new Map([...g.bases].map(([id, b]) => [id, { x: b.x + dx, y: b.y + dy }])))
  }
  const endMove = (key: string, e: React.PointerEvent): boolean => {
    const g = grab.current
    if (!g || g.key !== key) return false
    grab.current = null
    if (!g.moved) return false
    setMoving(null)
    const dx = e.clientX - g.sx
    const dy = e.clientY - g.sy
    commit([...g.bases].reduce((acc, [id, b]) => placeStep(acc, id, { x: b.x + dx, y: b.y + dy }), graph))
    return true
  }

  const saveLabel = () => {
    if (labelFor) commit(setWireLabel(graph, labelFor, labelDraft))
    setLabelFor(null)
  }

  // ---- Status -------------------------------------------------------------------------------

  const ranCount = view ? [...view.nodes.values()].filter(s => s === 'done').length : 0
  let status = toast || (readOnly ? 'Read-only. Click a step to see its settings.' : 'Drag a step to move it, drag from an exit dot or a wire’s end to (re-)wire, or drop a new one onto a step or a wire.')
  if (!toast && quiet && hoverWire && routes.get(hoverWire)) status = 'Drag the round handle at the arrow to another step to re-wire it. Let go on empty space to take it off.'
  if (marquee) status = 'Let go to pick the steps inside the box. Hold Shift to add to the steps already picked.'
  if (!toast && picked.size) status = `${picked.size} ${picked.size === 1 ? 'step' : 'steps'} picked. Press ${MOD}+G or Group to group them.`
  if (moving) status = moving.size > 1 ? 'Let go to put the group here.' : 'Let go to put the step here. Auto align lines everything up again.'
  if (wireDrag) {
    const over = wireDrag.over && wireDrag.over !== wireDrag.from ? byId.get(wireDrag.over) : undefined
    status = over
      ? validTargets?.has(over.id) ? `Let go to wire “${byId.get(wireDrag.from)?.label}” to “${over.label}”.` : whyNot(wireDrag, over.id)
      : wireDrag.kind === 'end' ? 'Let go on a step to re-wire, or on empty space to take the wire off.' : 'Let go on a step to wire it, or on empty space to add a new step.'
  }
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
  // The wire being dragged: it snaps onto a step it can go to, and follows the pointer otherwise.
  const dragLine = useMemo(() => {
    if (!wireDrag) return null
    const overBox = wireDrag.over && validTargets?.has(wireDrag.over) ? layout.at.get(wireDrag.over) : undefined
    if (overBox) {
      const end = inPoint(overBox)
      const r = route(wireDrag.start, end, (layout.depth.get(wireDrag.over!) ?? 0) === (layout.depth.get(wireDrag.from) ?? 0) + 1)
      return { d: r.d, end, ok: true }
    }
    const [x1, y1] = wireDrag.start
    const [x2, y2] = wireDrag.at
    return { d: `M${x1} ${y1} C${x1} ${y1 + 60} ${x2} ${y2 - 60} ${x2} ${y2}`, end: wireDrag.at, ok: false }
  }, [wireDrag, validTargets, layout])

  const board = (
    <div onPointerDownCapture={() => { active.current = true }} onPointerLeave={() => { if (!fullscreen) active.current = false }}
      className={`jizza-wires flex overflow-hidden border-gray-200 dark:border-gray-800 ${fullscreen ? 'h-full' : compact ? 'h-full rounded-lg' : 'h-[calc(100vh-19rem)] min-h-[560px] rounded-xl border'}`}>
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
          <div className="relative flex shrink-0 items-center gap-1.5">
          {!readOnly && (
            <div className="flex items-center gap-0.5 rounded-lg border border-gray-200 bg-white p-0.5 dark:border-gray-700 dark:bg-gray-900">
              <ToolButton label="Add a step (Tab)" onClick={openPickerCentered}><Plus className="h-4 w-4" /></ToolButton>
              <ToolButton label={`Undo (${MOD}+Z)`} disabled={!history.canUndo} onClick={history.undo}><Undo2 className="h-4 w-4" /></ToolButton>
              <ToolButton label={`Redo (${MOD}+Shift+Z)`} disabled={!history.canRedo} onClick={history.redo}><Redo2 className="h-4 w-4" /></ToolButton>
            </div>
          )}
          {!readOnly && (
            <button type="button" disabled={!canGroup} onClick={() => groupSteps(toGroup)}
              title={canGroup ? `Put the picked steps in a group (${MOD}+G)` : 'Pick steps first: drag a box around them on empty space, or Shift+click each one'}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-xs font-medium text-gray-800 hover:bg-gray-50 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-white dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200 dark:disabled:hover:bg-gray-900">
              <Group className="h-3.5 w-3.5" />Group{toGroup.length > 1 ? ` ${toGroup.length}` : ''}
            </button>
          )}
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
          {!compact && (
            <div className="flex items-center gap-0.5 rounded-lg border border-gray-200 bg-white p-0.5 dark:border-gray-700 dark:bg-gray-900">
              <ToolButton label="Keyboard shortcuts" onClick={() => setHelp(v => !v)}><Keyboard className="h-4 w-4" /></ToolButton>
              <ToolButton label={fullscreen ? 'Exit full screen (Esc)' : 'Full screen'} onClick={() => setFullscreen(v => !v)}>
                {fullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
              </ToolButton>
            </div>
          )}
          {help && <Shortcuts readOnly={readOnly} />}
          </div>
        </div>

        <div role="region" aria-label="Workflow wires" className="absolute inset-0 select-none overflow-auto bg-gray-50 pt-11 dark:bg-gray-950"
          style={{ touchAction: marquee ? 'none' : undefined, backgroundImage: 'radial-gradient(rgb(var(--gray-300)) 1px, transparent 1px)', backgroundSize: '18px 18px' }}
          onDragOver={onDragOver} onDrop={onDrop}
          onContextMenu={e => { if (e.target === e.currentTarget || e.target === content.current) boardMenu(e) }}
          onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as globalThis.Node | null)) setSuggest(null) }}
          onPointerDown={e => {
            // Dragging on empty space draws a box; the steps it touches are picked (Shift adds to them).
            if (readOnly || pending || preview !== null || e.button !== 0 || (e.target !== e.currentTarget && e.target !== content.current)) return
            const p = boardPoint(e)
            if (!p) return
            setMarquee({ x1: p.x, y1: p.y, x2: p.x, y2: p.y, add: e.shiftKey || e.metaKey || e.ctrlKey })
            e.currentTarget.setPointerCapture?.(e.pointerId)
          }}
          onPointerMove={e => {
            if (!marquee) return
            const p = boardPoint(e)
            if (p) setMarquee({ ...marquee, x2: p.x, y2: p.y })
          }}
          onPointerUp={() => {
            if (!marquee) return
            const m = marquee
            setMarquee(null)
            const [l, r] = [Math.min(m.x1, m.x2), Math.max(m.x1, m.x2)]
            const [t, b] = [Math.min(m.y1, m.y2), Math.max(m.y1, m.y2)]
            if (r - l < 4 && b - t < 4) return
            marqueeDone.current = true
            const hit = graph.nodes.filter(n => {
              const box = layout.at.get(n.id)
              return box && n.type !== 'TRIGGER' && box.x < r && box.x + CARD_W > l && box.y < b && box.y + CARD_H > t
            }).map(n => n.id)
            setPicked(prev => new Set([...(m.add ? prev : []), ...hit]))
            setSelected(null)
            if (hit.length) flash(`${hit.length} ${hit.length === 1 ? 'step' : 'steps'} picked. Press ${MOD}+G or Group to group them.`)
          }}
          onPointerCancel={() => setMarquee(null)}
          onClick={e => {
            if (marqueeDone.current) { marqueeDone.current = false; return }
            if (e.target === e.currentTarget || e.target === content.current) { setSelected(null); setPending(null); setPicked(new Set()) }
          }}>
          <div ref={content} className="relative mx-auto select-none" style={{ width: layout.width, height: layout.height }}>
            {marquee && (
              <div aria-hidden className="pointer-events-none absolute z-40 rounded-md border border-brand-500 bg-brand-500/10"
                style={{ left: Math.min(marquee.x1, marquee.x2), top: Math.min(marquee.y1, marquee.y2), width: Math.abs(marquee.x2 - marquee.x1), height: Math.abs(marquee.y2 - marquee.y1) }} />
            )}

            <svg width={layout.width} height={layout.height} className="pointer-events-none absolute inset-0 overflow-visible">
              {[...routes.entries()].map(([k, r]) => {
                const tone = WIRE_TONE(r.edge.branch)
                const sugHot = suggest?.kind === 'wire' && wireKey(suggest.edge) === k
                const hot = sugHot || (quiet && hoverWire === k) || (labelFor && wireKey(labelFor) === k) || (picker?.edge && wireKey(picker.edge) === k)
                const hotColor = sugHot ? 'rgb(var(--brand-500))' : HOVER_BLUE
                const skipped = !!view && !view.travelled.has(k) && view.nodes.get(r.edge.from) === 'done'
                const dim = (focusIds && !sugHot) || (view && !view.travelled.has(k))
                const lifted = !!wireDrag?.old && wireKey(wireDrag.old) === k
                return (
                  <g key={k} style={{ opacity: lifted ? 0.15 : dim ? 0.4 : 1 }}>
                    <path d={r.d} fill="none" stroke={tone.line} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={skipped ? '4 5' : undefined} />
                    <polygon points={ARROW_HEAD(r.end)} fill={tone.head} />
                    {hot && <>
                      <path d={r.d} fill="none" stroke={hotColor} strokeWidth={7} strokeOpacity={0.14} strokeLinecap="round" strokeLinejoin="round" />
                      <path d={r.d} fill="none" stroke={hotColor} strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round" />
                      <path d={r.d} fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" className="jw-current" />
                      <polygon points={ARROW_HEAD(r.end)} fill={hotColor} />
                    </>}
                    {!readOnly && !r.fixed && (
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
              {dragLine && (
                <g data-testid="wire-drag">
                  <path d={dragLine.d} fill="none" stroke={dragLine.ok ? 'rgb(var(--brand-500))' : HOVER_BLUE} strokeWidth={2.25} strokeDasharray={dragLine.ok ? undefined : '6 5'} strokeLinecap="round" strokeLinejoin="round" />
                  {dragLine.ok
                    ? <polygon points={ARROW_HEAD(dragLine.end, true)} fill="rgb(var(--brand-500))" />
                    : <circle cx={dragLine.end[0]} cy={dragLine.end[1]} r={5} fill="#fff" stroke={HOVER_BLUE} strokeWidth={2} />}
                </g>
              )}
            </svg>

            {groups.map(g => {
              if (into.has(g.nodes[0])) {
                const box = layout.at.get(g.id)
                if (!box) return null
                const issues = g.nodes.filter(id => issuesByNode.has(id)).length
                return (
                  <FoldedGroup key={g.id} group={g} box={box} issues={issues} readOnly={readOnly}
                    run={view ? groupRun(g.nodes.map(id => view.nodes.get(id))) : undefined}
                    onMenu={e => groupMenu(e, g)}
                    onFold={() => toggleFold(g.id)} onLock={() => commit(updateGroup(graph, g.id, { locked: !g.locked }))} />
                )
              }
              const boxes = g.nodes.map(id => layout.at.get(id)).filter((b): b is Box => !!b)
              if (!boxes.length) return null
              const x = Math.min(...boxes.map(b => b.x)) - GROUP_PAD
              const y = Math.min(...boxes.map(b => b.y)) - GROUP_PAD - GROUP_HEAD
              const w = Math.max(...boxes.map(b => b.x)) + CARD_W + GROUP_PAD - x
              const h = Math.max(...boxes.map(b => b.y)) + CARD_H + GROUP_PAD + 14 - y
              return (
                <GroupFrame key={g.id} group={g} box={{ x, y, w, h }} readOnly={readOnly}
                  onMenu={e => groupMenu(e, g)}
                  onFold={() => toggleFold(g.id)}
                  onLock={() => commit(updateGroup(graph, g.id, { locked: !g.locked }))}
                  onRename={title => commit(updateGroup(graph, g.id, { title }))}
                  renameAsk={renaming?.id === g.id ? renaming.at : undefined}
                  onUngroup={() => { commit(ungroup(graph, g.id)); flash('Ungrouped. The steps stay where they are.') }}
                  onPointerDown={e => { if (!readOnly && e.button === 0 && preview === null && !pending) startMove(`group:${g.id}`, g.nodes, e) }}
                  onPointerMove={e => dragMove(`group:${g.id}`, e)}
                  onPointerUp={e => { endMove(`group:${g.id}`, e) }}
                  onPointerCancel={() => { grab.current = null; setMoving(null) }} />
              )
            })}

            {graph.nodes.map(n => {
              const box = layout.at.get(n.id)
              if (!box) return null
              const Icon = NODE_ICON[n.type]
              const meta = NODE_META[n.type]
              const agent = n.type === 'AGENT_STEP' ? agents?.find(a => a.id === n.config.agentId) : undefined
              const state = view?.nodes.get(n.id)
              const waiting = !!view && !state
              const target = (suggest?.kind === 'step' && suggest.id === n.id) || (!!wireDrag && wireDrag.over === n.id && !!validTargets?.has(n.id))
              const isSel = selected === n.id || editing === n.id || picked.has(n.id)
              const problems = issuesByNode.get(n.id)
              const canTarget = !!validTargets?.has(n.id)
              const num = numbers.get(n.id)
              const note = typeof n.config.note === 'string' && n.config.note.trim()
              return (
                <button key={n.id} type="button" aria-label={`${meta.label}: ${n.label}`}
                  onClick={e => {
                    if (justMoved.current) { justMoved.current = false; return }
                    if (pending) { connectTo(n.id); return }
                    // Shift (or Ctrl/⌘) + click picks steps to group.
                    if (!readOnly && (e.shiftKey || e.metaKey || e.ctrlKey)) {
                      setPicked(prev => {
                        const next = new Set(prev.size ? prev : selected ? [selected] : [])
                        if (next.has(n.id)) next.delete(n.id); else next.add(n.id)
                        return next
                      })
                      setSelected(null)
                      return
                    }
                    setPicked(new Set())
                    setSelected(s => (s === n.id ? null : n.id))
                  }}
                  onPointerDown={e => {
                    justMoved.current = false
                    if (readOnly || pending || preview !== null || e.button !== 0) return
                    // A step of a locked group moves the whole group, so its arrangement stays.
                    startMove(`step:${n.id}`, lockedGroupOf(graph, n.id)?.nodes ?? [n.id], e)
                  }}
                  onPointerMove={e => dragMove(`step:${n.id}`, e)}
                  onPointerUp={e => { if (endMove(`step:${n.id}`, e)) justMoved.current = true }}
                  onPointerCancel={() => { grab.current = null; setMoving(null) }}
                  onDoubleClick={() => openSettings(n.id)}
                  onContextMenu={e => stepMenu(e, n.id)}
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
                    opacity: (focusIds && !focusIds.has(n.id)) ? 0.35 : waiting ? 0.45 : (pending || wireDrag) && !canTarget && (pending ?? wireDrag)!.from !== n.id ? 0.45 : 1,
                    cursor: pending ? (canTarget ? 'pointer' : 'not-allowed') : moving?.has(n.id) ? 'grabbing' : readOnly ? 'pointer' : 'grab',
                    zIndex: moving?.has(n.id) ? 20 : undefined,
                    boxShadow: moving?.has(n.id) ? '0 18px 40px rgb(0 0 0 / 0.18)' : undefined,
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
                  {(pending || wireDrag) && canTarget && <span className="jw-pulse absolute -top-1.5 left-1/2 h-3 w-3 -translate-x-1/2 rounded-full border-2 border-brand-500 bg-brand-100" />}
                </button>
              )
            })}

            {/* Exit dots: click one, then a step, to wire it by hand. */}
            {graph.nodes.flatMap(n => {
              const box = layout.at.get(n.id)
              if (!box) return []
              const exits = exitsOf(n)
              return exits.map(b => {
                const wired = graph.edges.find(e => e.from === n.id && e.branch === b)
                const fixedExit = !!wired && isLockedEdge(graph, wired)
                const [x, y] = exitPoint(exits, box, b)
                const tone = WIRE_TONE(b)
                const picking = pending?.from === n.id && pending.branch === b
                const name = exitLabel(b, n)
                return (
                  <div key={`${n.id}:${b ?? 'out'}`} className="absolute z-[5]" style={{ left: x, top: y }}>
                    <button type="button" disabled={readOnly || fixedExit}
                      title={fixedExit ? 'Locked: this wire is inside a locked group' : readOnly ? undefined : 'Drag to a step to wire it, or click, then click the step'}
                      aria-label={`${name ? `"${name}" exit` : 'Exit'} of ${n.label}. Drag to the step that comes next, or click, then click it.`}
                      onPointerDown={e => { if (!fixedExit) startWire({ kind: 'exit', from: n.id, branch: b, old: wired ?? null, start: [x, y] }, e) }}
                      onClick={() => { if (justWired.current) return; setPending(picking ? null : { from: n.id, branch: b }); setSelected(null) }}
                      className={`absolute -left-1.5 -top-1.5 h-3 w-3 cursor-crosshair rounded-full border-[1.5px] bg-white transition-transform hover:scale-150 disabled:cursor-default disabled:hover:scale-100 dark:bg-gray-900 ${picking ? 'jw-pulse border-brand-500 bg-brand-500' : ''}`}
                      style={{ ...(picking ? {} : { borderColor: tone.head }), touchAction: 'none' }} />
                    {name && <span className={`pointer-events-none absolute left-2 top-1.5 max-w-[72px] truncate text-[10px] font-bold uppercase tracking-wide ${tone.text}`}>{name}</span>}
                  </div>
                )
              })
            })}

            {hoveredRoute && (
              <button type="button" aria-label={`Move the end of the wire from ${byId.get(hoveredRoute.edge.from)?.label}`} title="Drag to another step to re-wire"
                className="absolute z-30 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 cursor-grab rounded-full border-2 bg-white shadow active:cursor-grabbing dark:bg-gray-900"
                style={{ left: hoveredRoute.end[0], top: hoveredRoute.end[1] - 5, borderColor: HOVER_BLUE, touchAction: 'none' }}
                onMouseEnter={() => wireOn(hoverWire!)} onMouseLeave={wireOff}
                onPointerDown={e => startWire({ kind: 'end', from: hoveredRoute.edge.from, branch: hoveredRoute.edge.branch, old: hoveredRoute.edge, start: hoveredRoute.pts[0] }, e)} />
            )}
            {hoveredRoute && (
              <div className="absolute z-30 flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 animate-fade-in" style={{ left: hoveredRoute.mid[0], top: hoveredRoute.mid[1] }}
                onMouseEnter={() => wireOn(hoverWire!)} onMouseLeave={wireOff}>
                <div className="flex items-center gap-0.5 rounded-lg border-[1.5px] border-dashed bg-white p-0.5 shadow-md dark:bg-gray-900" style={{ borderColor: HOVER_BLUE }}>
                  <BarButton label="Delete wire" onClick={() => { commit(setNext(graph, hoveredRoute.edge.from, hoveredRoute.edge.branch, null)); setHoverWire(null); flash('Wire deleted.') }}>
                    <Scissors className="h-4 w-4" />
                  </BarButton>
                  <Divider />
                  <BarButton label="Re-wire" onClick={() => { setPending({ from: hoveredRoute.edge.from, branch: hoveredRoute.edge.branch }); setSelected(null); setHoverWire(null) }}>
                    <Cable className="h-4 w-4" />
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
                  {lockedGroupOf(graph, hoveredStep.id)
                    ? <><Divider /><span className="grid h-8 w-8 place-items-center text-amber-600" title="In a locked group: unlock it to delete this step"><Lock className="h-4 w-4" aria-label="Locked" /></span></>
                    : <><Divider /><BarButton label="Delete" tone="red" onClick={() => removeStep(hoveredStep.id)}><Trash2 className="h-4 w-4" /></BarButton></>}
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
            issues={agentIssues.get(editNode.id) ?? []} step={realRun?.steps.get(editNode.id)} runSteps={realRun?.steps} onDirty={onDirty}
            focusNote={focusNote} askJizza={askJizza}
            onSave={edited => { if (!commit(keepWiresSettings(applyStepEdit(graph, edited, editNode.id), graph, editNode.id))) return; unsaved.current = false; setEditing(null) }}
            onBranchByButton={edited => {
              unsaved.current = false
              const saved = keepWiresSettings(applyStepEdit(graph, edited, editNode.id), graph, editNode.id)
              const made = branchByButton(saved, editNode.id)
              if (!commit(made?.graph ?? saved)) return
              setEditing(null)
              setSelected(made?.id ?? editNode.id)
            }}
            onClose={() => { unsaved.current = false; setEditing(null) }} />
        )}
      </div>

      {menu && <ContextMenu at={menu.at} items={menu.items} onClose={() => setMenu(null)} />}
      {picker && (
        <StepPicker at={picker.at} title={picker.after ? 'Add the next step' : picker.edge ? 'Add a step on this wire' : 'Add a step'} agents={agents}
          allowEnd={!picker.edge} onClose={() => setPicker(null)}
          onRecord={readOnly ? undefined : () => {
            setRecordAt(picker.after ?? (picker.edge ? { from: picker.edge.from, branch: picker.edge.branch } : { from: selected, branch: null }))
            setPicker(null)
          }}
          onPick={(entry, agent) => {
            setPicker(null)
            if (!picker.after && !picker.edge) { addFromPalette(entry, agent); return }
            const r = picker.after ? addAfter(graph, picker.after.from, picker.after.branch, entry) : insertOnEdge(graph, picker.edge!, entry)
            if (commit(agent ? assignAgent(r.graph, r.id, agent) : r.graph)) setSelected(r.id)
          }} />
      )}
      {!readOnly && (
        <RecordFlow open={!!recordAt} mode="insert" onClose={() => setRecordAt(null)}
          onInsert={chain => {
            const from = recordAt?.from ?? startId
            if (!from) return
            const r = insertChain(graph, from, recordAt?.branch ?? null, chain as { nodes: CanvasNode[]; edges: CanvasEdge[] })
            if (commit(r.graph)) { setSelected(r.ids[0] ?? null); flash(`Added ${r.ids.length} recorded ${r.ids.length === 1 ? 'step' : 'steps'}.`) }
          }} />
      )}
    </div>
  )
  // Full screen has to leave the slide-over: its open animation leaves a transform that would pin
  // a "fixed" element to the panel instead of the window.
  return fullscreen ? createPortal(<div className="fixed inset-0 z-50 bg-white dark:bg-gray-950">{board}</div>, document.body) : board
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

function ToolButton({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} disabled={disabled} onClick={onClick}
      className="grid h-7 w-7 place-items-center rounded-md text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:opacity-35 disabled:hover:bg-transparent dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-gray-100">
      {children}
    </button>
  )
}

function Shortcuts({ readOnly }: { readOnly: boolean }) {
  const rows: Array<[string, string]> = readOnly
    ? [['Open a step', 'Double-click'], ['Copy steps', `${MOD}+C`], ['More options', 'Right-click']]
    : [
        ['Add a step', 'Tab'], ['Wire', 'Drag from an exit dot to a step'], ['Re-wire', 'Hover a wire, drag its end'], ['Open a step', 'Double-click'],
        ['Delete', 'Del / Backspace'], ['Undo / redo', `${MOD}+Z / ${MOD}+Shift+Z`], ['Copy / paste', `${MOD}+C / ${MOD}+V`], ['Clone', `${MOD}+D`],
        ['Pick steps', 'Shift+click, or drag on empty space'], ['Pick all', `${MOD}+A`], ['Group steps', `${MOD}+G`], ['Search steps', '/'], ['More options', 'Right-click'],
      ]
  return (
    <div className="absolute right-0 top-full z-40 mt-1.5 w-80 rounded-xl border border-gray-200 bg-white p-2.5 text-xs shadow-lg dark:border-gray-700 dark:bg-gray-900">
      {rows.map(([what, how]) => (
        <div key={what} className="flex justify-between gap-3 py-0.5">
          <span className="text-gray-600 dark:text-gray-400">{what}</span>
          <kbd className="text-right font-sans text-gray-900 dark:text-gray-100">{how}</kbd>
        </div>
      ))}
    </div>
  )
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl'
const GROUP_PAD = 16
const GROUP_HEAD = 30

/** A step group's box: a title bar to drag, rename, lock, fold or ungroup it, around its steps. */
function GroupFrame({ group, box, readOnly, onFold, onLock, onRename, renameAsk, onUngroup, onMenu, onPointerDown, onPointerMove, onPointerUp, onPointerCancel }: {
  group: StepGroup; box: { x: number; y: number; w: number; h: number }; readOnly: boolean; renameAsk?: number
  onMenu: (e: React.MouseEvent) => void
  onFold: () => void; onLock: () => void; onRename: (title: string) => void; onUngroup: () => void
  onPointerDown: (e: React.PointerEvent) => void; onPointerMove: (e: React.PointerEvent) => void
  onPointerUp: (e: React.PointerEvent) => void; onPointerCancel: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(group.title)
  useEffect(() => { if (!editing) setTitle(group.title) }, [group.title, editing])
  useEffect(() => { if (renameAsk && !readOnly) setEditing(true) }, [renameAsk]) // eslint-disable-line react-hooks/exhaustive-deps
  const finish = (keep: boolean) => {
    setEditing(false)
    if (keep && title.trim() !== group.title) onRename(title.trim())
  }
  const name = group.title || 'Untitled group'
  return (
    <div data-testid={`wires-group-${group.id}`} className={`pointer-events-none absolute rounded-2xl border-2 ${group.locked
      ? 'border-amber-500/70 bg-amber-500/[0.05]' : 'border-dashed border-brand-500/60 bg-brand-500/[0.05]'}`}
      style={{ left: box.x, top: box.y, width: box.w, height: box.h }}>
      <div className={`pointer-events-auto flex h-[30px] items-center gap-1 rounded-t-[14px] px-1.5 text-xs font-semibold ${readOnly ? '' : 'cursor-grab active:cursor-grabbing'} ${group.locked
        ? 'bg-amber-100 text-amber-900 dark:bg-amber-500/20 dark:text-amber-100' : 'bg-brand-50 text-brand-900 dark:bg-brand-500/15 dark:text-brand-100'}`}
        style={{ touchAction: 'none' }}
        onPointerDown={e => { if (!(e.target as HTMLElement).closest('button, input')) onPointerDown(e) }}
        onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}
        onContextMenu={onMenu}
        onDoubleClick={() => { if (!readOnly) setEditing(true) }}>
        <button type="button" onClick={onFold} aria-label={`Fold ${name}`} title="Fold into one card"
          className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10"><ChevronDown className="h-4 w-4" /></button>
        {editing
          ? <input autoFocus value={title} aria-label="Group title" maxLength={80} onFocus={e => e.currentTarget.select()}
              onChange={e => setTitle(e.target.value)} onBlur={() => finish(true)}
              onKeyDown={e => { if (e.key === 'Enter') finish(true); else if (e.key === 'Escape') finish(false) }}
              className="min-w-0 flex-1 rounded bg-white px-1.5 py-0.5 text-xs font-semibold text-gray-900 outline-none ring-1 ring-brand-500 dark:bg-gray-900 dark:text-gray-100" />
          : <span className="min-w-0 flex-1 truncate" title={readOnly ? name : `${name} (double-click to rename)`}>{name}</span>}
        {!readOnly && !editing && (
          <button type="button" onClick={() => setEditing(true)} aria-label={`Rename ${name}`} title="Rename"
            className="grid h-6 w-6 shrink-0 place-items-center rounded opacity-70 hover:bg-black/10 hover:opacity-100"><Pencil className="h-3.5 w-3.5" /></button>
        )}
        <span className="shrink-0 font-normal opacity-70">{group.nodes.length} {group.nodes.length === 1 ? 'step' : 'steps'}</span>
        {readOnly
          ? group.locked && <Lock className="mx-1 h-3.5 w-3.5 shrink-0" aria-label="Locked" />
          : <>
              <button type="button" onClick={onLock} aria-label={group.locked ? 'Unlock group' : 'Lock group'} aria-pressed={group.locked}
                title={group.locked ? 'Locked: its steps, settings and wiring can’t be changed. Click to unlock.' : 'Lock, so its steps and wiring can’t be changed by mistake'}
                className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10">
                {group.locked ? <Lock className="h-3.5 w-3.5" /> : <LockOpen className="h-3.5 w-3.5 opacity-70" />}
              </button>
              {!group.locked && (
                <button type="button" onClick={onUngroup} aria-label={`Ungroup ${name}`} title="Ungroup: remove the box, keep the steps"
                  className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10"><Ungroup className="h-3.5 w-3.5" /></button>
              )}
            </>}
      </div>
    </div>
  )
}

/** A folded group: one card standing in for its steps. */
function FoldedGroup({ group, box, issues, readOnly, run, onFold, onLock, onMenu }: {
  group: StepGroup; box: Box; issues: number; readOnly: boolean; run?: StepRun; onFold: () => void; onLock: () => void
  onMenu: (e: React.MouseEvent) => void
}) {
  const name = group.title || 'Untitled group'
  return (
    <div data-testid={`wires-group-${group.id}`} onContextMenu={onMenu}
      className={`absolute flex flex-col overflow-visible rounded-2xl border-2 bg-white shadow-sm dark:bg-gray-900 ${run ? STEP_RING[run] : group.locked ? 'border-amber-500/70' : 'border-dashed border-brand-500/60'}`}
      style={{ left: box.x, top: box.y, width: CARD_W, height: CARD_H }}>
      {run && (
        <span title={GROUP_RUN_TEXT[run]} className={`absolute -top-2.5 right-3 z-10 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold shadow ${STEP_BADGE[run].tone}`}>
          {run === 'done' && <Check className="h-3 w-3" strokeWidth={3} />}
          {run === 'running' && <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />}
          {STEP_BADGE[run].text}
        </span>
      )}
      <div className={`flex h-[30px] items-center gap-1 rounded-t-[14px] px-1.5 text-xs font-semibold ${group.locked
        ? 'bg-amber-100 text-amber-900 dark:bg-amber-500/20 dark:text-amber-100' : 'bg-brand-50 text-brand-900 dark:bg-brand-500/15 dark:text-brand-100'}`}>
        <button type="button" onClick={onFold} aria-label={`Unfold ${name}`} title="Show its steps"
          className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10"><ChevronRight className="h-4 w-4" /></button>
        <span className="min-w-0 flex-1 truncate">{name}</span>
        {issues > 0 && <span title={`${issues} ${issues === 1 ? 'step has' : 'steps have'} something to fix`} className="grid h-5 w-5 place-items-center rounded-full bg-red-500 text-white"><AlertCircle className="h-3.5 w-3.5" /></span>}
        {readOnly
          ? group.locked && <Lock className="mx-1 h-3.5 w-3.5 shrink-0" aria-label="Locked" />
          : <button type="button" onClick={onLock} aria-label={group.locked ? 'Unlock group' : 'Lock group'} aria-pressed={group.locked}
              className="grid h-6 w-6 shrink-0 place-items-center rounded hover:bg-black/10">
              {group.locked ? <Lock className="h-3.5 w-3.5" /> : <LockOpen className="h-3.5 w-3.5 opacity-70" />}
            </button>}
      </div>
      <p className="px-3 py-2 text-xs text-gray-600 dark:text-gray-300">
        {group.nodes.length} {group.nodes.length === 1 ? 'step' : 'steps'}{group.locked && <span className="text-amber-700 dark:text-amber-300"> · locked</span>}
      </p>
    </div>
  )
}

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
