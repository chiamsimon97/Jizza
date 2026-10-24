import { describe, expect, it } from 'vitest'
import { addAfter, emptyGraph, fieldIssues, updateNode } from './canvasModel'
import { dataSourcesFor } from './dataMapping'
import { contractIssues, isModule, moduleInputs, moduleOutputs } from './moduleContract'

const saved = {
  nodes: [
    { id: 'start', type: 'TRIGGER', config: { inputs: [{ name: 'amount', type: 'number', required: true }, { name: 'city', type: 'text', default: 'Chennai' }] } },
    { id: 'end', type: 'END', config: { outputs: [{ name: 'doubled', type: 'number', value: '{{ input.amount * 2 }}' }] } },
    { id: 'end2', type: 'END', config: { outputs: [{ name: 'doubled', type: 'text', value: 'x' }, { name: 'note', type: 'text', value: 'y' }] } },
  ],
  edges: [],
}

describe('workflow modules', () => {
  it('reads the inputs from Start and the outputs from every End', () => {
    expect(moduleInputs(saved).map(f => [f.name, f.type, f.required ?? false, f.default])).toEqual([
      ['amount', 'number', true, undefined], ['city', 'text', false, 'Chennai'],
    ])
    expect(moduleOutputs(saved).map(f => [f.name, f.type])).toEqual([['doubled', 'number'], ['note', 'text']])
    expect(isModule(saved)).toBe(true)
    expect(isModule({ nodes: [{ type: 'TRIGGER', config: {} }] })).toBe(false)
  })

  it('flags bad names, repeats and outputs without a value', () => {
    expect(contractIssues({ inputs: [{ name: 'first name' }, { name: 'a' }, { name: 'a' }] }, 'inputs', 'Start').map(([f]) => f))
      .toEqual(['inputs.0.name', 'inputs.2.name'])
    expect(contractIssues({ outputs: [{ name: 'total', value: ' ' }] }, 'outputs', 'End').map(([f]) => f)).toEqual(['outputs.0.value'])
  })

  it('offers the declared inputs as Start data, and a module step\'s outputs typed, to later steps', () => {
    let { graph, id: call } = addAfter(emptyGraph(), 'start', null, 'SUB_WORKFLOW')
    graph = updateNode(graph, 'start', { config: { inputs: [{ name: 'email', type: 'text' }] } })
    const next = addAfter(graph, call, null, 'NOTIFY')
    expect(fieldIssues(next.graph.nodes.find(n => n.id === 'start')!)).toEqual([])
    const sources = dataSourcesFor(next.graph, next.id, undefined, n => (n.id === call ? [{ path: 'doubled', type: 'number' }] : []))
    expect(sources.find(s => s.id === 'start')?.fields).toContainEqual({ expr: 'input.email', label: 'email', type: 'text' })
    expect(sources.find(s => s.id === call)?.fields).toContainEqual({ expr: `nodes.${call}.doubled`, label: 'doubled', type: 'number' })
  })
})
