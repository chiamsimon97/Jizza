import { describe, expect, it } from 'vitest'
import { formatINR, formatINRShort, formatPercent, groupIndian } from './money'

describe('groupIndian', () => {
  it.each([
    ['0', '0'], ['999', '999'], ['1000', '1,000'], ['12345', '12,345'], ['123456', '1,23,456'],
    ['1234567', '12,34,567'], ['12345678', '1,23,45,678'], ['123456789', '12,34,56,789'],
  ])('%s → %s', (input, expected) => expect(groupIndian(input)).toBe(expected))
})

describe('formatINR', () => {
  it('groups lakh and crore and rounds to whole rupees', () => {
    expect(formatINR(1234567.5)).toBe('₹12,34,568')
    expect(formatINR(10000000)).toBe('₹1,00,00,000')
  })
  it('shows paise when asked', () => expect(formatINR(1500.5, { decimals: 2 })).toBe('₹1,500.50'))
  it('marks negatives with a minus sign before the symbol', () => expect(formatINR(-2500)).toBe('−₹2,500'))
  it('signs positive changes only when asked', () => {
    expect(formatINR(2500, { signed: true })).toBe('+₹2,500')
    expect(formatINR(0, { signed: true })).toBe('₹0')
  })
  it('never shows a negative zero', () => expect(formatINR(-0.2)).toBe('₹0'))
  it('can leave out the symbol', () => expect(formatINR(125000, { symbol: false })).toBe('1,25,000'))
  it('shows a dash for a missing number', () => expect(formatINR(Number.NaN)).toBe('—'))
})

describe('formatINRShort', () => {
  it('keeps amounts under a lakh in full', () => expect(formatINRShort(85000)).toBe('₹85,000'))
  it('uses lakh from one lakh', () => {
    expect(formatINRShort(100000)).toBe('₹1 L')
    expect(formatINRShort(1234567)).toBe('₹12.3 L')
  })
  it('uses crore from one crore', () => {
    expect(formatINRShort(12500000)).toBe('₹1.25 Cr')
    expect(formatINRShort(30000000)).toBe('₹3 Cr')
  })
  it('signs negatives and, when asked, positives', () => {
    expect(formatINRShort(-450000)).toBe('−₹4.5 L')
    expect(formatINRShort(450000, { signed: true })).toBe('+₹4.5 L')
  })
})

describe('formatPercent', () => {
  it('shows one decimal and drops trailing zeros', () => {
    expect(formatPercent(0.153)).toBe('15.3%')
    expect(formatPercent(0.2)).toBe('20%')
  })
})
