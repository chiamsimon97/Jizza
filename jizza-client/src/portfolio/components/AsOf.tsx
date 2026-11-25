const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "2026-11-25" → "25 Nov 2026"; "2026-11" → "Nov 2026". Dates are read as written, never shifted by time zone. */
export function formatDay(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  if (!y || !m) return iso
  return d ? `${d} ${MONTHS[m - 1]} ${y}` : `${MONTHS[m - 1]} ${y}`
}

/** "2026-11" → "Nov 2026". */
export function formatMonth(ym: string | null | undefined): string {
  return ym ? formatDay(ym.slice(0, 7)) : '—'
}

/** Whole days from today to a date; negative when it has passed. */
export function daysUntil(iso: string, today = new Date()): number {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  const then = Date.UTC(y, m - 1, d)
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((then - now) / 86_400_000)
}

/** The date every figure is worked out on. */
export function AsOf({ date, className = '' }: { date: string | null | undefined; className?: string }) {
  return <span className={`text-xs text-gray-500 dark:text-gray-400 ${className}`}>As of {formatDay(date)}</span>
}
