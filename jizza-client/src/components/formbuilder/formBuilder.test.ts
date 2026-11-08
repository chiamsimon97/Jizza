import { describe, expect, it } from 'vitest'
import { acceptAll, flatten, linkNameOf, moveField, newField, pendingCount, problemsOf, removeField, uniqueName, updateField } from './formModel'
import { guessType, inputsOfStep, layoutFor, stepsOf } from './stepFields'

describe('form model', () => {
  it('makes link names from labels', () => {
    expect(linkNameOf('First name')).toBe('firstName')
    expect(linkNameOf('2nd address!')).toBe('f2ndAddress')
    expect(linkNameOf('')).toBe('field')
  })

  it('names new fields so they do not clash', () => {
    const a = newField('email', [])
    const b = newField('email', [a])
    expect(a.name).toBe('email')
    expect(b.name).toBe('email2')
    expect(newField('section', [a]).name).toBeUndefined()
  })

  it('flags clashing link names and missing choices', () => {
    const a = newField('single_line', [])
    const b = { ...newField('single_line', [a]), name: a.name }
    const c = { ...newField('dropdown', []), choices: [' '] }
    const problems = problemsOf([a, b, c])
    expect(problems.get(b.id)).toMatch(/already/)
    expect(problems.get(c.id)).toMatch(/choice/)
    expect(problems.has(a.id)).toBe(false)
  })

  it('moves fields and removes empty step layouts', () => {
    const [a, b, c] = ['a', 'b', 'c'].map(id => ({ id, type: 'single_line' as const, name: id, label: id }))
    expect(moveField([a, b, c], 'a', 3).map(f => f.id)).toEqual(['b', 'c', 'a'])
    expect(moveField([a, b, c], 'c', 0).map(f => f.id)).toEqual(['c', 'a', 'b'])
    const layout = { id: 'l', type: 'step_layout' as const, label: 'Step', children: [a] }
    expect(removeField([layout, b], 'a').map(f => f.id)).toEqual(['b'])
  })
})

const workflow = {
  id: 'wf1', name: 'Invoices',
  graph: {
    nodes: [
      { id: 'start', type: 'TRIGGER', label: 'Start', config: { inputs: [{ name: 'customerEmail', type: 'text', required: true }, { name: 'amount', type: 'number' }] } },
      { id: 'n1', type: 'NOTIFY', label: 'Tell me', config: { message: 'Hi' } },
      { id: 'n2', type: 'CONNECTOR_TOOL', label: 'Create invoice', config: { toolId: 't', arguments: { dueDate: '2026-01-01', notes: 'x', lines: [] } } },
      { id: 'b', type: 'BRANCH', label: 'Big?', config: { condition: 'input.amount > 5' } },
      { id: 'end', type: 'END', label: 'Done', config: {} },
    ],
    edges: [],
  },
}

describe('fields from workflow steps', () => {
  it('lists the steps without the end', () => {
    expect(stepsOf(workflow.graph).map(s => s.id)).toEqual(['start', 'n1', 'n2', 'b'])
  })

  it('makes start inputs keep their names and bind as inputs', () => {
    const layout = layoutFor(workflow, stepsOf(workflow.graph)[0], [])
    expect(layout.children!.map(f => [f.name, f.type, f.binding!.mode])).toEqual([
      ['customerEmail', 'email', 'input'], ['amount', 'decimal', 'input'],
    ])
    expect(layout.children!.every(f => f.pending)).toBe(true)
    expect(layout.children![0].required).toBe(true)
  })

  it('skips a start input that is already on the form', () => {
    const first = layoutFor(workflow, stepsOf(workflow.graph)[0], [])
    const again = layoutFor(workflow, stepsOf(workflow.graph)[0], [first])
    expect(again.children).toEqual([])
  })

  it('binds a service action argument as a template setting', () => {
    const inputs = inputsOfStep(stepsOf(workflow.graph)[2])
    expect(inputs.map(i => [i.path, i.mode, i.type])).toEqual([
      [['arguments', 'dueDate'], 'template', 'date'], [['arguments', 'notes'], 'template', 'multi_line'],
    ])
  })

  it('gives a branch nothing to fill', () => {
    expect(inputsOfStep(stepsOf(workflow.graph)[3])).toEqual([])
  })

  it('accepts all of a layout', () => {
    const layout = layoutFor(workflow, stepsOf(workflow.graph)[1], [])
    expect(pendingCount([layout])).toBe(2)
    const accepted = acceptAll([layout], layout.id)
    expect(pendingCount(accepted)).toBe(0)
    expect(flatten(updateField(accepted, layout.children![0].id, { label: 'Note' }))[0].label).toBe('Note')
  })

  it('guesses field types', () => {
    expect(guessType('website')).toBe('url')
    expect(guessType('x', 'boolean')).toBe('decision')
    expect(guessType('phoneNumber')).toBe('phone')
    expect(uniqueName([], 'x')).toBe('x')
  })
})
