import { describe, expect, it } from 'vitest'
import { stepsFromGraph } from '@/components/forms/WorkflowForm'
import { addNode, emptyGraph, fromGraph, problems, setNext, targetsFor, toGraph } from './canvasModel'

describe('canvasModel', () => {
  it('lays out a saved graph without positions and keeps positions it was given', () => {
    const graph = fromGraph({
      nodes: [
        { id: 'start', type: 'TRIGGER', config: {} },
        { id: 'check', type: 'BRANCH', config: { condition: 'x > 1' } },
        { id: 'yes', type: 'AGENT_STEP', config: { agentId: 'a', instruction: 'go', ui: { x: 900, y: 10 } } },
        { id: 'no', type: 'END', config: {} },
      ],
      edges: [{ from: 'start', to: 'check' }, { from: 'check', to: 'yes', branch: 'true' }, { from: 'check', to: 'no', branch: 'false' }],
    })
    const at = Object.fromEntries(graph.nodes.map(n => [n.id, [n.x, n.y]]))
    expect(at.start[0]).toBeLessThan(at.check[0])
    expect(at.yes).toEqual([900, 10])
    expect(graph.nodes.find(n => n.id === 'yes')!.config).not.toHaveProperty('ui')
    expect(problems(graph)).toEqual([])
  })

  it('round-trips through the engine graph with positions in the config', () => {
    const { graph } = addNode(emptyGraph(), 'HUMAN_INTERVENTION', 300, 150, 'start')
    const saved = toGraph(graph)
    expect(saved.edges).toEqual([{ from: 'start', to: graph.nodes[1].id, branch: null }])
    expect(saved.nodes[1].config).toEqual({ ui: { x: 300, y: 150 } })
    expect(fromGraph(saved).nodes[1]).toMatchObject({ x: 300, y: 150, type: 'HUMAN_INTERVENTION' })
    // A straight line of agent and ask steps still opens in the step list.
    expect(stepsFromGraph(saved)).toEqual([{ kind: 'ask', instruction: undefined }])
  })

  it('says what a branch is missing and never offers a connection that would loop', () => {
    let g = addNode(emptyGraph(), 'BRANCH', 300, 150, 'start').graph
    const branch = g.nodes[1].id
    expect(problems(g)).toEqual(expect.arrayContaining([
      expect.stringContaining('needs a condition'), expect.stringContaining('"yes"'), expect.stringContaining('"no"'),
    ]))
    const end = addNode(g, 'END', 600, 150, branch)
    g = setNext(end.graph, branch, 'false', end.id)
    expect(g.edges.filter(e => e.from === branch).map(e => e.branch).sort()).toEqual(['false', 'true'])
    expect(targetsFor(g, end.id).map(n => n.id)).not.toContain(branch)
    expect(targetsFor(g, branch).map(n => n.id)).not.toContain('start')
  })
})
