import { api } from '@/lib/api'
import type { ConnectionDto, GeneratedToolDto, RunDto, RunStepDto } from '@/types'
import type { StepRun } from './canvasParts'
import type { ToolOption } from './CanvasInspector'

export const RUN_LABEL: Record<string, string> = {
  RUNNING: 'Running', PAUSED_WAITING_INSTRUCTION: 'Waiting for you', WAITING: 'Waiting', DONE: 'Last run done', FAILED: 'Last run failed', CANCELLED: 'Last run cancelled',
}

/** Turns a run into what the builder shows: each step's state, its output, and which steps ran. */
export function runState(run: RunDto, startId?: string) {
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

/** One state for a folded group from its steps' states: a failure or a wait shows first. */
export function groupRun(states: Array<StepRun | undefined>): StepRun | undefined {
  const s = states.filter((x): x is StepRun => !!x)
  if (!s.length) return undefined
  for (const k of ['failed', 'running', 'waiting'] as const) if (s.includes(k)) return k
  if (s.every(x => x === 'skipped')) return 'skipped'
  return 'done'
}

/** Every service action the user can put in a workflow. Without connector access the list is empty. */
export async function loadTools(): Promise<ToolOption[]> {
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
