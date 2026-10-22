/**
 * Time ranges for the history views. A range is either a named preset, which slides with the
 * clock ("Last 7 days" is always the last seven), or fixed bounds the person picked or zoomed to.
 */

export type PresetKey =
  | 'last_hour' | 'today' | 'yesterday' | 'last_24h' | 'last_7d' | 'last_30d'
  | 'this_week' | 'this_month' | 'last_month' | 'year_to_date' | 'all'

export interface TimeRange {
  preset?: PresetKey
  /** ISO instants; unset means open-ended on that side. */
  from?: string
  to?: string
}

export const PRESETS: { key: PresetKey; label: string; short: string }[] = [
  { key: 'last_hour', label: 'Last hour', short: '1h' },
  { key: 'last_24h', label: 'Last 24 hours', short: '24h' },
  { key: 'today', label: 'Today', short: 'Today' },
  { key: 'yesterday', label: 'Yesterday', short: 'Yest.' },
  { key: 'last_7d', label: 'Last 7 days', short: '7d' },
  { key: 'this_week', label: 'This week (Mon–Sun)', short: 'Week' },
  { key: 'last_30d', label: 'Last 30 days', short: '30d' },
  { key: 'this_month', label: 'Month to date', short: 'Month' },
  { key: 'last_month', label: 'Previous month', short: 'Prev. month' },
  { key: 'year_to_date', label: 'Year to date', short: 'YTD' },
  { key: 'all', label: 'All time', short: 'All' },
]

const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate())

/** The concrete bounds of a range at {@code now}. */
export function resolveRange(range: TimeRange, now: Date = new Date()): { from?: Date; to?: Date } {
  if (!range.preset) {
    return { from: range.from ? new Date(range.from) : undefined, to: range.to ? new Date(range.to) : undefined }
  }
  const hour = 3600_000
  const day = 24 * hour
  switch (range.preset) {
    case 'last_hour': return { from: new Date(now.getTime() - hour) }
    case 'last_24h': return { from: new Date(now.getTime() - day) }
    case 'today': return { from: startOfDay(now) }
    case 'yesterday': {
      const today = startOfDay(now)
      return { from: new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1), to: today }
    }
    case 'last_7d': return { from: new Date(now.getTime() - 7 * day) }
    case 'last_30d': return { from: new Date(now.getTime() - 30 * day) }
    case 'this_week': {
      const today = startOfDay(now)
      const sinceMonday = (today.getDay() + 6) % 7
      return { from: new Date(today.getFullYear(), today.getMonth(), today.getDate() - sinceMonday) }
    }
    case 'this_month': return { from: new Date(now.getFullYear(), now.getMonth(), 1) }
    case 'last_month': return {
      from: new Date(now.getFullYear(), now.getMonth() - 1, 1),
      to: new Date(now.getFullYear(), now.getMonth(), 1),
    }
    case 'year_to_date': return { from: new Date(now.getFullYear(), 0, 1) }
    case 'all': return {}
  }
}

/** The bounds as ISO strings for an API call. */
export function rangeParams(range: TimeRange, now: Date = new Date()): { from?: string; to?: string } {
  const { from, to } = resolveRange(range, now)
  return { from: from?.toISOString(), to: to?.toISOString() }
}

const dateFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })

/** "Last 7 days", or "3 Oct 14:00 → 4 Oct 09:00" for fixed bounds. */
export function describeRange(range: TimeRange): string {
  if (range.preset) return PRESETS.find(p => p.key === range.preset)?.label ?? 'All time'
  const { from, to } = resolveRange(range)
  if (!from && !to) return 'All time'
  const fmt = (d: Date) => (d.getHours() === 0 && d.getMinutes() === 0 ? dateFmt : dateTimeFmt).format(d)
  if (from && !to) return `Since ${fmt(from)}`
  if (!from && to) return `Until ${fmt(to)}`
  return `${fmt(from!)} → ${fmt(to!)}`
}

/** Reads a range from URL params written by {@link writeRange}. */
export function readRange(params: URLSearchParams, fallback: PresetKey): TimeRange {
  const preset = params.get('range') as PresetKey | null
  if (preset && PRESETS.some(p => p.key === preset)) return { preset }
  const from = params.get('from') ?? undefined
  const to = params.get('to') ?? undefined
  return from || to ? { from, to } : { preset: fallback }
}

export function writeRange(params: URLSearchParams, range: TimeRange) {
  params.delete('range'); params.delete('from'); params.delete('to')
  if (range.preset) params.set('range', range.preset)
  if (!range.preset && range.from) params.set('from', range.from)
  if (!range.preset && range.to) params.set('to', range.to)
}

/** "<1s", "840 ms", "13.1 s", "4m 05s", "2h 10m". */
export function formatDuration(ms: number | undefined | null): string {
  if (ms == null || Number.isNaN(ms)) return '—'
  if (ms < 1) return '<1 ms'
  if (ms < 1000) return `${Math.round(ms)} ms`
  const s = ms / 1000
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${String(Math.round(s % 60)).padStart(2, '0')}s`
  const h = Math.floor(m / 60)
  if (h < 48) return `${h}h ${String(m % 60).padStart(2, '0')}m`
  return `${Math.floor(h / 24)}d ${h % 24}h`
}

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

/** "just now", "5 minutes ago", "yesterday". */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const diff = (new Date(iso).getTime() - now.getTime()) / 1000
  const abs = Math.abs(diff)
  if (abs < 45) return 'just now'
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day')
  return new Date(iso).toLocaleDateString()
}

/** A local calendar day key, "2026-10-22", for grouping rows by the viewer's day. */
export function dayKey(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** "Today", "Yesterday", or "Tue 13 Oct". */
export function dayLabel(key: string, now: Date = new Date()): string {
  const [y, m, d] = key.split('-').map(Number)
  const day = new Date(y, m - 1, d)
  const diff = Math.round((startOfDay(now).getTime() - day.getTime()) / 86_400_000)
  if (diff === 0) return 'Today'
  if (diff === 1) return 'Yesterday'
  return day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: day.getFullYear() === now.getFullYear() ? undefined : 'numeric' })
}
