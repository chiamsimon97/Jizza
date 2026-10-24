import { describe, expect, it } from 'vitest'
import { localsOf, scriptFunctionAt, scriptProblems } from './script'

const inputs = [{ name: 'lines', type: 'list' as const }]

describe('Jizza script', () => {
  it('finds the variables a script declares, not ones in comments or strings', () => {
    expect(localsOf("var total = 0;\n// var hidden = 1\nvar label = 'var nope';\nfor (var line : lines) { }")).toEqual(['total', 'label', 'line'])
  })

  it('accepts a script with loops over a list, functions and a return', () => {
    const script = "var total = 0;\nfor (var line : lines) {\n  total = total + line.amount;\n}\n// don't do this twice\nreturn {'total': total.round(2), 'label': upper('ok')};"
    expect(scriptProblems(script, inputs)).toEqual([])
  })

  it('flags while, unknown functions, unclosed brackets and a missing return', () => {
    const problems = scriptProblems('while (true) { nope(1)', inputs)
    expect(problems.map(p => [p.severity, p.message.split(' ').slice(0, 3).join(' ')])).toEqual([
      ['error', 'Loop over a'],
      ['error', 'There is no'],
      ['error', 'This { is'],
      ['warning', "End with return"],
    ])
  })

  it('finds the function under the cursor for its docs', () => {
    const doc = "return {'x': upper(name)};"
    expect(scriptFunctionAt(doc, doc.indexOf('upper') + 2)).toMatchObject({ name: 'upper' })
    expect(scriptFunctionAt(doc, doc.indexOf('name') + 1)).toBeNull()
  })
})
