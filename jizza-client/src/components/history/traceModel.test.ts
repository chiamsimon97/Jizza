import { describe, expect, it } from 'vitest'
import { layoutMap, waterfall } from './traceModel'
import type { RunTrace } from '@/types'

const trace = (over: Partial<RunTrace> = {}): RunTrace => ({
  run: {} as RunTrace['run'],
  nodes: [
    { id: 'start', type: 'TRIGGER', label: 'Start' },
    { id: 'split', type: 'PARALLEL', label: 'Split' },
    { id: 'a', type: 'TRANSFORM', label: 'A' },
    { id: 'b1', type: 'TRANSFORM', label: 'B1' },
    { id: 'b2', type: 'TRANSFORM', label: 'B2' },
    { id: 'join', type: 'JOIN', label: 'Join' },
  ],
  edges: [
    { from: 'start', to: 'split' }, { from: 'split', to: 'a' }, { from: 'split', to: 'b1' },
    { from: 'b1', to: 'b2' }, { from: 'a', to: 'join' }, { from: 'b2', to: 'join' },
  ],
  steps: [
    { id: '1', nodeId: 'start', label: 'Start', status: 'DONE', attempts: 1 },
    { id: '2', nodeId: 'split', label: 'Split', status: 'DONE', attempts: 1 },
    { id: '3', nodeId: 'a', label: 'A', status: 'FAILED', attempts: 3 },
  ],
  children: [],
  ...over,
})

describe('layoutMap', () => {
  it('puts a join after the longest path into it and stacks parallel paths', () => {
    const { nodes, cols, rows } = layoutMap(trace())
    const at = (id: string) => nodes.find(n => n.id === id)!
    expect(at('a').col).toBe(2)
    expect(at('b1').col).toBe(2)
    expect(at('b2').col).toBe(3)
    expect(at('join').col).toBe(4)
    expect(cols).toBe(5)
    expect(rows).toBe(2)
  })

  it('marks how each node fared, including the one the run waits on', () => {
    const { nodes } = layoutMap(trace({ waitingOn: 'b1' }))
    const state = Object.fromEntries(nodes.map(n => [n.id, n.state]))
    expect(state).toMatchObject({ start: 'done', a: 'failed', b1: 'waiting', join: 'unreached' })
  })
})

describe('waterfall', () => {
  it('places a step on the run clock and clamps to it', () => {
    const t0 = Date.parse('2026-10-22T10:00:00Z')
    const step = { id: 's', nodeId: 'x', label: 'x', status: 'DONE' as const, attempts: 1,
      startedAt: '2026-10-22T10:00:02Z', finishedAt: '2026-10-22T10:00:06Z' }
    expect(waterfall(step, t0, t0 + 10_000)).toEqual({ left: 0.2, width: 0.4 })
    expect(waterfall({ ...step, finishedAt: undefined }, t0, t0 + 10_000).width).toBe(0)
  })
})
