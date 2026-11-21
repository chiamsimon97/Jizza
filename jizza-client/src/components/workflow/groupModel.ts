/**
 * Step groups on the workflow canvas: a titled box around some steps that can be folded into one
 * card and locked. A locked group keeps its steps, their settings, their arrangement and the wires
 * between them as they are until it is unlocked; wires into and out of the group stay editable, so
 * the group can still be connected to the rest of the workflow.
 *
 * Groups are only a picture of the workflow: the engine runs grouped steps like any others. They
 * are saved with the graph as {@code groups} beside the nodes and edges.
 */

import type { CanvasEdge, CanvasGraph, CanvasNode } from './canvasModel'

export interface StepGroup {
  id: string
  title: string
  /** The steps in the group. A step is in one group at most, and Start is never in one. */
  nodes: string[]
  locked: boolean
  /** Folded into one card on the canvas. */
  collapsed: boolean
}

export function newGroupId(): string {
  return `grp_${Math.random().toString(36).slice(2, 8)}`
}

/** Reads saved groups, keeping only steps that exist, each in one group, and dropping empty groups. */
export function readGroups(raw: unknown, nodes: CanvasNode[]): StepGroup[] {
  if (!Array.isArray(raw)) return []
  const ids = new Set(nodes.filter(n => n.type !== 'TRIGGER').map(n => n.id))
  const taken = new Set<string>()
  const seen = new Set<string>()
  const out: StepGroup[] = []
  for (const g of raw as Array<Record<string, unknown>>) {
    if (!g || typeof g !== 'object') continue
    const id = typeof g.id === 'string' && g.id && !seen.has(g.id) ? g.id : newGroupId()
    const members = (Array.isArray(g.nodes) ? g.nodes : []).map(String).filter(n => ids.has(n) && !taken.has(n))
    if (!members.length) continue
    members.forEach(n => taken.add(n))
    seen.add(id)
    out.push({
      id, nodes: members,
      title: typeof g.title === 'string' ? g.title : '',
      locked: g.locked === true,
      collapsed: g.collapsed === true,
    })
  }
  return out
}

/** The groups to save, or nothing when there are none (so older graphs stay as they were). */
export function writeGroups(graph: CanvasGraph): { groups?: StepGroup[] } {
  const groups = readGroups(graph.groups, graph.nodes)
  return groups.length ? { groups } : {}
}

export function groupsOf(graph: CanvasGraph): StepGroup[] {
  return graph.groups ?? []
}

export function groupOfNode(graph: CanvasGraph, nodeId: string): StepGroup | undefined {
  return groupsOf(graph).find(g => g.nodes.includes(nodeId))
}

/** The locked group a step is in, if any. */
export function lockedGroupOf(graph: CanvasGraph, nodeId: string): StepGroup | undefined {
  const g = groupOfNode(graph, nodeId)
  return g?.locked ? g : undefined
}

/** Whether a wire runs between two steps of the same locked group, so it can't be changed. */
export function isLockedEdge(graph: CanvasGraph, edge: Pick<CanvasEdge, 'from' | 'to'>): boolean {
  const g = lockedGroupOf(graph, edge.from)
  return !!g && g.nodes.includes(edge.to)
}

/** A title for a new group that no other group has yet: "Group 1", "Group 2", … */
export function nextGroupTitle(graph: CanvasGraph): string {
  const titles = new Set(groupsOf(graph).map(g => g.title))
  for (let i = 1; ; i++) if (!titles.has(`Group ${i}`)) return `Group ${i}`
}

/**
 * Puts steps in a new group. Start, and steps in a locked group, are left out; steps in another
 * (unlocked) group move to the new one. Null when nothing is left to group.
 */
