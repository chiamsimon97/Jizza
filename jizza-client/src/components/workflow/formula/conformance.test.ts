import { describe, expect, it } from 'vitest'
import corpus from './cases.json'
import { analyze } from './analyze'
import { functionAt } from './editor'
import { parseFormula } from './parser'

/** The same cases the engine runs (FormulaConformanceTest), so the editor and the engine agree. */
describe('formulas the editor and the engine share', () => {
  const lenient = { typeOf: () => undefined, hasStep: () => true, hasVariable: () => undefined }

  for (const c of corpus.cases) {
    const parses = (c as { parses?: boolean }).parses ?? true
    it(`${parses ? 'accepts' : 'refuses'} ${c.formula}`, () => {
      expect(parseFormula(c.formula).ok).toBe(parses)
      if (parses) expect(analyze(c.formula, lenient).problems.filter(p => p.severity === 'error')).toEqual([])
    })
  }
})

describe('function docs on hover', () => {
  it('finds the function under the pointer, in a formula or inside a placeholder only', () => {
    const doc = 'input.lines.where(\'status\', \'paid\')'
    expect(functionAt(doc, 14, false)).toEqual({ from: 12, to: 17, name: 'where' })
    expect(functionAt(doc, 3, false)).toBeNull()
    const text = 'where is {{ input.name.upper() }}'
    expect(functionAt(text, 2, true)).toBeNull()
    expect(functionAt(text, text.indexOf('upper') + 1, true)?.name).toBe('upper')
  })
})
