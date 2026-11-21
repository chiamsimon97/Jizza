import { describe, expect, it } from 'vitest'
import { toGraph } from './WorkflowForm'
import { fromGraph, problems } from '@/components/workflow/canvasModel'

describe('toGraph', () => {
  it('turns drafted agent and ask steps into a straight line from Start to the end', () => {
    const graph = toGraph([
      { kind: 'agent', agentId: 'a1', instruction: 'Check the gold rate' },
      { kind: 'ask', agentId: '', instruction: 'Buy?' },
    ])
    expect(graph.edges).toEqual([
      { from: 'start', to: 'step_1', branch: null }, { from: 'step_1', to: 'step_2', branch: null }, { from: 'step_2', to: 'end', branch: null },
    ])
    expect(graph.nodes[1]).toMatchObject({ type: 'AGENT_STEP', config: { agentId: 'a1', instruction: 'Check the gold rate' } })
    expect(graph.nodes[2]).toMatchObject({ type: 'HUMAN_INTERVENTION', config: { prompt: 'Buy?' } })
    // It opens on the wires with nothing to fix.
    expect(problems(fromGraph(graph))).toEqual([])
  })
})