export function createGroup(graph: CanvasGraph, ids: string[], title = nextGroupTitle(graph)): { graph: CanvasGraph; id: string } | null {
  const byId = new Map(graph.nodes.map(n => [n.id, n]))
  const picked = [...new Set(ids)].filter(id => byId.has(id) && byId.get(id)!.type !== 'TRIGGER' && !lockedGroupOf(graph, id))
  if (!picked.length) return null
  // Keep the steps in the order they sit on the canvas, left to right, so the group reads in order.
  picked.sort((a, b) => byId.get(a)!.x - byId.get(b)!.x || byId.get(a)!.y - byId.get(b)!.y)
  const id = newGroupId()
  const others = groupsOf(graph).map(g => ({ ...g, nodes: g.nodes.filter(n => !picked.includes(n)) })).filter(g => g.nodes.length)
  return { graph: { ...graph, groups: [...others, { id, title, nodes: picked, locked: false, collapsed: false }] }, id }
}

export function updateGroup(graph: CanvasGraph, id: string, patch: Partial<Omit<StepGroup, 'id' | 'nodes'>>): CanvasGraph {
  return { ...graph, groups: groupsOf(graph).map(g => (g.id === id ? { ...g, ...patch } : g)) }
}

/** Removes the box; the steps stay where they are. A locked group has to be unlocked first. */
export function ungroup(graph: CanvasGraph, id: string): CanvasGraph {
  if (groupsOf(graph).find(g => g.id === id)?.locked) return graph
  return { ...graph, groups: groupsOf(graph).filter(g => g.id !== id) }
}

/** Takes a step out of its group (not out of a locked one). */
export function removeFromGroup(graph: CanvasGraph, nodeId: string): CanvasGraph {
  const g = groupOfNode(graph, nodeId)
  if (!g || g.locked) return graph
  return {
    ...graph,
    groups: groupsOf(graph).map(x => (x.id === g.id ? { ...x, nodes: x.nodes.filter(n => n !== nodeId) } : x)).filter(x => x.nodes.length),
  }
}

/** Moves every step of a group by the same amount, keeping its arrangement. */
export function moveGroup(graph: CanvasGraph, id: string, dx: number, dy: number): CanvasGraph {
  const g = groupsOf(graph).find(x => x.id === id)
  if (!g || (!dx && !dy)) return graph
  return { ...graph, nodes: graph.nodes.map(n => (g.nodes.includes(n.id) ? { ...n, x: Math.round(n.x + dx), y: Math.round(n.y + dy) } : n)) }
}

/**
 * Puts the steps of each locked group back in their arrangement after a change that moved some of
 * them on their own (Tidy up, Auto arrange): the group's first step that moved leads, and the rest
 * keep their places around it.
 */
export function keepLockedArrangement(before: CanvasGraph, after: CanvasGraph): CanvasGraph {
  const was = new Map(before.nodes.map(n => [n.id, n]))
  let next = after
  for (const g of groupsOf(before)) {
    if (!g.locked) continue
    const now = new Map(next.nodes.map(n => [n.id, n]))
    const lead = g.nodes.find(id => {
      const a = was.get(id); const b = now.get(id)
      return a && b && (a.x !== b.x || a.y !== b.y)
    })
    if (!lead) continue
    const dx = now.get(lead)!.x - was.get(lead)!.x
    const dy = now.get(lead)!.y - was.get(lead)!.y
    next = {
      ...next,
      nodes: next.nodes.map(n => {
        const a = was.get(n.id)
        return g.nodes.includes(n.id) && a ? { ...n, x: a.x + dx, y: a.y + dy } : n
      }),
    }
  }
  return next
}

const edgeKey = (e: CanvasEdge) => `${e.from}>${e.branch ?? ''}>${e.to}`
/** A step's settings, without where the Wires view placed it or how it labels its wires. */
const stepKey = (n: CanvasNode) => {
  const { wiresAt: _at, wireLabels: _labels, ...config } = n.config
  return JSON.stringify([n.type, n.label, config])
}

/**
 * Why a change may not be made because it touches a locked group, or null when it may. A locked
 * group must keep its steps, their settings, and the wires between them; it may be unlocked, folded,
 * renamed or moved as a whole. Wires into and out of the group may change.
 */
