import { describe, expect, it } from 'vitest'
import { addAfter, emptyGraph, fieldIssues, updateNode } from './canvasModel'
import { dataSourcesFor } from './dataMapping'
import { contractIssues, fieldsOf, inputSections, isModule, moduleInputs, moduleOutputs, ruleProblem } from './moduleContract'

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

  it('gives a Script step\'s declared outputs, typed, to later steps; its outputs need no value but its inputs do', () => {
    const { graph, id: script } = addAfter(emptyGraph(), 'start', null, 'SCRIPT')
    const config = { inputs: [{ name: 'lines', type: 'list', value: '' }], script: "return {'total': 1};", outputs: [{ name: 'total', type: 'number' }] }
    const withScript = updateNode(graph, script, { config })
    expect(fieldIssues(withScript.nodes.find(n => n.id === script)!).map(f => f.field)).toEqual(['inputs.0.value'])
    const next = addAfter(withScript, script, null, 'NOTIFY')
    const sources = dataSourcesFor(next.graph, next.id)
    expect(sources.find(s => s.id === script)?.fields).toContainEqual({ expr: `nodes.${script}.total`, label: 'total', type: 'number' })
  })
})

describe('input catalogue', () => {
  const config = {
    inputs: [
      { name: 'from', type: 'text', group: 'Journey', label: 'From station' },
      { name: 'cls', type: 'text', group: 'Journey', options: ['Sleeper', 'AC'], synonyms: { sl: 'Sleeper' } },
      { name: 'note', type: 'text' },
      { name: 'otp', type: 'text', ask: 'atStep' },
      { name: 'people', type: 'list', maxRows: 2, columns: [{ key: 'name', label: 'Name', required: true, options: ['x'] }] },
      { name: 'seats', type: 'number', min: 1, max: 6, ask: 'sometimes' },
    ],
  }

  it('keeps every catalogue key so a field read and saved back loses nothing', () => {
    const [from, cls, , otp, people, seats] = fieldsOf(config, 'inputs')
    expect(from).toMatchObject({ label: 'From station', group: 'Journey' })
    expect(cls).toMatchObject({ options: ['Sleeper', 'AC'], synonyms: { sl: 'Sleeper' } })
    expect(otp.ask).toBe('atStep')
    expect(people.columns?.[0]).toEqual({ key: 'name', label: 'Name', required: true, options: ['x'] })
    expect(seats).toMatchObject({ min: 1, max: 6 })
    expect(seats.ask).toBeUndefined()
  })

  it('shows ungrouped inputs first and leaves at-step inputs for their card', () => {
    const sections = inputSections(fieldsOf(config, 'inputs'))
    expect(sections.map(s => s.group)).toEqual(['', 'Journey'])
    expect(sections[0].fields.map(f => f.name)).toEqual(['note', 'people', 'seats'])
    expect(sections.flatMap(s => s.fields).some(f => f.name === 'otp')).toBe(false)
  })

  it('names what is wrong before the server is asked', () => {
    const [, , , , people, seats] = fieldsOf(config, 'inputs')
    expect(ruleProblem(seats, '9')).toContain('at most 6')
    expect(ruleProblem(seats, '3')).toBeNull()
    expect(ruleProblem(people, '[{"name":"a"},{"name":"b"},{"name":"c"}]')).toContain('at most 2 entries')
    expect(ruleProblem({ name: 'pin', type: 'text', pattern: '[0-9]{6}', patternHint: '6 digits' }, '12')).toContain('6 digits')
    expect(ruleProblem({ name: 'pin', type: 'text', pattern: '[broken' }, '12')).toBeNull()
  })
})
