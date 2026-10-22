import type { RunTrace, TraceStep } from '@/types'

export type StepState = 'done' | 'failed' | 'skipped' | 'waiting' | 'unreached'

export interface MapNode { id: string; label: string; type: string; col: number; row: number; state: StepState }

/** How each node of the graph fared in this run. */
export function stateOf(trace: RunTrace, nodeId: string): StepState {
  if (trace.waitingOn === nodeId) return 'waiting'
  const step = trace.steps.find(s => s.nodeId === nodeId)
  if (!step) return 'unreached'
  return step.status === 'DONE' ? 'done' : step.status === 'FAILED' ? 'failed' : 'skipped'
}

/**
 * Lays the workflow out left to right: each node's column is its longest path from a start node,
 * so parallel paths sit side by side and a join lands after the slowest of them.
 */
export function layoutMap(trace: RunTrace): { nodes: MapNode[]; cols: number; rows: number } {
  const ids = trace.nodes.map(n => n.id)
  const incoming = new Map<string, string[]>(ids.map(id => [id, []]))
  trace.edges.forEach(e => incoming.get(e.to)?.push(e.from))

  const depth = new Map<string, number>()
  const visiting = new Set<string>()
  const depthOf = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    if (visiting.has(id)) return 0 // a cycle; graphs are validated acyclic, so this is defensive
    visiting.add(id)
    const parents = incoming.get(id) ?? []
    const d = parents.length === 0 ? 0 : Math.max(...parents.map(p => depthOf(p) + 1))
    visiting.delete(id)
    depth.set(id, d)
    return d
  }
  ids.forEach(depthOf)

  const perCol = new Map<number, number>()
  const nodes = trace.nodes.map(n => {
    const col = depth.get(n.id) ?? 0
    const row = perCol.get(col) ?? 0
    perCol.set(col, row + 1)
    return { id: n.id, label: n.label, type: n.type, col, row, state: stateOf(trace, n.id) }
  })
  return {
    nodes,
    cols: Math.max(1, ...nodes.map(n => n.col + 1)),
    rows: Math.max(1, ...[...perCol.values()]),
  }
}

/** Where a step's bar sits on the run's own clock, as fractions of the whole run. */
export function waterfall(step: TraceStep, runStart: number, runEnd: number): { left: number; width: number } {
  const span = Math.max(1, runEnd - runStart)
  const start = step.startedAt ? new Date(step.startedAt).getTime() : runStart
  const end = step.finishedAt ? new Date(step.finishedAt).getTime() : start
  const left = Math.min(1, Math.max(0, (start - runStart) / span))
  const width = Math.max(0, Math.min(1 - left, (end - start) / span))
  return { left, width }
}
