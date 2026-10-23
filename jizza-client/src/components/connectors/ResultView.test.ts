import { describe, expect, it } from 'vitest'
import { asData, schemaFields } from './ResultView'

describe('tool results', () => {
  it('reads JSON sent as text as data', () => {
    expect(asData('[{"tradingsymbol":"INFY"}]')).toEqual([{ tradingsymbol: 'INFY' }])
    expect(asData('found 2 notes')).toBe('found 2 notes')
    expect(asData('{not json}')).toBe('{not json}')
  })

  it('lists the fields a schema describes as paths a step can map', () => {
    const holdings = { type: 'array', items: { type: 'object', properties: {
      tradingsymbol: { type: 'string' }, quantity: { type: 'integer' },
      mtf: { type: 'object', properties: { value: { type: 'number' } } },
    } } }
    expect(schemaFields(holdings).map(f => f.path)).toEqual([
      '[0]', '[0].tradingsymbol', '[0].quantity', '[0].mtf', '[0].mtf.value',
    ])
    expect(schemaFields({ type: 'object', properties: { data: holdings } }).map(f => f.path))
      .toContain('data[0].tradingsymbol')
    expect(schemaFields(null)).toEqual([])
  })
})
