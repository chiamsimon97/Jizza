import { describe, expect, it } from 'vitest'
import { argOf, cleanDraft, draftIssues, emptyDraft, keysOf, mergeKeys, resultFields } from './functionModel'
import { scriptProblems } from '@/components/workflow/formula/script'

describe('functions', () => {
  it('flags a bad or taken link name, bad inputs, an empty script and bad map keys', () => {
    const d = { ...emptyDraft(), name: 'upper', inputs: [{ name: 'fn', type: 'text' as const }, { name: 'a', type: 'text' as const }, { name: 'a', type: 'text' as const }],
      returnType: 'map' as const, returnFields: [{ path: 'customer..email', type: 'text' as const }] }
    expect(Object.keys(draftIssues(d))).toEqual(['name', 'inputs.0.name', 'inputs.2.name', 'script', 'returnFields.0.path'])
    expect(draftIssues({ ...emptyDraft(), name: 'orderTotal', script: 'return 1;' }, ['orderTotal']).name).toMatch(/already have/)
    expect(draftIssues({ ...emptyDraft(), name: 'orderTotal', script: 'return 1;' })).toEqual({})
  })

  it('reads test values by type and keeps map keys only for a map', () => {
    expect(argOf(' [1, 2] ', { name: 'xs', type: 'list' })).toEqual([1, 2])
    expect(argOf('42', { name: 'n', type: 'number' })).toBe(42)
    expect(argOf('42', { name: 's', type: 'text' })).toBe('42')
    expect(argOf('  ', { name: 's', type: 'text' })).toBeUndefined()
    expect(cleanDraft({ ...emptyDraft(), name: ' f ', returnType: 'number', returnFields: [{ path: 'x', type: 'any' }] }).returnFields).toEqual([])
  })

  it("takes a map's keys from a test result, nested ones dotted, and merges them with the listed ones", () => {
    const found = keysOf({ total: 10, customer: { email: 'a@b.c', tags: ['x'] }, 'bad key': 1 })
    expect(found).toEqual([
      { path: 'total', type: 'number' }, { path: 'customer', type: 'map' },
      { path: 'customer.email', type: 'text' }, { path: 'customer.tags', type: 'list' },
    ])
    expect(mergeKeys([{ path: 'total', type: 'any' }], found).map(f => `${f.path}:${f.type}`))
      .toEqual(['total:any', 'customer:map', 'customer.email:text', 'customer.tags:list'])
    expect(resultFields({ returnType: 'number', returnFields: [] })).toEqual([{ path: 'result', type: 'number' }])
  })

  it('checks calls to other functions by link name and number of inputs', () => {
    const fns = [{ name: 'withTax', inputs: [{ name: 'amount', type: 'number' as const }], returnType: 'number' as const }]
    expect(scriptProblems('return fn.withTax(total).round(2);', [], fns)).toEqual([])
    expect(scriptProblems('return fn.withTax(1, 2);', [], fns).map(p => p.message)).toEqual(['fn.withTax takes 1 input (amount) but is given 2'])
    expect(scriptProblems('return fn.nope(1);', [], fns).map(p => p.message)).toEqual(['There is no function called fn.nope'])
  })
})
