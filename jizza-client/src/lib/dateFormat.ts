/**
 * Dates in the format a site writes them (DD/MM/YYYY, YYYY-MM-DD HH:mm, DD-MMM-YYYY hh:mm A), as
 * a recorded web step keeps it. A picker gives the date as ISO (2026-11-20 or 2026-11-20T18:30);
 * the browser agent writes that the site's way, and the card shows the person how it will look.
 */

export type DateKind = 'date' | 'datetime'

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December']

export interface DateParts { year: number; month: number; day: number; hour?: number; minute?: number }

/** Whether a format has a time of day in it. */
export function hasTime(format: string | undefined): boolean {
  return !!format && /[Hh]/.test(format)
}

/** The kind of picker a field takes: a date, or a date and time. */
export function kindOf(type: string, format?: string): DateKind {
  return type === 'datetime' || hasTime(format) ? 'datetime' : 'date'
}

const TOKENS = /YYYY|YY|MMMM|MMM|MM|M|DD|D|HH|H|hh|h|mm|ss|A/g

/** Reads `text` written in `format` into its parts; undefined when it does not follow it. */
export function parseWithFormat(text: string, format: string): DateParts | undefined {
  const order = (format.match(TOKENS) ?? []).map(t => (/^M{3,}$/.test(t) ? 'N' : t.replace(/^(.)\1+$/, '$1')))
  const parts = text.match(/\d+|[A-Za-z]+/g) ?? []
  if (order.length < 3 || parts.length < 3) return undefined
  const get = (token: string) => {
    const i = order.indexOf(token)
    return i >= 0 ? parts[i] : undefined
  }
  const name = get('N')?.toLowerCase()
  const month = name
    ? MONTHS.findIndex(m => name.length >= 3 && m.toLowerCase().startsWith(name.slice(0, 3))) + 1
    : Number(get('M'))
  let year = Number(get('Y'))
  const day = Number(get('D'))
  if (!year || !month || !day || month > 12 || day > 31) return undefined
  if (year < 100) year += 2000
  const hourText = get('H') ?? get('h')
  let hour = hourText === undefined ? undefined : Number(hourText)
  const meridiem = get('A')?.toLowerCase()
  if (hour !== undefined && meridiem === 'pm' && hour < 12) hour += 12
  if (hour !== undefined && meridiem === 'am' && hour === 12) hour = 0
  const minute = hour === undefined ? undefined : Number(get('m') ?? 0)
  return { year, month, day, ...(hour !== undefined ? { hour, minute } : {}) }
}

/** Reads a date written as ISO, or day first (20/11/2026, 20 Nov 2026), with an optional time. */
export function parseAny(text: string): DateParts | undefined {
  const t = text.trim()
  let m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{1,2}):(\d{2}))?/.exec(t)
  if (m) {
    return { year: +m[1]!, month: +m[2]!, day: +m[3]!, ...(m[4] ? { hour: +m[4], minute: +m[5]! } : {}) }
  }
  m = /^(\d{1,2})[/.\- ]([A-Za-z]{3,}|\d{1,2})[/.\- ,]+(\d{4}|\d{2})(?:[ ,T]+(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?)?$/.exec(t)
  if (!m) return undefined
  const named = /[A-Za-z]/.test(m[2]!)
  const fmt = `D-${named ? 'MMM' : 'M'}-YYYY${m[4] ? ` ${m[6] ? 'h' : 'H'}:mm${m[6] ? ' A' : ''}` : ''}`
  return parseWithFormat(t, fmt)
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

/** The parts as ISO: 2026-11-20, or 2026-11-20T18:30 for a date and time. */
export function isoOf(parts: DateParts, kind: DateKind): string {
  const date = `${pad(parts.year, 4)}-${pad(parts.month)}-${pad(parts.day)}`
  return kind === 'datetime' ? `${date}T${pad(parts.hour ?? 0)}:${pad(parts.minute ?? 0)}` : date
}

/**
 * The value a date picker shows for `value`: read in the site's format first, else as ISO or day
 * first. Empty when it is not a date (a {{ mapping }} or free text), so the text is kept.
 */
export function pickerValue(value: unknown, kind: DateKind, format?: string): string {
  if (typeof value !== 'string' || !value.trim() || value.includes('{{')) return ''
  const parts = (format ? parseWithFormat(value, format) : undefined) ?? parseAny(value)
  return parts ? isoOf(parts, kind) : ''
}

/** Writes ISO (from a picker) in the site's format: 2026-11-20T18:30 → 20/11/2026 06:30 PM. */
export function formatDate(iso: string, format: string): string {
  const parts = parseAny(iso)
  if (!parts) return iso
  const hour = parts.hour ?? 0
  const twelve = hour % 12 === 0 ? 12 : hour % 12
  const values: Record<string, string> = {
    YYYY: pad(parts.year, 4), YY: pad(parts.year % 100), MMMM: MONTHS[parts.month - 1]!,
    MMM: MONTHS[parts.month - 1]!.slice(0, 3), MM: pad(parts.month), M: String(parts.month),
    DD: pad(parts.day), D: String(parts.day), HH: pad(hour), H: String(hour), hh: pad(twelve),
    h: String(twelve), mm: pad(parts.minute ?? 0), ss: '00', A: hour < 12 ? 'AM' : 'PM',
  }
  return format.replace(TOKENS, t => values[t] ?? t)
}
