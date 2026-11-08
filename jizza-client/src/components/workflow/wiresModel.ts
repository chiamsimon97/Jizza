/**
 * The Wires view's model: a top-to-bottom layout of the same graph the canvas edits, the wires
 * between steps, and the edits the view makes. Positions here are worked out from the graph each
 * time, so the canvas keeps its own layout untouched.
 */

import {
  addAfter, exitsOf, insertOnEdge, removeNode, setNext, updateNode,
  type CanvasEdge, type CanvasGraph, type ExitKey, type PaletteEntry, type NodeType,
} from './canvasModel'

export const CARD_W = 248
export const CARD_H = 76
const COL = 288
const ROW = 136
const PAD = 56

export interface Box { x: number; y: number }
export type Point = [number, number]

export interface WiresLayout {
  at: Map<string, Box>
  depth: Map<string, number>
  width: number
  height: number
}

/** Identifies a wire by the exit it leaves from; an exit has at most one wire. */
export const wireKey = (e: Pick<CanvasEdge, 'from' | 'branch'>) => `${e.from}:${e.branch ?? 'out'}`

/** Where a step was dragged to in this view, if it was; the canvas keeps its own position. */
export function placedAt(config: Record<string, unknown>): Box | null {
  const at = config.wiresAt as { x?: unknown; y?: unknown } | undefined
  return at && typeof at.x === 'number' && typeof at.y === 'number' ? { x: at.x, y: at.y } : null
}

/** Puts a step where it was dragged, on the 8px grid and clear of the board's top-left edge. */
export function placeStep(graph: CanvasGraph, id: string, at: Box): CanvasGraph {
  const node = graph.nodes.find(n => n.id === id)
  if (!node) return graph
  const snap = (v: number) => Math.max(8, Math.round(v / 8) * 8)
  return updateNode(graph, id, { config: { ...node.config, wiresAt: { x: snap(at.x), y: snap(at.y) } } })
}

/** Whether any step was moved by hand, so lining everything up again would change something. */
export const isArranged = (graph: CanvasGraph) => graph.nodes.some(n => placedAt(n.config) !== null)

/** Forgets every hand-placed position: each step goes back to its place in the automatic layout. */
export function autoAlign(graph: CanvasGraph): CanvasGraph {
  if (!isArranged(graph)) return graph
  return {
    ...graph,
    nodes: graph.nodes.map(n => {
      if (!placedAt(n.config)) return n
      const { wiresAt: _drop, ...config } = n.config
      return { ...n, config }
    }),
  }
}

/**
 * Keeps what this view stores on a step (where it was moved to, its wires' labels) when the step's
 * settings are saved from a copy taken before those changed.
 */
export function keepWiresSettings(saved: CanvasGraph, current: CanvasGraph, id: string): CanvasGraph {
  const now = current.nodes.find(n => n.id === id)?.config
  const node = saved.nodes.find(n => n.id === id)
  if (!now || !node) return saved
  const { wiresAt: _at, wireLabels: _labels, ...config } = node.config
  return updateNode(saved, id, {
    config: { ...config, ...(now.wiresAt ? { wiresAt: now.wiresAt } : {}), ...(now.wireLabels ? { wireLabels: now.wireLabels } : {}) },
  })
}

/**
 * Lays the graph out top to bottom: one row per step along the longest path from Start, each step
 * centred over the steps it leads to, children in exit order (yes before no). Steps nothing leads
 * to are laid out the same way to the right of the main flow. A step moved by hand stays where it
 * was put.
 */
