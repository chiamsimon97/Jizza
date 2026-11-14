import { describe, expect, it } from 'vitest'
import { formatDate, kindOf, parseAny, parseWithFormat, pickerValue } from './dateFormat'

describe("dates in the site's format", () => {
  it('reads a date written the site’s way, month first and month names included', () => {
    expect(pickerValue('20/11/2026', 'date', 'DD/MM/YYYY')).toBe('2026-11-20')
    expect(pickerValue('11/20/2026', 'date', 'MM/DD/YYYY')).toBe('2026-11-20')
    expect(pickerValue('20-Nov-2026', 'date', 'DD-MMM-YYYY')).toBe('2026-11-20')
    expect(pickerValue('20/11/2026 06:30 PM', 'datetime', 'DD/MM/YYYY hh:mm A')).toBe('2026-11-20T18:30')
    expect(parseWithFormat('Asha', 'DD/MM/YYYY')).toBeUndefined()
  })

  it('reads ISO and day-first dates without a format, and leaves mappings and text alone', () => {
    expect(pickerValue('2026-11-20', 'date')).toBe('2026-11-20')
    expect(pickerValue('2026-11-20T18:30', 'datetime')).toBe('2026-11-20T18:30')
    expect(pickerValue('2026-11-20', 'datetime')).toBe('2026-11-20T00:00')
    expect(parseAny('20 Nov 2026')).toMatchObject({ year: 2026, month: 11, day: 20 })
    expect(pickerValue('{{ input.when }}', 'date')).toBe('')
    expect(pickerValue('next Friday', 'date')).toBe('')
  })

  it('writes a picked date the site’s way', () => {
    expect(formatDate('2026-11-05', 'DD/MM/YYYY')).toBe('05/11/2026')
    expect(formatDate('2026-11-05', 'D MMM YYYY')).toBe('5 Nov 2026')
    expect(formatDate('2026-11-05T18:05', 'DD/MM/YYYY hh:mm A')).toBe('05/11/2026 06:05 PM')
    expect(formatDate('2026-11-05T09:05', 'YYYY-MM-DD HH:mm')).toBe('2026-11-05 09:05')
  })

  it('takes a format with a time as a date and time', () => {
    expect(kindOf('date', 'DD/MM/YYYY')).toBe('date')
    expect(kindOf('date', 'DD/MM/YYYY HH:mm')).toBe('datetime')
    expect(kindOf('datetime')).toBe('datetime')
  })
})
