import { describe, expect, it } from 'vitest'
import type { ProcedureSummary, WorkflowDto } from '@/types'
import { changesBetween, stateOf, stepsOf } from './procedureModel'

const graph = {
  nodes: [
    { id: 'start', type: 'TRIGGER', label: 'Start' },
    { id: 'fetch', type: 'CONNECTOR_TOOL', label: 'Get invoices', config: { toolLabel: 'List invoices' } },
    { id: 'check', type: 'BRANCH', label: 'Any unpaid?', config: { condition: 'nodes.fetch.count > 0' } },
    { id: 'tell', type: 'NOTIFY', label: 'Tell me', config: { message: 'Unpaid: {{ nodes.fetch.count }}' } },
    { id: 'done', type: 'END', label: 'End' },
  ],
  edges: [
    { from: 'start', to: 'fetch', branch: null },
    { from: 'fetch', to: 'check', branch: null },
    { from: 'check', to: 'tell', branch: 'true' },
    { from: 'check', to: 'done', branch: 'false' },
  ],
}

const summary = (over: Partial<WorkflowDto>, rest: Partial<ProcedureSummary> = {}): ProcedureSummary => ({
  workflow: { id: 'w', name: 'Bills', graph, origin: 'AGENT_AUTHORED', status: 'PENDING_REVIEW', version: 1, createdAt: '', ...over },
  disabled: false, sourceTaskTitle: 'Monthly bills', ...rest,
})

describe('stepsOf', () => {
  it('lists the steps in run order with plain names and the way a branch leads', () => {
    const steps = stepsOf(graph)
    expect(steps.map(s => s.id)).toEqual(['start', 'fetch', 'check', 'tell', 'done'])
    expect(steps[1]).toMatchObject({ kind: 'Service action', label: 'Get invoices', detail: 'List invoices' })
    expect(steps[2].detail).toBe('nodes.fetch.count > 0')
    expect(steps[3].when).toBe('If yes')
    expect(steps[4].when).toBe('If no')
    expect(steps[0].label).toBe('')
  })
})

describe('stateOf', () => {
  it('says a proposal needs review and nothing runs yet', () => {
    expect(stateOf(summary({})).state).toBe('review')
  })
  it('says an approved, bound procedure is in use by its task', () => {
    const info = stateOf(summary({ status: 'APPROVED' }, { boundVersion: 1 }))
    expect(info.badge).toBe('In use')
    expect(info.explain).toContain('"Monthly bills"')
  })
  it('treats rejected and turned-off procedures as not in use', () => {
    expect(stateOf(summary({ status: 'REJECTED' })).state).toBe('off')
    expect(stateOf(summary({ status: 'APPROVED' }, { disabled: true })).badge).toBe('Turned off')
  })
})

describe('changesBetween', () => {
  it('names the steps added, removed and changed', () => {
    const next = {
      nodes: [
        graph.nodes[0], graph.nodes[1],
        { ...graph.nodes[2], config: { condition: 'nodes.fetch.count > 5' } },
        { id: 'wait', type: 'WAIT', label: 'Wait a day', config: { duration: 'P1D' } },
      ],
      edges: [],
    }
    const changes = changesBetween(graph, next)
    expect(changes.added).toEqual(['Wait: Wait a day'])
    expect(changes.changed).toEqual(['Branch: Any unpaid?'])
    expect(changes.removed).toEqual(['Notify me: Tell me', 'End'])
  })
})