export function wiresLayout(graph: CanvasGraph): WiresLayout {
  const ids = graph.nodes.map(n => n.id)
  const byId = new Map(graph.nodes.map(n => [n.id, n]))
  const edges = graph.edges.filter(e => byId.has(e.from) && byId.has(e.to) && e.from !== e.to)
  const hasIn = new Set(edges.map(e => e.to))
  const start = graph.nodes.find(n => n.type === 'TRIGGER')
  const roots = [...(start ? [start.id] : []), ...ids.filter(id => id !== start?.id && !hasIn.has(id))]

  const depth = new Map<string, number>(roots.map(r => [r, 0]))
  // The graph is acyclic, so relaxing every edge |nodes| times settles the longest paths.
  for (let i = 0; i < ids.length; i++) {
    let changed = false
    for (const e of edges) {
      const d = depth.get(e.from)
      if (d !== undefined && (depth.get(e.to) ?? -1) < d + 1) { depth.set(e.to, d + 1); changed = true }
    }
    if (!changed) break
  }
  for (const id of ids) if (!depth.has(id)) { depth.set(id, 0); roots.push(id) }

  // A spanning tree: each step hangs off one step a row above it, in that step's exit order.
  const rank = (e: CanvasEdge) => Math.max(0, exitsOf(byId.get(e.from)!).indexOf(e.branch))
  const children = new Map<string, string[]>()
  const placed = new Set(roots)
  for (const e of [...edges].sort((a, b) => rank(a) - rank(b))) {
    if (placed.has(e.to) || depth.get(e.to) !== depth.get(e.from)! + 1) continue
    placed.add(e.to)
    children.set(e.from, [...(children.get(e.from) ?? []), e.to])
  }
  for (const id of ids) if (!placed.has(id)) { placed.add(id); roots.push(id) }

  const col = new Map<string, number>()
  let cursor = 0
  const walk = (id: string): number => {
    const kids = children.get(id) ?? []
    let c: number
    if (kids.length === 0) {
      c = cursor
      cursor += 1
    } else {
      const cols = kids.map(walk)
      c = (cols[0] + cols[cols.length - 1]) / 2
    }
    col.set(id, c)
    return c
  }
  roots.forEach((r, i) => { if (i > 0) cursor += 0.25; walk(r) })

  const at = new Map<string, Box>()
  let right = 0
  let bottom = 0
  for (const id of ids) {
    const box = placedAt(byId.get(id)!.config) ?? { x: Math.round(PAD + (col.get(id) ?? 0) * COL), y: PAD + (depth.get(id) ?? 0) * ROW }
    right = Math.max(right, box.x + CARD_W)
    bottom = Math.max(bottom, box.y + CARD_H)
    at.set(id, box)
  }
  // Room past the furthest step for wires that go round the side, and to drag a step further out.
  return { at, depth, width: Math.round(right + PAD + CARD_W / 2), height: bottom + PAD + 40 }
}

/** Where an exit's socket sits: spread along the bottom of the card, first exit on the left. */
export function exitPoint(exits: ExitKey[], box: Box, branch: ExitKey): Point {
  const i = Math.max(0, exits.indexOf(branch))
  return [Math.round(box.x + (CARD_W * (i + 1)) / (exits.length + 1)), box.y + CARD_H]
}

/** Where wires come into a step: the middle of its top edge. */
export const inPoint = (box: Box): Point => [box.x + CARD_W / 2, box.y]

export interface Route {
  pts: Point[]
  /** The SVG path, with rounded corners. */
  d: string
  /** Where the wire's toolbar and label go. */
  mid: Point
}

/**
 * A circuit-style wire: down out of the exit, across, and down into the step. A wire to the next row
 * turns just below its exit; one that skips rows turns just above the step it goes to, so it runs
 * alongside the steps it passes.
 */
export function route(a: Point, b: Point, nextRow: boolean): Route {
  const [x1, y1] = a
  const [x2, y2] = b
  let pts: Point[]
  let mid: Point
  if (y2 > y1 + 20) {
    // Steps moved close together still get a wire that only runs downwards.
    const ym = nextRow ? y1 + Math.min(22, (y2 - y1) / 2) : Math.max((y1 + y2) / 2, y2 - 24)
    if (Math.abs(x1 - x2) < 1) {
      pts = [a, b]
      mid = [x1, Math.round((y1 + y2) / 2)]
    } else {
      pts = [a, [x1, ym], [x2, ym], b]
      mid = [Math.round((x1 + x2) / 2), ym]
    }
  } else {
    // Only a wire drawn back up the flow lands here; it goes round the side.
    const side = Math.max(x1, x2) + CARD_W / 2 + 40
    pts = [a, [x1, y1 + 18], [side, y1 + 18], [side, y2 - 22], [x2, y2 - 22], b]
    mid = [side, Math.round((y1 + y2) / 2)]
  }
  return { pts, d: rounded(pts), mid }
}

function rounded(pts: Point[], r = 10): string {
  let d = `M${pts[0][0]} ${pts[0][1]}`
  for (let i = 1; i < pts.length - 1; i++) {
    const [p, c, n] = [pts[i - 1], pts[i], pts[i + 1]]
    const l1 = Math.hypot(c[0] - p[0], c[1] - p[1])
    const l2 = Math.hypot(n[0] - c[0], n[1] - c[1])
    if (l1 < 1 || l2 < 1) continue
    const rr = Math.min(r, l1 / 2, l2 / 2)
    const ax = c[0] - ((c[0] - p[0]) / l1) * rr
    const ay = c[1] - ((c[1] - p[1]) / l1) * rr
    const bx = c[0] + ((n[0] - c[0]) / l2) * rr
    const by = c[1] + ((n[1] - c[1]) / l2) * rr
    d += ` L${ax} ${ay} Q${c[0]} ${c[1]} ${bx} ${by}`
  }
  const z = pts[pts.length - 1]
  return `${d} L${z[0]} ${z[1]}`
}

/** How far a point is from a wire, for picking the wire a step is dropped on. */
export function distanceTo(pts: Point[], x: number, y: number): number {
  let best = Infinity
  for (let i = 0; i < pts.length - 1; i++) {
    const [ax, ay] = pts[i]
    const [bx, by] = pts[i + 1]
    const dx = bx - ax
    const dy = by - ay
    const len = dx * dx + dy * dy
    const t = len ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len)) : 0
    best = Math.min(best, Math.hypot(x - (ax + t * dx), y - (ay + t * dy)))
  }
  return best
}