export function lockedChange(before: CanvasGraph, after: CanvasGraph): string | null {
  const prevNodes = new Map(before.nodes.map(n => [n.id, n]))
  const nextNodes = new Map(after.nodes.map(n => [n.id, n]))
  for (const g of groupsOf(before)) {
    if (!g.locked) continue
    const name = g.title.trim() ? `“${g.title.trim()}”` : 'this group'
    const now = groupsOf(after).find(x => x.id === g.id)
    if (!now || now.nodes.length !== g.nodes.length || g.nodes.some(n => !now.nodes.includes(n))) {
      return `The steps in ${name} are locked. Unlock the group to change what is in it.`
    }
    if (!now.locked) continue
    for (const id of g.nodes) {
      const a = prevNodes.get(id); const b = nextNodes.get(id)
      if (!b) return `The steps in ${name} are locked. Unlock the group to delete one.`
      if (a && stepKey(a) !== stepKey(b)) return `The steps in ${name} are locked. Unlock the group to change their settings.`
    }
    const inside = (e: CanvasEdge) => g.nodes.includes(e.from) && g.nodes.includes(e.to)
    const was = new Set(before.edges.filter(inside).map(edgeKey))
    const is = new Set(after.edges.filter(inside).map(edgeKey))
    if (was.size !== is.size || [...was].some(k => !is.has(k))) {
      return `The wiring inside ${name} is locked. Unlock the group to change it.`
    }
  }
  return null
}

/** The box around a group's steps on the canvas, with room for its title bar. */
export const GROUP_PAD = 20
export const GROUP_HEADER = 34
export const GROUP_FOLDED_W = 240
export const GROUP_FOLDED_H = 76

export function groupBounds(
  graph: CanvasGraph, group: StepGroup, sizeOf: (n: CanvasNode) => { width: number; height: number },
): { x: number; y: number; width: number; height: number } | null {
  const members = graph.nodes.filter(n => group.nodes.includes(n.id))
  if (!members.length) return null
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const n of members) {
    const s = sizeOf(n)
    minX = Math.min(minX, n.x); minY = Math.min(minY, n.y)
    maxX = Math.max(maxX, n.x + s.width); maxY = Math.max(maxY, n.y + s.height)
  }
  const x = minX - GROUP_PAD
  const y = minY - GROUP_PAD - GROUP_HEADER
  if (group.collapsed) return { x, y, width: GROUP_FOLDED_W, height: GROUP_FOLDED_H }
  return { x, y, width: maxX - minX + GROUP_PAD * 2, height: maxY - minY + GROUP_PAD * 2 + GROUP_HEADER }
}

/**
 * The graph as it looks with some groups folded: each folded group becomes one stand-in step under
 * the group's id, wires inside it go, and wires into and out of its steps meet the stand-in. Used to
 * lay out the Wires view; {@code into} says which group each hidden step went into.
 */
export function foldedGraph(graph: CanvasGraph, folded: (groupId: string) => boolean): { graph: CanvasGraph; into: Map<string, string> } {
  const into = new Map<string, string>()
  const standIns: CanvasNode[] = []
  for (const g of groupsOf(graph)) {
    if (!folded(g.id)) continue
    const members = graph.nodes.filter(n => g.nodes.includes(n.id))
    if (!members.length) continue
    members.forEach(n => into.set(n.id, g.id))
    const first = members[0]
    standIns.push({
      id: g.id, type: 'TRANSFORM', label: g.title, x: first.x, y: first.y,
      config: first.config.wiresAt ? { wiresAt: first.config.wiresAt } : {},
    })
  }
  if (!into.size) return { graph, into }
  const seen = new Set<string>()
  const edges: CanvasEdge[] = []
  for (const e of graph.edges) {
    const from = into.get(e.from) ?? e.from
    const to = into.get(e.to) ?? e.to
    if (from === to) continue
    const branch = into.has(e.from) ? null : e.branch
    const key = `${from}>${branch ?? ''}>${to}`
    if (seen.has(key)) continue
    seen.add(key)
    edges.push({ from, to, branch })
  }
  return { graph: { ...graph, nodes: [...graph.nodes.filter(n => !into.has(n.id)), ...standIns], edges }, into }
}
