/**
 * The canvas builder's model and its translation to and from the engine's workflow graph.
 *
 * Ported from the original Jizza builder (frontend/src/domain/builder.ts). Two changes: node
 * settings are real fields instead of free "key: value" text, and connections can be set
 * explicitly, including a branch's yes and no exits.
 */

export type NodeType = 'TRIGGER' | 'AGENT_STEP' | 'HUMAN_INTERVENTION' | 'CONNECTOR_TOOL' | 'TRANSFORM' | 'BRANCH' | 'END'

export const NODE_W = 200
export const NODE_H = 92
const COL_W = 250
const ROW_H = 130

export interface CanvasNode {
  id: string
  type: NodeType
  label: string
  /** The engine's node config, minus the canvas position. */
  config: Record<string, unknown>
  x: number
  y: number
}

/** {@code branch} is "true" or "false" on a branch node's exits, null otherwise. */
export interface CanvasEdge {
  from: string
  to: string
  branch: 'true' | 'false' | null
}

export interface CanvasGraph {
  nodes: CanvasNode[]
  edges: CanvasEdge[]
}

export const NODE_META: Record<NodeType, { label: string; desc: string; tone: string }> = {
  TRIGGER: { label: 'Start', desc: 'Where the workflow begins.', tone: 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900' },
  AGENT_STEP: { label: 'Ask an agent', desc: 'A specialist does a task.', tone: 'bg-brand-500 text-white' },
  HUMAN_INTERVENTION: { label: 'Ask me', desc: 'Pauses and asks you.', tone: 'bg-amber-500 text-white' },
  CONNECTOR_TOOL: { label: 'Service action', desc: 'Calls a tool on a connected service.', tone: 'bg-sky-600 text-white' },
  TRANSFORM: { label: 'Transform', desc: 'Reshapes data between steps.', tone: 'bg-violet-600 text-white' },
  BRANCH: { label: 'Branch', desc: 'Splits on a yes/no condition.', tone: 'bg-emerald-600 text-white' },
  END: { label: 'End', desc: 'Finishes the run.', tone: 'bg-gray-400 text-white dark:bg-gray-600' },
}

/** What can be dragged from the palette. Start is added once, automatically. */
export const PALETTE: NodeType[] = ['AGENT_STEP', 'HUMAN_INTERVENTION', 'CONNECTOR_TOOL', 'TRANSFORM', 'BRANCH', 'END']

const NODE_TYPES = new Set<string>(Object.keys(NODE_META))

let counter = 0
export function newId(type: NodeType): string {
  counter += 1
  return `${type.toLowerCase()}_${Date.now().toString(36)}${counter}`
}

export function newNode(type: NodeType, x: number, y: number): CanvasNode {
  return { id: type === 'TRIGGER' ? 'start' : newId(type), type, label: NODE_META[type].label, config: {}, x, y }
}

export function emptyGraph(): CanvasGraph {
  return { nodes: [newNode('TRIGGER', 30, 150)], edges: [] }
}

/** Reads an engine graph. Saved canvas positions are used; anything else is laid out left to right. */
export function fromGraph(graph: unknown): CanvasGraph {
  const g = graph as { nodes?: Array<Record<string, unknown>>; edges?: Array<Record<string, unknown>> } | null
  if (!g || !Array.isArray(g.nodes) || g.nodes.length === 0) return emptyGraph()

  const edges: CanvasEdge[] = (Array.isArray(g.edges) ? g.edges : []).map(e => ({
    from: String(e.from), to: String(e.to),
    branch: e.branch === 'true' || e.branch === true ? 'true' : e.branch === 'false' || e.branch === false ? 'false' : null,
  }))
  const layout = autoLayout(g.nodes.map(n => String(n.id)), edges, g.nodes.find(n => n.type === 'TRIGGER')?.id as string | undefined)

  const nodes: CanvasNode[] = g.nodes.map(n => {
    const { ui, ...config } = (n.config ?? {}) as Record<string, unknown> & { ui?: { x?: number; y?: number } }
    const type = (NODE_TYPES.has(String(n.type)) ? n.type : 'TRANSFORM') as NodeType
    const at = layout.get(String(n.id)) ?? { x: 30, y: 150 }
    return {
      id: String(n.id), type, label: typeof n.label === 'string' && n.label ? n.label : NODE_META[type].label,
      config, x: typeof ui?.x === 'number' ? ui.x : at.x, y: typeof ui?.y === 'number' ? ui.y : at.y,
    }
  })
  return { nodes, edges }
}

/** Columns by the longest path from the start (so every edge points right), rows in order. */
function autoLayout(ids: string[], edges: CanvasEdge[], start?: string): Map<string, { x: number; y: number }> {
  const depth = new Map<string, number>()
  if (start) depth.set(start, 0)
  // The graph is acyclic, so relaxing every edge |nodes| times settles the longest paths.
  for (let i = 0; i < ids.length; i++) {
    let changed = false
    for (const e of edges) {
      const d = depth.get(e.from)
      if (d !== undefined && (depth.get(e.to) ?? -1) < d + 1) { depth.set(e.to, d + 1); changed = true }
    }
    if (!changed) break
  }
  let orphan = Math.max(0, ...depth.values()) + 1
  for (const id of ids) if (!depth.has(id)) depth.set(id, orphan++)
  const rows = new Map<number, number>()
  const out = new Map<string, { x: number; y: number }>()
  for (const id of ids) {
    const col = depth.get(id)!
    const row = rows.get(col) ?? 0
    rows.set(col, row + 1)
    out.set(id, { x: 30 + col * COL_W, y: 40 + row * ROW_H })
  }
  return out
}

/** Re-arranges every node left to right ("Tidy up"). */
export function tidy(graph: CanvasGraph): CanvasGraph {
  const at = autoLayout(graph.nodes.map(n => n.id), graph.edges, graph.nodes.find(n => n.type === 'TRIGGER')?.id)
  return { ...graph, nodes: graph.nodes.map(n => ({ ...n, ...(at.get(n.id) ?? {}) })) }
}

/** The nearest spot at or below (x, y) where a new step would not sit on top of another. */
export function freeSpot(graph: CanvasGraph, x: number, y: number): { x: number; y: number } {
  const clashes = (ty: number) => graph.nodes.some(n => Math.abs(n.x - x) < NODE_W && Math.abs(n.y - ty) < NODE_H)
  for (let i = 0; i < 50; i++) {
    for (const ty of i === 0 ? [y] : [y + i * ROW_H, y - i * ROW_H]) if (ty >= 0 && !clashes(ty)) return { x, y: ty }
  }
  return { x, y }
}

/** Puts a new step on an existing connection: from → new → to, keeping the from side's exit. */
export function insertOnEdge(graph: CanvasGraph, edge: CanvasEdge, type: NodeType): { graph: CanvasGraph; id: string } {
  const from = graph.nodes.find(n => n.id === edge.from)
  const to = graph.nodes.find(n => n.id === edge.to)
  const at = freeSpot(graph, from && to ? Math.round((from.x + to.x) / 2) : 0, from && to ? Math.round((from.y + to.y) / 2) + ROW_H : 0)
  const node = newNode(type, at.x, at.y)
  const edges = graph.edges.filter(e => !(e.from === edge.from && e.to === edge.to && e.branch === edge.branch))
  edges.push({ from: edge.from, to: node.id, branch: edge.branch })
  // A branch's onward path leaves from its "yes" exit; everything else has one exit.
  if (type !== 'END') edges.push({ from: node.id, to: edge.to, branch: type === 'BRANCH' ? 'true' : null })
  return { graph: { nodes: [...graph.nodes, node], edges }, id: node.id }
}

/** Adds a step on a given exit of a node, replacing whatever that exit pointed at before. */
export function addAfter(graph: CanvasGraph, from: string, branch: 'true' | 'false' | null, type: NodeType): { graph: CanvasGraph; id: string } {
  const source = graph.nodes.find(n => n.id === from)
  const siblings = graph.edges.filter(e => e.from === from).length
  const at = freeSpot(graph, (source?.x ?? 0) + COL_W, (source?.y ?? 0) + (branch === 'false' ? ROW_H : branch === 'true' && siblings ? -ROW_H : 0))
  const node = newNode(type, at.x, at.y)
  const withNode = { ...graph, nodes: [...graph.nodes, node] }
  return { graph: setNext(withNode, from, branch, node.id), id: node.id }
}

/**
 * Copies nodes (with the connections between them) to new ids, offset so the copy is visible.
 * Start is never copied: a workflow has exactly one.
 */
export function cloneNodes(graph: CanvasGraph, ids: string[], source: CanvasGraph = graph, offset = 40): { graph: CanvasGraph; ids: string[] } {
  const picked = source.nodes.filter(n => ids.includes(n.id) && n.type !== 'TRIGGER')
  const map = new Map(picked.map(n => [n.id, newId(n.type)]))
  const nodes = picked.map(n => ({ ...n, id: map.get(n.id)!, config: structuredClone(n.config), x: n.x + offset, y: n.y + offset }))
  const edges = source.edges.filter(e => map.has(e.from) && map.has(e.to)).map(e => ({ ...e, from: map.get(e.from)!, to: map.get(e.to)! }))
  return { graph: { nodes: [...graph.nodes, ...nodes], edges: [...graph.edges, ...edges] }, ids: nodes.map(n => n.id) }
}

/** Steps further up the flow, whose results a step can read as nodes.<id>. */
export function upstreamOf(graph: CanvasGraph, id: string): CanvasNode[] {
  return graph.nodes.filter(n => n.id !== id && n.type !== 'END' && reaches(graph, n.id, id))
}

/** Writes the engine graph, keeping each node's canvas position in its config. */
export function toGraph(graph: CanvasGraph) {
  return {
    nodes: graph.nodes.map(n => ({ id: n.id, type: n.type, label: n.label, config: { ...n.config, ui: { x: n.x, y: n.y } } })),
    edges: graph.edges.map(e => ({ from: e.from, to: e.to, branch: e.branch })),
  }
}

/** The exits a node has: one, or a yes and a no for a branch. End has none. */
export function exitsOf(type: NodeType): Array<'true' | 'false' | null> {
  if (type === 'END') return []
  if (type === 'BRANCH') return ['true', 'false']
  return [null]
}

/** Points one exit of a node at another node, or clears it. */
export function setNext(graph: CanvasGraph, from: string, branch: 'true' | 'false' | null, to: string | null): CanvasGraph {
  const edges = graph.edges.filter(e => !(e.from === from && e.branch === branch))
  return { ...graph, edges: to ? [...edges, { from, to, branch }] : edges }
}

/**
 * Adds a node and connects it to the flow: from the selected node if it has a free exit,
 * otherwise from the nearest node with one.
 */
export function addNode(graph: CanvasGraph, type: NodeType, x: number, y: number, after?: string | null): { graph: CanvasGraph; id: string } {
  const node = newNode(type, x, y)
  const freeExit = (n: CanvasNode) => exitsOf(n.type).find(b => !graph.edges.some(e => e.from === n.id && e.branch === b))
  const candidates = graph.nodes.filter(n => freeExit(n) !== undefined)
  const chosen = candidates.find(n => n.id === after)
    ?? candidates.sort((a, b) => dist(a, x, y) - dist(b, x, y))[0]
  const edges = chosen ? [...graph.edges, { from: chosen.id, to: node.id, branch: freeExit(chosen)! }] : graph.edges
  return { graph: { nodes: [...graph.nodes, node], edges }, id: node.id }
}

const dist = (n: CanvasNode, x: number, y: number) => (n.x - x) ** 2 + (n.y - y) ** 2

/** Whether {@code to} can be reached from {@code from}; connecting back to it would make a loop. */
export function reaches(graph: CanvasGraph, from: string, to: string): boolean {
  const seen = new Set<string>()
  const stack = [from]
  while (stack.length) {
    const id = stack.pop()!
    if (id === to) return true
    if (seen.has(id)) continue
    seen.add(id)
    for (const e of graph.edges) if (e.from === id) stack.push(e.to)
  }
  return false
}

/** Nodes an exit of {@code from} may point at: anything but Start, itself, or what leads back to it. */
export function targetsFor(graph: CanvasGraph, from: string): CanvasNode[] {
  return graph.nodes.filter(n => n.type !== 'TRIGGER' && n.id !== from && !reaches(graph, n.id, from))
}

export function removeNode(graph: CanvasGraph, id: string): CanvasGraph {
  return { nodes: graph.nodes.filter(n => n.id !== id), edges: graph.edges.filter(e => e.from !== id && e.to !== id) }
}

export function updateNode(graph: CanvasGraph, id: string, patch: Partial<CanvasNode>): CanvasGraph {
  return { ...graph, nodes: graph.nodes.map(n => (n.id === id ? { ...n, ...patch } : n)) }
}

const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '')

