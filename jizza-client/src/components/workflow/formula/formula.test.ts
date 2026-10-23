import { describe, expect, it } from 'vitest'
import { CompletionContext } from '@codemirror/autocomplete'
import { EditorState } from '@codemirror/state'
import { addAfter, emptyGraph } from '../canvasModel'
import { dataSourcesFor, scopeFor } from '../dataMapping'
import { analyze } from './analyze'
import { callAt, formulaCompletions } from './completion'
import { problemsIn } from './editor'
import { parseFormula, placeholdersIn, referencesIn, tokenize } from './parser'

function setup() {
  const { graph, id: hook } = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
  const next = addAfter(graph, hook, null, 'NOTIFY')
  const sources = dataSourcesFor(next.graph, next.id, new Map<string, unknown>([[hook, { status: 200, body: { price: 6100, unit: 'g', items: [] } }]]))
  return { hook, sources, scope: scopeFor(sources) }
}

function complete(doc: string, template = false, env = setup(), pos = doc.length) {
  const { sources, scope } = env
  const state = EditorState.create({ doc })
  const result = formulaCompletions(() => ({ sources, scope }), template)(new CompletionContext(state, pos, true))
  return result?.options ?? []
}

describe('formula grammar', () => {
  it('parses chained calls, operators, ternaries, lists, maps and the older fmt: form', () => {
    for (const ok of [
      'input.name.trim().upper()',
      "nodes.a.body.price.round(2) > 1000 && vars.city == 'Pune'",
      "input.ok ? 'yes' : 'no'",
      'input.items[0].qty * 2 + 1',
      "fmt:formatDate(input.date, 'dd MMM')",
      "{'name': input.name, 'tags': [1, 2]}",
      'not input.done || input.count ?? 0',
      '(input.a + input.b).round(1)',
    ]) expect(parseFormula(ok).ok, ok).toBe(true)
  })

  it('says where a formula goes wrong', () => {
    const missing = parseFormula('upper(input.name')
    expect(missing.ok).toBe(false)
    expect(!missing.ok && missing.error.message).toMatch(/Expected “\)”/)
    const joined = parseFormula('input.a input.b')
    expect(!joined.ok && joined.error).toMatchObject({ from: 8, message: expect.stringMatching(/Unexpected “input”/) })
    expect(parseFormula("'open").ok).toBe(false)
  })

  it('finds references, stopping before a function call, and not inside text', () => {
    const refs = referencesIn(tokenize("nodes.a.body.price.round(2) + input.items[0].qty + 'input.x' + vars.n"))
    expect(refs.map(r => r.text)).toEqual(['nodes.a.body.price', 'input.items[0].qty', 'vars.n'])
  })

  it('finds placeholders in a template, including one still being typed', () => {
    expect(placeholdersIn('Hi {{ input.name }}, total {{ nodes.a')).toEqual([
      { from: 3, to: 19, innerFrom: 5, innerTo: 17, closed: true },
      { from: 27, to: 37, innerFrom: 29, innerTo: 37, closed: false },
    ])
  })
})

describe('formula checks', () => {
  it('flags unknown functions, wrong counts and steps that are not before this one', () => {
    const { scope, hook } = setup()
    expect(analyze('input.name.condssa()', scope).problems[0]).toMatchObject({ severity: 'error', message: 'Unknown function “condssa”' })
    expect(analyze('input.price.round()', scope).problems[0].message).toMatch(/takes 1 value in brackets, not 0/)
    expect(analyze('round(input.price)', scope).problems[0].message).toMatch(/takes 2 values in brackets, not 1/)
    expect(analyze('nodes.later.body', scope).problems[0]).toMatchObject({ severity: 'error' })
    expect(analyze('total + 1', scope).problems[0].message).toMatch(/Unknown name “total”/)
    expect(analyze(`nodes.${hook}.body.price.round(2)`, scope).problems).toEqual([])
  })

  it('warns, but does not fail, when a value is the wrong type', () => {
    const { scope, hook } = setup()
    const { problems, type } = analyze(`nodes.${hook}.status.contains('2')`, scope)
    expect(problems).toEqual([expect.objectContaining({ severity: 'warning', message: expect.stringMatching(/expects text .* but this is a number/) })])
    expect(type).toBe('boolean')
  })

  it('checks each placeholder of a template where it sits', () => {
    const { scope } = setup()
    const problems = problemsIn('Hello {{ input.name.nope() }} and {{ input', true, scope)
    expect(problems.map(p => p.message)).toEqual(['Unknown function “nope”', 'Close this with }}'])
    expect(problems[0].from).toBe('Hello {{ input.name.'.length)
  })
})

describe('formula suggestions', () => {
  it('offers each earlier step\'s data, grouped by step, then functions by kind', () => {
    const labels = complete('')
    expect(labels.some(o => o.label === 'input' && o.section && (o.section as { name: string }).name.startsWith('Start'))).toBe(true)
    expect(labels.some(o => o.label === 'upper' && (o.section as { name: string }).name === 'Functions of Text')).toBe(true)
  })

  it('after a dot, offers the fields there and the functions that suit the type first', () => {
    const env = setup()
    const { hook } = env
    const body = complete(`nodes.${hook}.body.`, false, env)
    expect(body.filter(o => o.type === 'property').map(o => o.label)).toEqual(expect.arrayContaining(['price', 'unit', 'items']))
    const price = complete(`nodes.${hook}.body.price.`, false, env)
    const recommended = price.filter(o => (o.section as { name: string }).name === 'Recommended functions').map(o => o.label)
    expect(recommended).toContain('round')
    expect(recommended).not.toContain('upper')
    const steps = complete('nodes.', false, env)
    expect(steps.map(o => o.apply)).toEqual([hook])
  })

  it('suggests nothing in a template\'s plain text, only inside {{ }}', () => {
    expect(complete('Hello the', true)).toEqual([])
    expect(complete('Hello {{ inp', true).length).toBeGreaterThan(0)
  })

  it('knows which value of a call the cursor is on', () => {
    expect(callAt("input.date.formatDate('dd', ", 27, false)).toMatchObject({ chained: true, arg: 1, fn: { name: 'formatDate' } })
    expect(callAt('round(input.a', 13, false)).toMatchObject({ chained: false, arg: 0 })
    expect(callAt('input.a', 7, false)).toBeNull()
  })
})