/**
 * Adds a step after another, as dropping onto it does: on its first free exit, or, when every exit
 * is taken, between it and what follows its first exit. Null for a step nothing can follow (End).
 */
export function dropOnStep(graph: CanvasGraph, id: string, entry: NodeType | PaletteEntry): { graph: CanvasGraph; id: string } | null {
  const node = graph.nodes.find(n => n.id === id)
  if (!node) return null
  const exits = exitsOf(node)
  if (exits.length === 0) return null
  const free = exits.find(b => !graph.edges.some(e => e.from === id && e.branch === b))
  if (free !== undefined) return addAfter(graph, id, free, entry)
  const edge = graph.edges.find(e => e.from === id && e.branch === exits[0])!
  return insertOnEdge(graph, edge, entry)
}

/**
 * Deletes a step. A step in a straight line (one wire in, one out) is stepped over, so the steps
 * either side of it stay joined.
 */
export function deleteStep(graph: CanvasGraph, id: string): { graph: CanvasGraph; joined: boolean } {
  const node = graph.nodes.find(n => n.id === id)
  if (!node || node.type === 'TRIGGER') return { graph, joined: false }
  const ins = graph.edges.filter(e => e.to === id)
  const outs = graph.edges.filter(e => e.from === id)
  const next = removeNode(graph, id)
  if (ins.length === 1 && outs.length === 1 && exitsOf(node).length === 1) {
    return { graph: setNext(next, ins[0].from, ins[0].branch, outs[0].to), joined: true }
  }
  return { graph: next, joined: false }
}

/** A wire's label, kept in the settings of the step it leaves, by exit. */
export function wireLabel(graph: CanvasGraph, e: Pick<CanvasEdge, 'from' | 'branch'>): string {
  const labels = graph.nodes.find(n => n.id === e.from)?.config.wireLabels
  if (!labels || typeof labels !== 'object' || Array.isArray(labels)) return ''
  const v = (labels as Record<string, unknown>)[e.branch ?? 'out']
  return typeof v === 'string' ? v : ''
}

export function setWireLabel(graph: CanvasGraph, e: Pick<CanvasEdge, 'from' | 'branch'>, text: string): CanvasGraph {
  const node = graph.nodes.find(n => n.id === e.from)
  if (!node) return graph
  const old = node.config.wireLabels
  const labels: Record<string, unknown> = old && typeof old === 'object' && !Array.isArray(old) ? { ...(old as Record<string, unknown>) } : {}
  const key = e.branch ?? 'out'
  if (text.trim()) labels[key] = text.trim()
  else delete labels[key]
  const { wireLabels: _drop, ...rest } = node.config
  return updateNode(graph, e.from, { config: Object.keys(labels).length ? { ...rest, wireLabels: labels } : rest })
}

/**
 * The path a preview walks, without running anything: from Start, taking "yes" at a branch,
 * "approved" at an approval and the first path of a paths step, and every lane of a parallel step.
 * {@code step} is how many hops from Start each step is; {@code edges} are the wires taken.
 */
export function previewPath(graph: CanvasGraph): { step: Map<string, number>; edges: Map<string, number>; last: number } {
  const byId = new Map(graph.nodes.map(n => [n.id, n]))
  const start = graph.nodes.find(n => n.type === 'TRIGGER')
  const step = new Map<string, number>()
  const edges = new Map<string, number>()
  if (!start) return { step, edges, last: 0 }
  step.set(start.id, 0)
  const queue = [start.id]
  let last = 0
  while (queue.length) {
    const id = queue.shift()!
    const node = byId.get(id)!
    const exits = exitsOf(node)
    const taken = node.type === 'PARALLEL' ? exits : exits.slice(0, 1)
    for (const e of graph.edges) {
      if (e.from !== id || !taken.includes(e.branch) || !byId.has(e.to) || step.has(e.to)) continue
      const d = step.get(id)! + 1
      step.set(e.to, d)
      edges.set(wireKey(e), d)
      last = Math.max(last, d)
      queue.push(e.to)
    }
  }
  return { step, edges, last }
}

/** Run order numbers for the steps, as a breadth-first walk from Start. */
export function stepNumbers(graph: CanvasGraph): Map<string, number> {
  const start = graph.nodes.find(n => n.type === 'TRIGGER')
  const out = new Map<string, number>()
  if (!start) return out
  out.set(start.id, 1)
  const queue = [start.id]
  while (queue.length) {
    const id = queue.shift()!
    for (const e of graph.edges) {
      if (e.from === id && !out.has(e.to)) { out.set(e.to, out.size + 1); queue.push(e.to) }
    }
  }
  return out
}