/** One thing wrong with the graph, tied to the step it is about when there is one. */
export interface Issue { nodeId?: string; message: string }

/**
 * What would stop the engine accepting this graph, in the user's words, so the canvas can say so
 * before saving and mark the step it is about. The server checks again regardless.
 */
export function issues(graph: CanvasGraph, label: (n: CanvasNode) => string = n => n.label): Issue[] {
  const out: Issue[] = []
  const starts = graph.nodes.filter(n => n.type === 'TRIGGER')
  if (starts.length !== 1) out.push({ message: 'The workflow needs exactly one Start.' })
  if (!graph.nodes.some(n => n.type !== 'TRIGGER' && n.type !== 'END')) out.push({ message: 'Add at least one step after Start.' })
  for (const n of graph.nodes) {
    const name = `"${label(n)}"`
    const add = (message: string) => out.push({ nodeId: n.id, message })
    if (n.type === 'AGENT_STEP' && !text(n.config.agentId)) add(`${name} needs an agent.`)
    if (n.type === 'AGENT_STEP' && !text(n.config.instruction) && !text(n.config.instructionExpr)) add(`${name} needs an instruction.`)
    if (n.type === 'CONNECTOR_TOOL' && !text(n.config.toolId)) add(`${name} needs a service action.`)
    if (n.type === 'TRANSFORM' && !text(n.config.expression)) add(`${name} needs an expression.`)
    if (n.type === 'BRANCH') {
      if (!text(n.config.condition)) add(`${name} needs a condition.`)
      for (const b of ['true', 'false'] as const) {
        if (!graph.edges.some(e => e.from === n.id && e.branch === b)) add(`${name} needs a step for "${b === 'true' ? 'yes' : 'no'}".`)
      }
    }
  }
  return out
}

export function problems(graph: CanvasGraph, label?: (n: CanvasNode) => string): string[] {
  return issues(graph, label).map(i => i.message)
}

/** Steps nothing leads to from Start. They are allowed, but they will never run. */
export function unreachable(graph: CanvasGraph): Set<string> {
  const start = graph.nodes.find(n => n.type === 'TRIGGER')
  return new Set(graph.nodes.filter(n => n.type !== 'TRIGGER' && !(start && reaches(graph, start.id, n.id))).map(n => n.id))
}
