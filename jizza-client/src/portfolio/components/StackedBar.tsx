import { ChartFrame } from '@/components/charts/ChartFrame'
import { formatINR } from '@/lib/money'

export interface Segment {
  label: string
  value: number
  /** A Tailwind background class, so the colour follows the theme. */
  className: string
}

/** One bar split into parts (needs, wants, saved), each labelled with its share; a table view too. */
export function StackedBar({ title, description, segments, format = (n: number) => formatINR(n) }: {
  title: string
  description?: string
  segments: Segment[]
  format?: (n: number) => string
}) {
  const total = segments.reduce((s, x) => s + Math.max(0, x.value), 0)
  const pct = (v: number) => (total > 0 ? (Math.max(0, v) / total) * 100 : 0)
  return (
    <ChartFrame title={title} description={description}
      table={{ columns: ['Part', 'Amount', 'Share'], rows: segments.map(s => [s.label, format(s.value), `${pct(s.value).toFixed(1)}%`]) }}>
      <div className="flex h-6 w-full overflow-hidden rounded-md bg-gray-100 dark:bg-gray-800">
        {segments.filter(s => s.value > 0).map(s => (
          <div key={s.label} className={`${s.className} flex items-center justify-center overflow-hidden whitespace-nowrap px-1 text-[11px] font-medium text-white`}
            style={{ width: `${pct(s.value)}%` }}>
            {pct(s.value) >= 12 ? `${s.label} ${Math.round(pct(s.value))}%` : ''}
          </div>
        ))}
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {segments.map(s => (
          <span key={s.label} className="inline-flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
            <span className={`inline-block h-2.5 w-2.5 shrink-0 rounded-sm ${s.className}`} />
            {s.label} <span className="tabular-nums text-gray-500 dark:text-gray-400">{format(s.value)}</span>
          </span>
        ))}
      </div>
    </ChartFrame>
  )
}
