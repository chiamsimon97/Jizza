import { describe, expect, it } from 'vitest'
import { stepsFromGraph } from './WorkflowForm'

const node = (id: string, type: string, config: Record<string, unknown> = {}) => ({ id, type, label: id, config })

describe('stepsFromGraph', () => {
  it('reads a straight line of agent and ask steps back into builder steps', () => {
    const graph = {
      nodes: [node('start', 'TRIGGER'), node('step_1', 'AGENT_STEP', { agentId: 'a1', instruction: 'Check the gold rate' }),
        node('step_2', 'HUMAN_INTERVENTION', { prompt: 'Buy?' }), node('end', 'END')],
      edges: [{ from: 'start', to: 'step_1' }, { from: 'step_1', to: 'step_2' }, { from: 'step_2', to: 'end' }],
    }
    expect(stepsFromGraph(graph)).toEqual([
      { kind: 'agent', agent: 'a1', instruction: 'Check the gold rate' },
      { kind: 'ask', instruction: 'Buy?' },
    ])
  })

  it('returns null for graphs the builder cannot show', () => {
    const branching = {
      nodes: [node('start', 'TRIGGER'), node('b', 'BRANCH'), node('x', 'END'), node('y', 'END')],
      edges: [{ from: 'start', to: 'b' }, { from: 'b', to: 'x' }, { from: 'b', to: 'y' }],
    }
    expect(stepsFromGraph(branching)).toBeNull()
    expect(stepsFromGraph({ nodes: [node('start', 'TRIGGER'), node('t', 'CONNECTOR_TOOL'), node('end', 'END')],
      edges: [{ from: 'start', to: 't' }, { from: 't', to: 'end' }] })).toBeNull()
    expect(stepsFromGraph(null)).toBeNull()
  })
})
