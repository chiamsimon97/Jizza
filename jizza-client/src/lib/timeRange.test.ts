import { describe, expect, it } from 'vitest'
import { dayKey, dayLabel, describeRange, formatDuration, readRange, resolveRange, writeRange } from './timeRange'

const now = new Date(2026, 9, 22, 15, 30) // Thu 22 Oct 2026, 15:30 local

describe('resolveRange', () => {
  it('slides presets with the clock', () => {
    expect(resolveRange({ preset: 'last_hour' }, now).from?.getTime()).toBe(now.getTime() - 3600_000)
    expect(resolveRange({ preset: 'today' }, now).from).toEqual(new Date(2026, 9, 22))
    expect(resolveRange({ preset: 'all' }, now)).toEqual({})
  })

  it('bounds yesterday and the previous month on both sides', () => {
    expect(resolveRange({ preset: 'yesterday' }, now)).toEqual({ from: new Date(2026, 9, 21), to: new Date(2026, 9, 22) })
    expect(resolveRange({ preset: 'last_month' }, now)).toEqual({ from: new Date(2026, 8, 1), to: new Date(2026, 9, 1) })
  })

  it('starts the week on Monday', () => {
    expect(resolveRange({ preset: 'this_week' }, now).from).toEqual(new Date(2026, 9, 19))
  })

  it('keeps fixed bounds as they are', () => {
    const r = resolveRange({ from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }, now)
    expect(r.from?.toISOString()).toBe('2026-10-01T00:00:00.000Z')
    expect(r.to?.toISOString()).toBe('2026-10-02T00:00:00.000Z')
  })
})

describe('url round trip', () => {
  it('writes and reads a preset or fixed bounds', () => {
    const p = new URLSearchParams()
    writeRange(p, { preset: 'last_30d' })
    expect(readRange(p, 'last_7d')).toEqual({ preset: 'last_30d' })
    writeRange(p, { from: '2026-10-01T00:00:00.000Z' })
    expect(readRange(p, 'last_7d')).toEqual({ from: '2026-10-01T00:00:00.000Z', to: undefined })
    expect(readRange(new URLSearchParams(), 'last_7d')).toEqual({ preset: 'last_7d' })
  })
})

describe('formatting', () => {
  it('formats durations at each scale', () => {
    expect(formatDuration(0.2)).toBe('<1 ms')
    expect(formatDuration(840)).toBe('840 ms')
    expect(formatDuration(13127)).toBe('13 s')
    expect(formatDuration(4200)).toBe('4.2 s')
    expect(formatDuration(245_000)).toBe('4m 05s')
    expect(formatDuration(7_800_000)).toBe('2h 10m')
  })

  it('labels days relative to today', () => {
    expect(dayLabel('2026-10-22', now)).toBe('Today')
    expect(dayLabel('2026-10-21', now)).toBe('Yesterday')
    expect(dayKey(new Date(2026, 9, 5, 23, 59).toISOString())).toBe('2026-10-05')
  })

  it('describes presets by name', () => {
    expect(describeRange({ preset: 'last_7d' })).toBe('Last 7 days')
    expect(describeRange({})).toBe('All time')
  })
})
