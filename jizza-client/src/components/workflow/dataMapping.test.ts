import { describe, expect, it } from 'vitest'
import { addAfter, emptyGraph } from './canvasModel'
import { chipLabel, dataSourcesFor, parse, scopeFor, write } from './dataMapping'

describe('dataMapping', () => {
  it('cuts a template into text and chips and puts it back', () => {
    const value = 'Gold is {{ nodes.rate.body.price }} today ({{fmt:formatDate(input.date, "dd MMM")}})'
    const parts = parse(value, 'template')
    expect(parts).toEqual([
      { kind: 'text', text: 'Gold is ' },
      { kind: 'ref', expr: 'nodes.rate.body.price' },
      { kind: 'text', text: ' today (' },
      { kind: 'ref', expr: 'fmt:formatDate(input.date, "dd MMM")' },
      { kind: 'text', text: ')' },
    ])
    expect(write(parts, 'template')).toBe('Gold is {{ nodes.rate.body.price }} today ({{ fmt:formatDate(input.date, "dd MMM") }})')
  })

  it('finds references in an expression without touching functions or strings around them', () => {
    const value = "nodes.rate.body.price < 2000 && fmt:upper(vars.city) == 'X' && input.items[0].qty > 1"
    const refs = parse(value, 'expression').filter(p => p.kind === 'ref').map(p => (p as { expr: string }).expr)
    expect(refs).toEqual(['nodes.rate.body.price', 'vars.city', 'input.items[0].qty'])
    expect(write(parse(value, 'expression'), 'expression')).toBe(value)
  })

  it('offers the input, earlier steps with their fields, and variables, but not later steps', () => {
    let { graph, id: hook } = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    const next = addAfter(graph, hook, null, 'NOTIFY')
    graph = next.graph
    const later = addAfter(graph, next.id, null, 'STORAGE').graph
    const sources = dataSourcesFor(later, next.id, new Map<string, unknown>([[hook, { status: 200, body: { price: 6100, unit: 'g' } }]]))
    expect(sources.map(s => s.type)).toEqual(['TRIGGER', 'HTTP_REQUEST'])
    const fields = sources[1].fields.map(f => f.expr)
    expect(fields).toContain(`nodes.${hook}.status`)
    expect(fields).toContain(`nodes.${hook}.body.price`)
    // Known outputs and values from the last run come with their types, for the formula editor.
    const scope = scopeFor(sources)
    expect(scope.typeOf(`nodes.${hook}.status`)).toBe('number')
    expect(scope.typeOf(`nodes.${hook}.body.unit`)).toBe('text')
    expect(scope.hasStep(hook)).toBe(true)
    expect(scope.hasStep(next.id)).toBe(false)
  })

  it('labels a chip with the step name and field, or as a formula', () => {
    const { graph, id } = addAfter(emptyGraph(), 'start', null, 'HTTP_REQUEST')
    expect(chipLabel(`nodes.${id}.body.price`, graph)).toEqual({ source: 'Send webhook', field: 'body.price', formula: false })
    expect(chipLabel('input.email', graph)).toEqual({ source: 'Start', field: 'email', formula: false })
    expect(chipLabel('fmt:upper(input.name)', graph).formula).toBe(true)
  })
})
