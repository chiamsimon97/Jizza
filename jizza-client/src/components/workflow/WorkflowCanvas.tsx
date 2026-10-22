import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { createPortal } from 'react-dom'
import { useQuery } from '@tanstack/react-query'
import ReactFlow, {
  Background, BackgroundVariant, ConnectionLineType, Controls, MarkerType, MiniMap, Panel, ReactFlowProvider, useReactFlow,
  type Connection, type Edge, type EdgeChange, type EdgeSelectionChange, type Node, type NodeChange, type NodeDimensionChange, type NodeSelectionChange,
  type OnConnectStartParams,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { ChevronDown, Keyboard, Maximize2, Minimize2, Plus, Redo2, Search, Sparkles, Undo2 } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { api } from '@/lib/api'
import type { AgentDto, ConnectionDto, GeneratedToolDto, RunDto, RunStepDto, WorkflowDto } from '@/types'
import {
  NODE_H, NODE_META, NODE_W, PALETTE_GROUPS, addAfter, addNode, applyStepEdit, arrangeFrom, branchesFrom, cloneNodes, descendantsOf, exitsOf, freeSpot,
  insertOnEdge, issues as findIssues, reaches, removeNode, setNext, tidy, unreachable, updateNode, type CanvasEdge, type CanvasGraph, type NodeType,
} from './canvasModel'
import {
  ContextMenu, NODE_ICON, StepNode, StepPicker, Wire, exitOf, handleOf,
  type Exit, type MenuAt, type MenuItem, type StepData, type StepRun, type WireData,
} from './canvasParts'
import { CanvasInspector, type ToolOption, type WorkflowOption } from './CanvasInspector'
import { canConfigureWithJizza } from '@/jizza/commands'

interface Props {
  graph: CanvasGraph
  onChange: (graph: CanvasGraph) => void
  readOnly?: boolean
  /** A run to show on the canvas: which steps ran, failed or are waiting, and what they produced. */
  run?: RunDto | null
}

const nodeTypes = { step: StepNode }
const edgeTypes = { wire: Wire }
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl'

/** What was copied, kept across editors so steps can be pasted into another workflow. */
let clipboard: { graph: CanvasGraph; ids: string[] } | null = null

const edgeId = (e: CanvasEdge) => `${e.from}:${handleOf(e.branch)}`

/** Undo history for the canvas. It lives above the editor so going full screen keeps it. */
function useHistory(graph: CanvasGraph, onChange: (g: CanvasGraph) => void) {
  const past = useRef<CanvasGraph[]>([])
  const future = useRef<CanvasGraph[]>([])
  const last = useRef<{ key?: string; at: number }>({ at: 0 })
  const emitted = useRef(graph)
  const [, rerender] = useState(0)

  // A graph we didn't produce (another workflow opened, or the steps editor converted) starts afresh.
  if (graph !== emitted.current) {
    past.current = []
    future.current = []
    emitted.current = graph
  }

  const emit = (next: CanvasGraph) => { emitted.current = next; onChange(next) }
  return {
    canUndo: past.current.length > 0,
    canRedo: future.current.length > 0,
    /** A change worth its own undo step. Keystrokes in one field within a second share one. */
    commit(next: CanvasGraph, key?: string) {
      const now = Date.now()
      if (!(key && last.current.key === key && now - last.current.at < 1000)) {
        past.current = [...past.current.slice(-99), emitted.current]
        future.current = []
        rerender(n => n + 1)
      }
      last.current = { key, at: now }
      emit(next)
    },
    /** Marks the current graph as an undo point without changing it (before a drag). */
    checkpoint() {
      past.current = [...past.current.slice(-99), emitted.current]
      future.current = []
      last.current = { at: 0 }
      rerender(n => n + 1)
    },
    /** A change that continues the last undo step (frames of a drag). */
    replace(next: CanvasGraph) { emit(next) },
    undo() {
      const prev = past.current.pop()
      if (!prev) return
      future.current.push(emitted.current)
      last.current = { at: 0 }
      emit(prev)
      rerender(n => n + 1)
    },
    redo() {
      const next = future.current.pop()
      if (!next) return
      past.current.push(emitted.current)
      last.current = { at: 0 }
      emit(next)
      rerender(n => n + 1)
    },
  }
}

type History = ReturnType<typeof useHistory>

/**
 * The n8n-style workflow canvas. Drag from a step's exit to another step to connect them, drag a
 * wire's end to reconnect it, hover a wire to put a step on it or delete it, and use the + on a
 * free exit to add what comes next.
 */
/** Points a freshly added agent step at {@code agent} and names the step after it. */
function assignAgent(graph: CanvasGraph, id: string, agent: AgentDto): CanvasGraph {
  const node = graph.nodes.find(n => n.id === id)
  return updateNode(graph, id, { label: agent.name, config: { ...node?.config, agentId: agent.id } })
}

export function WorkflowCanvas({ graph, onChange, readOnly = false, run }: Props) {
  const history = useHistory(graph, onChange)
  const [fullscreen, setFullscreen] = useState(false)
  const editor = (
    <ReactFlowProvider>
      <CanvasEditor graph={graph} history={history} readOnly={readOnly} run={run}
        fullscreen={fullscreen} onFullscreen={setFullscreen} />
    </ReactFlowProvider>
  )
  // Full screen has to leave the slide-over: its open animation leaves a transform that would pin
  // a "fixed" element to the panel instead of the window.
  return fullscreen ? createPortal(<div className="fixed inset-0 z-50 bg-white dark:bg-gray-950">{editor}</div>, document.body) : editor
}

type Picker =
  | { kind: 'after'; from: string; branch: Exit; at: { x: number; y: number }; drop?: { x: number; y: number } }
  | { kind: 'wire'; edge: CanvasEdge; at: { x: number; y: number } }
  | { kind: 'free'; at: { x: number; y: number }; drop?: { x: number; y: number } }

function CanvasEditor({ graph, history, readOnly, run, fullscreen, onFullscreen }: {
  graph: CanvasGraph; history: History; readOnly: boolean; run?: RunDto | null
  fullscreen: boolean; onFullscreen: (v: boolean) => void
}) {
  const flow = useReactFlow()
  const wrapper = useRef<HTMLDivElement>(null)
  const [selNodes, setSelNodes] = useState<string[]>([])
  const [selEdges, setSelEdges] = useState<string[]>([])
  const [hovered, setHovered] = useState<string | null>(null)
  const hoverTimer = useRef<number>()
  const [picker, setPicker] = useState<Picker | null>(null)
  /** {@code nodeId} is the step whose options menu this is, which keeps its button showing. */
  const [menu, setMenu] = useState<{ at: MenuAt; items: MenuItem[]; nodeId?: string } | null>(null)
  /** Whether the open step settings have changes not yet saved, and whether to open them at the note. */
  const unsaved = useRef(false)
  const [focusNote, setFocusNote] = useState(false)
  /** The step "Configure using Jizza" was chosen for, so its settings open with Jizza on them. */
  const [askJizza, setAskJizza] = useState<string | null>(null)
  const [help, setHelp] = useState(false)
  const [paletteQuery, setPaletteQuery] = useState('')
  const connecting = useRef<{ from: string; branch: Exit; done: boolean } | null>(null)
  const reconnected = useRef(true)
  const pastes = useRef(0)
  const active = useRef(false)
  // React Flow hides a node it has no size for until it measures it again, so measured sizes are
  // passed back on every render; without them each edit would blink every node out for a frame.
  const [sizes, setSizes] = useState<Map<string, { width: number; height: number }>>(() => new Map())

  // Archived agents too, so a step still pointing at one can say so instead of looking empty.
  const { data: agents } = useQuery({ queryKey: ['agents', 'all'], queryFn: () => agentsApi.list('all') })
  const { data: tools } = useQuery({ queryKey: ['workflow-canvas-tools'], queryFn: loadTools, enabled: !readOnly, staleTime: 60_000 })
  const { data: workflows } = useQuery({
    queryKey: ['workflow-canvas-workflows'], enabled: !readOnly, staleTime: 60_000,
    queryFn: () => api.get<WorkflowDto[]>('/api/workflows')
      .then(ws => ws.map((w): WorkflowOption => ({ id: w.id, name: w.name, status: w.status }))).catch(() => [] as WorkflowOption[]),
  })

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

  const [showRun, setShowRun] = useState(true)
  const startId = graph.nodes.find(n => n.type === 'TRIGGER')?.id
  const runInfo = useMemo(() => (run && showRun ? runState(run, startId) : null), [run, showRun, startId])

  const selected = selNodes.length === 1 ? graph.nodes.find(n => n.id === selNodes[0]) ?? null : null

  /**
   * Leaving the step whose settings are open, with changes not saved, asks first. False when the
   * user would rather stay.
   */
  const mayLeave = useCallback((nodes: string[]) => {
    const open = selNodes.length === 1 ? selNodes[0] : null
    if (!open || !unsaved.current || (nodes.length === 1 && nodes[0] === open)) return true
    if (!window.confirm('Leave this step without saving your changes?')) return false
    unsaved.current = false
    return true
  }, [selNodes])

  const select = useCallback((nodes: string[], edges: string[] = []) => {
    if (!mayLeave(nodes)) return
    setFocusNote(false)
    setAskJizza(null)
    setSelNodes(nodes)
    setSelEdges(edges)
  }, [mayLeave])

  // ---- Editing operations -------------------------------------------------------------------

  const commit = history.commit

  const place = useCallback((type: NodeType, at?: { x: number; y: number }, agent?: AgentDto) => {
    const anchor = graph.nodes.find(n => n.id === selNodes[0]) ?? graph.nodes[graph.nodes.length - 1]
    const { x, y } = at
      ? { x: Math.round(at.x - NODE_W / 2), y: Math.round(at.y - NODE_H / 2) }
      : freeSpot(graph, anchor ? anchor.x + 250 : 30, anchor ? anchor.y : 150)
    const result = addNode(graph, type, x, y, selNodes[0])
    commit(agent ? assignAgent(result.graph, result.id, agent) : result.graph)
    select([result.id])
    if (!at) requestAnimationFrame(() => flow.setCenter(x + NODE_W / 2, y + NODE_H / 2, { zoom: flow.getZoom(), duration: 300 }))
  }, [graph, selNodes, commit, select, flow])

  const pick = useCallback((type: NodeType, agent?: AgentDto) => {
    if (!picker) return
    setPicker(null)
    // Picking an agent adds an agent step that already knows who does it.
    const withAgent = (r: { graph: CanvasGraph; id: string }) =>
      agent ? { ...r, graph: assignAgent(r.graph, r.id, agent) } : r
    if (picker.kind === 'wire') {
      const r = withAgent(insertOnEdge(graph, picker.edge, type))
      commit(r.graph)
      select([r.id])
      return
    }
    if (picker.kind === 'after') {
      const r = withAgent(addAfter(graph, picker.from, picker.branch, type))
      const drop = picker.drop
      const placed = drop
        ? { ...r.graph, nodes: r.graph.nodes.map(n => (n.id === r.id ? { ...n, x: Math.round(drop.x), y: Math.round(drop.y - NODE_H / 2) } : n)) }
        : r.graph
      commit(placed)
      select([r.id])
      return
    }
    place(type, picker.drop, agent)
  }, [picker, graph, commit, select, place])

  const deleteSelection = useCallback(() => {
    if (readOnly) return
    const nodes = selNodes.filter(id => graph.nodes.find(n => n.id === id)?.type !== 'TRIGGER')
    if (!nodes.length && !selEdges.length) return
    let next: CanvasGraph = { ...graph, edges: graph.edges.filter(e => !selEdges.includes(edgeId(e))) }
    for (const id of nodes) next = removeNode(next, id)
    // The step whose settings were open is gone, and its unsaved changes with it.
    unsaved.current = false
    commit(next)
    select([])
  }, [readOnly, selNodes, selEdges, graph, commit, select])

  const copy = useCallback((ids = selNodes) => {
    const picked = ids.filter(id => graph.nodes.find(n => n.id === id)?.type !== 'TRIGGER')
    if (picked.length) { clipboard = { graph, ids: picked }; pastes.current = 0 }
  }, [selNodes, graph])

  const paste = useCallback(() => {
    if (readOnly || !clipboard) return
    pastes.current += 1
    const r = cloneNodes(graph, clipboard.ids, clipboard.graph, 40 * pastes.current)
    commit(r.graph)
    select(r.ids)
  }, [readOnly, graph, commit, select])

  const duplicate = useCallback((ids = selNodes) => {
    if (readOnly) return
    const r = cloneNodes(graph, ids, graph, 40)
    if (!r.ids.length) return
    commit(r.graph)
    select(r.ids)
  }, [readOnly, selNodes, graph, commit, select])

  const tidyUp = useCallback(() => {
    commit(tidy(graph))
    requestAnimationFrame(() => flow.fitView({ padding: 0.2, duration: 300, maxZoom: 1 }))
  }, [graph, commit, flow])

  const openPickerCentered = useCallback(() => {
    const rect = wrapper.current?.getBoundingClientRect()
    const at = rect ? { x: rect.left + rect.width / 2 - 128, y: rect.top + 80 } : { x: 200, y: 200 }
    setPicker({ kind: 'free', at })
  }, [])

  // Settings open over the right of the canvas; slide the view so the step being edited stays in sight.
  useEffect(() => {
    const node = selected && wrapper.current ? graph.nodes.find(n => n.id === selected.id) : null
    if (!node || !wrapper.current) return
    const area = wrapper.current.querySelector('.react-flow')?.getBoundingClientRect()
    if (!area) return
    const { x, zoom } = flow.getViewport()
    const right = area.left + x + (node.x + NODE_W) * zoom
    const limit = area.right - 416 - 24
    if (right > limit) flow.setViewport({ ...flow.getViewport(), x: x - (right - limit) }, { duration: 250 })
  }, [selected?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- Keyboard ---------------------------------------------------------------------------

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!active.current && !fullscreen) return
      const t = e.target as HTMLElement
      if (t.closest?.('input, textarea, select, [contenteditable="true"], [role="dialog"]')) return
      const mod = e.metaKey || e.ctrlKey
      const key = e.key.toLowerCase()
      if (e.key === 'Escape') {
        if (picker || menu || help) { setPicker(null); setMenu(null); setHelp(false) }
        else if (selNodes.length || selEdges.length) select([])
        else if (fullscreen) onFullscreen(false)
        else return
        // Handled here: React Flow's own Escape on a focused step would select it again.
        e.preventDefault()
        e.stopPropagation()
        return
      }
      if (mod && key === 'c') { copy(); return }
      if (!mod && key === '1') { flow.fitView({ padding: 0.2, duration: 300, maxZoom: 1 }); return }
      if (readOnly) return
      if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); deleteSelection() }
      else if (mod && key === 'z' && !e.shiftKey) { e.preventDefault(); history.undo() }
      else if (mod && (key === 'y' || (key === 'z' && e.shiftKey))) { e.preventDefault(); history.redo() }
      else if (mod && key === 'v') { e.preventDefault(); paste() }
      else if (mod && key === 'd') { e.preventDefault(); duplicate() }
      else if (mod && key === 'a') { e.preventDefault(); select(graph.nodes.map(n => n.id)) }
      else if (e.key === 'Tab' && !mod) { e.preventDefault(); openPickerCentered() }
      else if (!mod && e.shiftKey && key === 't') { e.preventDefault(); tidyUp() }
    }
    // Capture, so Escape is claimed before the slide-over around the canvas sees it.
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [fullscreen, picker, menu, help, selNodes, selEdges, readOnly, deleteSelection, history, copy, paste, duplicate, select, graph, openPickerCentered, tidyUp, flow, onFullscreen])

  // ---- React Flow wiring ------------------------------------------------------------------

  /**
   * The options on a step's ⋮ button. Selecting and arranging "from here" only make sense where
   * the flow splits, so they show only on a step with more than one connection leaving it.
   */
  const stepMenu = useCallback((id: string, at: MenuAt) => {
    const node = graph.nodes.find(n => n.id === id)
    if (!node || readOnly) return
    const isStart = node.type === 'TRIGGER'
    const note = typeof node.config.note === 'string' && node.config.note.trim()
    const items: MenuItem[] = []
    if (!isStart) items.push({ label: 'Clone', shortcut: `${MOD}+D`, onSelect: () => duplicate([id]) })
    if (branchesFrom(graph, id)) {
      items.push(
        {
          label: 'Select steps from here', divider: items.length > 0,
          onSelect: () => select([id, ...descendantsOf(graph, id)]),
        },
        {
          label: 'Auto arrange from here',
          onSelect: () => {
            commit(arrangeFrom(graph, id))
            requestAnimationFrame(() => flow.fitView({ padding: 0.2, duration: 300, maxZoom: 1, nodes: [{ id }, ...descendantsOf(graph, id).map(d => ({ id: d }))] }))
          },
        },
      )
    }
    if (canConfigureWithJizza(node.type)) {
      items.unshift({ label: 'Configure using Jizza', onSelect: () => { select([id]); setAskJizza(id) } })
      if (items.length > 1) items[1] = { ...items[1], divider: true }
    }
    if (!isStart) {
      items.push(
        { label: note ? 'Edit note' : 'Add note', divider: !branchesFrom(graph, id) && items.length > 0, onSelect: () => { select([id]); setFocusNote(true) } },
        {
          label: 'Delete', shortcut: 'Del', danger: true, divider: true,
          onSelect: () => {
            if (selNodes.length === 1 && selNodes[0] === id) unsaved.current = false
            commit(removeNode(graph, id))
            select(selNodes.filter(s => s !== id))
          },
        },
      )
    }
    if (items.length) setMenu({ at, items, nodeId: id })
  }, [graph, readOnly, duplicate, select, commit, flow, selNodes])

  const onDirty = useCallback((dirty: boolean) => { unsaved.current = dirty }, [])

  const onAddAfter = useCallback((from: string, branch: Exit, at: { x: number; y: number }) => setPicker({ kind: 'after', from, branch, at }), [])

  const nodes: Node<StepData>[] = useMemo(() => {
    const used = new Set(graph.edges.map(edgeId))
    return graph.nodes.map(n => ({
      id: n.id,
      type: 'step',
      position: { x: n.x, y: n.y },
      ...sizes.get(n.id),
      selected: selNodes.includes(n.id),
      deletable: !readOnly && n.type !== 'TRIGGER',
      data: {
        node: n,
        agent: agents?.find(a => a.id === n.config.agentId),
        issues: issuesByNode.get(n.id) ?? [],
        orphan: orphans.has(n.id),
        run: runInfo?.nodes.get(n.id),
        freeExits: exitsOf(n).filter(b => !used.has(`${n.id}:${handleOf(b)}`)),
        readOnly,
        menuOpen: menu?.nodeId === n.id,
        onAddAfter: (b, at) => onAddAfter(n.id, b, at),
        onMenu: readOnly ? undefined : at => stepMenu(n.id, at),
      },
    }))
  }, [graph, sizes, selNodes, readOnly, agents, issuesByNode, orphans, runInfo, onAddAfter, menu, stepMenu])

  const onHover = useCallback((id: string | null) => {
    window.clearTimeout(hoverTimer.current)
    if (id) setHovered(id)
    else hoverTimer.current = window.setTimeout(() => setHovered(null), 180)
  }, [])

  const edges: Edge<WireData>[] = useMemo(() => graph.edges.map(e => {
    const id = edgeId(e)
    const reached = runInfo?.nodes.get(e.to)
    const travelled = !!runInfo && runInfo.ran.has(e.from) && (runInfo.ran.has(e.to) || reached === 'running' || reached === 'waiting' || reached === 'failed')
    return {
      id, source: e.from, target: e.to, sourceHandle: handleOf(e.branch), targetHandle: 'in', type: 'wire',
      selected: selEdges.includes(id),
      animated: travelled && runInfo?.live,
      updatable: !readOnly,
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: travelled ? '#10b981' : '#9ca3af' },
      data: {
        branch: e.branch, travelled, hovered: hovered === id, readOnly, onHover,
        onInsert: (_: string, at: { x: number; y: number }) => setPicker({ kind: 'wire', edge: e, at }),
        onDelete: () => { commit({ ...graph, edges: graph.edges.filter(x => x !== e) }); select([]); setHovered(null) },
      },
    }
  }), [graph, selEdges, hovered, readOnly, runInfo, onHover, commit, select])

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    let moved: Map<string, { x: number; y: number }> | null = null
    const selects = changes.filter((c): c is NodeSelectionChange => c.type === 'select')
    const measured = changes.filter((c): c is NodeDimensionChange => c.type === 'dimensions' && !!c.dimensions)
    for (const c of changes) {
      if (c.type === 'position' && c.position && !readOnly) (moved ??= new Map()).set(c.id, c.position)
    }
    if (measured.length) {
      setSizes(prev => {
        if (measured.every(c => prev.get(c.id)?.width === c.dimensions!.width && prev.get(c.id)?.height === c.dimensions!.height)) return prev
        const next = new Map(prev)
        for (const c of measured) next.set(c.id, c.dimensions!)
        return next
      })
    }
    if (moved) {
      const at = moved
      history.replace({ ...graph, nodes: graph.nodes.map(n => (at.has(n.id) ? { ...n, x: Math.round(at.get(n.id)!.x), y: Math.round(at.get(n.id)!.y) } : n)) })
    }
    if (selects.length) {
      const next = applySelection(selNodes, selects)
      if (next !== selNodes && mayLeave(next)) { setFocusNote(false); setAskJizza(null); setSelNodes(next) }
    }
  }, [readOnly, graph, history, selNodes, mayLeave])

  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    const selects = changes.filter((c): c is EdgeSelectionChange => c.type === 'select')
    if (selects.length) setSelEdges(prev => applySelection(prev, selects))
  }, [])

  const isValidConnection = useCallback((c: Connection) => {
    if (!c.source || !c.target || c.source === c.target) return false
    if (graph.nodes.find(n => n.id === c.target)?.type === 'TRIGGER') return false
    // A wire back to something that leads here would make a loop, which the engine refuses.
    return !reaches(graph, c.target, c.source)
  }, [graph])

  const onConnectStart = useCallback((_: unknown, p: OnConnectStartParams) => {
    connecting.current = p.handleType === 'source' && p.nodeId ? { from: p.nodeId, branch: exitOf(p.handleId), done: false } : null
  }, [])

  // One wire per exit: connecting an exit that already has one moves it.
  const onConnect = useCallback((c: Connection) => {
    if (connecting.current) connecting.current.done = true
    if (!c.source || !c.target) return
    commit(setNext(graph, c.source, exitOf(c.sourceHandle), c.target))
  }, [graph, commit])

  // A wire dropped on empty canvas asks what to add there, as in n8n.
  const onConnectEnd = useCallback((e: MouseEvent | TouchEvent) => {
    const c = connecting.current
    connecting.current = null
    if (!c || c.done || readOnly) return
    const target = e.target as HTMLElement
    if (!target.classList?.contains('react-flow__pane')) return
    const point = 'changedTouches' in e ? e.changedTouches[0] : e
    setPicker({
      kind: 'after', from: c.from, branch: c.branch, at: { x: point.clientX, y: point.clientY },
      drop: flow.screenToFlowPosition({ x: point.clientX, y: point.clientY }),
    })
  }, [readOnly, flow])

  const onEdgeUpdateStart = useCallback(() => { reconnected.current = false }, [])

  const onEdgeUpdate = useCallback((old: Edge, c: Connection) => {
    reconnected.current = true
    if (!c.source || !c.target) return
    const without = { ...graph, edges: graph.edges.filter(e => edgeId(e) !== old.id) }
    commit(setNext(without, c.source, exitOf(c.sourceHandle), c.target))
  }, [graph, commit])

  // Pulling a wire off a step and letting go on empty canvas removes it.
  const onEdgeUpdateEnd = useCallback((_: unknown, old: Edge) => {
    if (reconnected.current) return
    reconnected.current = true
    commit({ ...graph, edges: graph.edges.filter(e => edgeId(e) !== old.id) })
  }, [graph, commit])

  const onDrop = useCallback((e: DragEvent) => {
    e.preventDefault()
    const type = e.dataTransfer.getData('application/x-jizza-node') as NodeType
    if (type) place(type, flow.screenToFlowPosition({ x: e.clientX, y: e.clientY }))
  }, [place, flow])

  const nodeMenu = useCallback((e: React.MouseEvent, node: Node) => {
    e.preventDefault()
    const ids = selNodes.includes(node.id) ? selNodes : [node.id]
    if (!selNodes.includes(node.id)) select([node.id])
    const onlyStart = ids.every(id => graph.nodes.find(n => n.id === id)?.type === 'TRIGGER')
    setMenu({
      at: { x: e.clientX, y: e.clientY },
      items: readOnly ? [{ label: 'Copy', shortcut: `${MOD}+C`, disabled: onlyStart, onSelect: () => copy(ids) }] : [
        { label: 'Open settings', onSelect: () => select([node.id]) },
        { label: 'Duplicate', shortcut: `${MOD}+D`, disabled: onlyStart, onSelect: () => duplicate(ids) },
        { label: 'Copy', shortcut: `${MOD}+C`, disabled: onlyStart, onSelect: () => copy(ids) },
        { label: 'Disconnect', onSelect: () => commit({ ...graph, edges: graph.edges.filter(x => !ids.includes(x.from) && !ids.includes(x.to)) }) },
        ...(ids.length === 1 && branchesFrom(graph, node.id) ? [
          { label: 'Select steps from here', onSelect: () => select([node.id, ...descendantsOf(graph, node.id)]) },
          { label: 'Auto arrange from here', onSelect: () => commit(arrangeFrom(graph, node.id)) },
        ] : []),
        {
          label: ids.length > 1 ? `Delete ${ids.length} steps` : 'Delete', shortcut: 'Del', danger: true, disabled: onlyStart,
          onSelect: () => {
            let g = graph
            for (const id of ids) if (g.nodes.find(n => n.id === id)?.type !== 'TRIGGER') g = removeNode(g, id)
            commit(g)
            select([])
          },
        },
      ],
    })
  }, [selNodes, select, graph, readOnly, copy, duplicate, commit])

  const paneMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    if (readOnly) return
    const drop = flow.screenToFlowPosition({ x: e.clientX, y: e.clientY })
    setMenu({
      at: { x: e.clientX, y: e.clientY },
      items: [
        { label: 'Add a step here', shortcut: 'Tab', onSelect: () => setPicker({ kind: 'free', at: { x: e.clientX, y: e.clientY }, drop: { x: drop.x + NODE_W / 2, y: drop.y + NODE_H / 2 } }) },
        { label: 'Paste', shortcut: `${MOD}+V`, disabled: !clipboard, onSelect: paste },
        { label: 'Select all', shortcut: `${MOD}+A`, onSelect: () => select(graph.nodes.map(n => n.id)) },
        { label: 'Tidy up', shortcut: 'Shift+T', onSelect: tidyUp },
      ],
    })
  }, [readOnly, flow, paste, select, graph, tidyUp])

  const [folded, setFolded] = useState<Set<string>>(() => new Set())
  const q = paletteQuery.trim().toLowerCase()
  const palette = PALETTE_GROUPS
    .map(g => ({ ...g, types: g.types.filter(t => !q || `${NODE_META[t].label} ${NODE_META[t].desc} ${g.title}`.toLowerCase().includes(q)) }))
    .filter(g => g.types.length)
  const blocking = issueList.length

  return (
    <div ref={wrapper}
      onPointerDownCapture={() => { active.current = true }}
      onPointerLeave={() => { if (!fullscreen) active.current = false }}
      className={`jizza-flow flex overflow-hidden border-gray-200 dark:border-gray-800 ${fullscreen ? 'h-full' : 'h-[calc(100vh-19rem)] min-h-[420px] rounded-xl border'}`}>
      {!readOnly && (
        <aside className="hidden w-48 shrink-0 flex-col gap-1.5 overflow-y-auto border-r border-gray-200 bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-950 sm:flex">
          <p className="px-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Add a step</p>
          <label className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-2 py-1 dark:border-gray-700 dark:bg-gray-900">
            <Search className="h-3.5 w-3.5 text-gray-400" />
            <input value={paletteQuery} onChange={e => setPaletteQuery(e.target.value)} placeholder="Search" aria-label="Search steps"
              className="w-full bg-transparent text-xs text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
          </label>
          <p className="px-1 text-[11px] leading-snug text-gray-400">Drag onto the canvas, or click to add after the selected step.</p>
          {palette.map(group => {
            // A search shows every match, folded groups included.
            const open = !!q || !folded.has(group.title)
            return (
            <section key={group.title} className="flex flex-col gap-1.5">
              <button type="button" aria-expanded={open} onClick={() => setFolded(f => { const n = new Set(f); if (n.has(group.title)) n.delete(group.title); else n.add(group.title); return n })}
                className="mt-1 flex items-center gap-1 px-1 text-left text-[11px] font-semibold uppercase tracking-wide text-gray-500 hover:text-gray-800 dark:hover:text-gray-200">
                <ChevronDown className={`h-3 w-3 transition-transform ${open ? '' : '-rotate-90'}`} />
                <span className="flex-1">{group.title}</span>
                <span className="font-normal normal-case text-gray-400">{group.types.length}</span>
              </button>
              {open && group.types.map(type => {
            const Icon = NODE_ICON[type]
            return (
              <button key={type} type="button" draggable
                onDragStart={e => { e.dataTransfer.setData('application/x-jizza-node', type); e.dataTransfer.effectAllowed = 'move' }}
                onClick={() => place(type)}
                className="flex items-start gap-2 rounded-lg border border-gray-200 bg-white px-2 py-2 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 dark:border-gray-800 dark:bg-gray-900 dark:hover:border-brand-700">
                <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-md ${NODE_META[type].tone}`}><Icon className="h-3.5 w-3.5" /></span>
                <span className="min-w-0">
                  <span className="block text-xs font-medium text-gray-800 dark:text-gray-200">{NODE_META[type].label}</span>
                  <span className="block text-[11px] leading-snug text-gray-500 dark:text-gray-400">{NODE_META[type].desc}</span>
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

      <div className="relative min-w-0 flex-1" onDragOver={e => { e.preventDefault(); e.dataTransfer.dropEffect = 'move' }} onDrop={onDrop}>
        <ReactFlow
          nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
          onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
          onNodeDragStart={() => { if (!readOnly) history.checkpoint() }}
          onConnect={onConnect} onConnectStart={onConnectStart} onConnectEnd={onConnectEnd}
          isValidConnection={isValidConnection}
          onEdgeUpdate={onEdgeUpdate} onEdgeUpdateStart={onEdgeUpdateStart} onEdgeUpdateEnd={onEdgeUpdateEnd}
          edgeUpdaterRadius={10} elevateEdgesOnSelect
          onEdgeMouseEnter={(_, e) => onHover(e.id)} onEdgeMouseLeave={() => onHover(null)}
          onNodeContextMenu={nodeMenu} onPaneContextMenu={paneMenu}
          onPaneClick={() => { select([]); setMenu(null) }}
          nodesDraggable={!readOnly} nodesConnectable={!readOnly} edgesUpdatable={!readOnly} elementsSelectable
          deleteKeyCode={null} selectionKeyCode="Shift" multiSelectionKeyCode={['Meta', 'Control', 'Shift']}
          snapToGrid snapGrid={[10, 10]} minZoom={0.2} maxZoom={2}
          connectionLineType={ConnectionLineType.Bezier} connectionLineStyle={{ stroke: 'rgb(var(--brand-500))', strokeWidth: 2 }}
          connectionRadius={36}
          fitView fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
          proOptions={{ hideAttribution: true }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} className="!bg-gray-50 dark:!bg-gray-950" />
          <Controls showInteractive={false} position="bottom-left" />
          <MiniMap pannable zoomable position="bottom-right" nodeBorderRadius={8} style={selected ? { marginRight: 432 } : undefined}
            nodeColor={n => MINIMAP[(n.data as StepData).node.type]} maskColor="rgb(var(--gray-500) / 0.12)" />

          <Panel position="top-left">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className={`rounded-full px-2.5 py-1 text-[11px] font-medium shadow-sm ring-1 ring-inset ${blocking
                ? 'bg-red-50 text-red-700 ring-red-500/20 dark:bg-red-500/10 dark:text-red-300'
                : 'bg-emerald-50 text-emerald-700 ring-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-300'}`}
                title={issueList.map(i => i.message).join('\n')}>
                {blocking ? `${blocking} ${blocking === 1 ? 'thing' : 'things'} to fix` : 'Ready to save'}
              </span>
              {run && (
                <button type="button" onClick={() => setShowRun(v => !v)}
                  className="rounded-full bg-white px-2.5 py-1 text-[11px] font-medium text-gray-700 shadow-sm ring-1 ring-inset ring-gray-200 hover:bg-gray-50 dark:bg-gray-900 dark:text-gray-300 dark:ring-gray-700">
                  {RUN_LABEL[run.status] ?? run.status.toLowerCase()} · {showRun ? 'hide' : 'show'} on canvas
                </button>
              )}
            </div>
          </Panel>

          {/* Clear of the step settings when they are open over the canvas. */}
          <Panel position="top-right" style={selected ? { marginRight: 432 } : undefined}>
            <div className="flex items-center gap-0.5 rounded-lg border border-gray-200 bg-white p-0.5 shadow-sm dark:border-gray-700 dark:bg-gray-900">
              {!readOnly && <>
                <ToolButton label="Add a step (Tab)" onClick={openPickerCentered}><Plus className="h-4 w-4" /></ToolButton>
                <ToolButton label={`Undo (${MOD}+Z)`} disabled={!history.canUndo} onClick={history.undo}><Undo2 className="h-4 w-4" /></ToolButton>
                <ToolButton label={`Redo (${MOD}+Shift+Z)`} disabled={!history.canRedo} onClick={history.redo}><Redo2 className="h-4 w-4" /></ToolButton>
                <ToolButton label="Tidy up (Shift+T)" onClick={tidyUp}><Sparkles className="h-4 w-4" /></ToolButton>
              </>}
              <ToolButton label="Keyboard shortcuts" onClick={() => setHelp(v => !v)}><Keyboard className="h-4 w-4" /></ToolButton>
              <ToolButton label={fullscreen ? 'Exit full screen (Esc)' : 'Full screen'} onClick={() => onFullscreen(!fullscreen)}>
                {fullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
              </ToolButton>
            </div>
            {help && <Shortcuts />}
          </Panel>

          {graph.nodes.length === 1 && !readOnly && (
            <Panel position="top-center">
              <p className="mt-10 rounded-lg bg-white/90 px-3 py-2 text-xs text-gray-600 shadow-sm dark:bg-gray-900/90 dark:text-gray-300">
                Press the + next to Start, or drag a step in from the left.
              </p>
            </Panel>
          )}
        </ReactFlow>
        {/* Over the canvas rather than beside it: resizing the canvas under the pointer would turn
            the click that opened it into a click on empty canvas. */}
        {selected && (
        <CanvasInspector key={selected.id} node={selected} graph={graph} agents={agents} tools={tools} workflows={workflows} readOnly={readOnly}
          issues={agentIssues.get(selected.id) ?? []} step={runInfo?.steps.get(selected.id)} runSteps={runInfo?.steps} focusNote={focusNote} askJizza={askJizza === selected.id} onDirty={onDirty}
          onSave={edited => { unsaved.current = false; commit(applyStepEdit(graph, edited, selected.id)); select([]) }}
          onClose={() => { unsaved.current = false; select([]) }} />
        )}

      </div>

      {picker && (
        <StepPicker at={picker.at} onClose={() => setPicker(null)} onPick={pick} agents={agents}
          title={picker.kind === 'wire' ? 'Insert a step' : picker.kind === 'after' ? 'Add the next step' : 'Add a step'}
          allowEnd={picker.kind !== 'wire'} />
      )}
      {menu && <ContextMenu at={menu.at} items={menu.items} onClose={() => setMenu(null)} />}
    </div>
  )
}

/** Applies React Flow's select/deselect changes to a list of selected ids. */
function applySelection(prev: string[], changes: Array<{ id: string; selected: boolean }>): string[] {
  const next = new Set(prev)
  for (const c of changes) {
    if (c.selected) next.add(c.id)
    else next.delete(c.id)
  }
  return next.size === prev.length && prev.every(id => next.has(id)) ? prev : [...next]
}

const MINIMAP: Record<NodeType, string> = {
  TRIGGER: '#111827', AGENT_STEP: 'rgb(var(--brand-500))', HUMAN_INTERVENTION: '#f59e0b', CONNECTOR_TOOL: '#0284c7',
  TRANSFORM: '#7c3aed', BRANCH: '#059669', END: '#9ca3af',
  PARALLEL: '#0d9488', JOIN: '#0f766e', WAIT: '#f97316', SUB_WORKFLOW: '#4f46e5', FOR_EACH: '#c026d3',
  APPROVAL: '#d97706', HTTP_REQUEST: '#0891b2', FILTER: '#65a30d', SWITCH: '#15803d', SET_VARIABLE: '#9333ea', STORAGE: '#475569', NOTIFY: '#f43f5e',
}

const RUN_LABEL: Record<string, string> = {
  RUNNING: 'Running', PAUSED_WAITING_INSTRUCTION: 'Waiting for you', WAITING: 'Waiting', DONE: 'Last run done', FAILED: 'Last run failed', CANCELLED: 'Last run cancelled',
}

/** Turns a run into what the canvas shows: each step's state, its output, and which steps ran. */
function runState(run: RunDto, startId?: string) {
  const steps = new Map<string, RunStepDto>()
  for (const s of run.steps ?? []) steps.set(s.nodeId, s)
  const nodes = new Map<string, StepRun>()
  for (const [id, s] of steps) nodes.set(id, s.status === 'DONE' ? 'done' : s.status === 'FAILED' ? 'failed' : 'skipped')
  if (run.currentNode && run.status === 'RUNNING') nodes.set(run.currentNode, 'running')
  if (run.currentNode && (run.status === 'PAUSED_WAITING_INSTRUCTION' || run.status === 'WAITING')) nodes.set(run.currentNode, 'waiting')
  const ran = new Set([...steps].filter(([, s]) => s.status === 'DONE').map(([id]) => id))
  // Start may have no step of its own; it ran if the run started.
  if (startId) { ran.add(startId); if (!nodes.has(startId)) nodes.set(startId, 'done') }
  return { steps, nodes, ran, live: run.status === 'RUNNING' }
}

function ToolButton({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} disabled={disabled} onClick={onClick}
      className="grid h-7 w-7 place-items-center rounded-md text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:opacity-35 disabled:hover:bg-transparent dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-gray-100">
      {children}
    </button>
  )
}

function Shortcuts() {
  const rows: Array<[string, string]> = [
    ['Add a step', 'Tab'], ['Connect', 'Drag from a dot to a step'], ['Reconnect', 'Click a wire, drag its end'],
    ['Delete', 'Del / Backspace'], ['Undo / redo', `${MOD}+Z / ${MOD}+Shift+Z`], ['Copy / paste', `${MOD}+C / ${MOD}+V`],
    ['Duplicate', `${MOD}+D`], ['Select all', `${MOD}+A`], ['Select an area', 'Shift+drag'], ['Tidy up', 'Shift+T'], ['Fit to screen', '1'],
  ]
  return (
    <div className="mt-1.5 w-72 rounded-xl border border-gray-200 bg-white p-2.5 text-xs shadow-lg dark:border-gray-700 dark:bg-gray-900">
      {rows.map(([what, how]) => (
        <div key={what} className="flex justify-between gap-3 py-0.5">
          <span className="text-gray-600 dark:text-gray-400">{what}</span>
          <kbd className="text-right font-sans text-gray-900 dark:text-gray-100">{how}</kbd>
        </div>
      ))}
    </div>
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
