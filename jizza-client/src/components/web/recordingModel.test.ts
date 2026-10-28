import { describe, expect, it } from 'vitest'
import type { RecordedStep } from '@/api/web'
import { liveSteps, mappableFields, missingLogins, suggestInputName } from './recordingModel'

const step = (seq: number, over: Partial<RecordedStep['step']> = {}, deleted = false, position = seq): RecordedStep =>
  ({ seq, position, deleted, step: { action: 'browser.setText', label: 'Set "Invoice number" to "INV-2041"', input: { text: 'INV-2041', clearFirst: true }, ...over } })

describe('recordingModel', () => {
  it('lists live steps in their order, without deleted ones', () => {
    expect(liveSteps([step(1, {}, false, 2), step(2, {}, true, 1), step(3, {}, false, 1)]).map(s => s.seq)).toEqual([3, 1])
  })

  it('offers typed values as inputs, but never a password', () => {
    expect(mappableFields(step(1))).toEqual([{ field: 'text', value: 'INV-2041' }])
    expect(mappableFields(step(2, { meta: { sensitive: true } }))).toEqual([])
  })

  it('names an input from the quoted label', () => {
    expect(suggestInputName(step(1), 'text')).toBe('invoiceNumber')
  })

  it('finds password steps still without a saved login', () => {
    const pw = step(1, { meta: { sensitive: true } })
    expect(missingLogins([pw])).toHaveLength(1)
    expect(missingLogins([step(1, { meta: { sensitive: true }, secretRef: 'login-1' })])).toHaveLength(0)
  })
})
