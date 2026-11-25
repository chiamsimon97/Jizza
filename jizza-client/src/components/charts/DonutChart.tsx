import { ChartFrame, Swatch, colourAt } from './ChartFrame'
import { formatINRShort, formatPercent } from '@/lib/money'

export interface DonutPart {
  label: string
  value: number
  colour?: string
}

interface DonutChartProps {
  title: string
  description?: string
  parts: DonutPart[]
  /** Big figure in the middle; defaults to the total. */
  centre?: string
  format?: (n: number) => string
  size?: number
}

/** Parts of one whole, such as a plan's allocation by asset class. Negative parts are ignored. */
export function DonutChart({ title, description, parts, centre, format = formatINRShort, size = 160 }: DonutChartProps) {
  const shown = parts.filter(p => p.value > 0)
  const total = shown.reduce((sum, p) => sum + p.value, 0)
  const r = 15.915 // circumference of 100, so dash lengths are percentages
  let offset = 25 // start at twelve o'clock

  const table = {
    columns: ['Part', 'Amount', 'Share'],
    rows: shown.map(p => [p.label, format(p.value), formatPercent(total ? p.value / total : 0)]),
  }

  return (
    <ChartFrame title={title} description={description} table={table}>
      <div className="flex flex-wrap items-center gap-4">
        <svg viewBox="0 0 42 42" width={size} height={size} className="shrink-0">
          <circle cx="21" cy="21" r={r} fill="none" stroke="rgb(var(--gray-200))" strokeWidth="5" />
          {total > 0 && shown.map((p, i) => {
            const pct = (p.value / total) * 100
            const segment = (
              <circle key={p.label} cx="21" cy="21" r={r} fill="none"
                stroke={p.colour ?? colourAt(i)} strokeWidth="5"
                strokeDasharray={`${pct} ${100 - pct}`} strokeDashoffset={offset} />
            )
            offset -= pct
            return segment
          })}
          <text x="21" y="22.5" textAnchor="middle" className="fill-gray-900 dark:fill-gray-100" style={{ fontSize: 5, fontWeight: 600 }}>
            {centre ?? format(total)}
          </text>
        </svg>
        <ul className="flex min-w-0 flex-col gap-1.5">
          {shown.map((p, i) => (
            <li key={p.label}>
              <Swatch colour={p.colour ?? colourAt(i)} label={p.label}
                detail={`${formatPercent(total ? p.value / total : 0)} · ${format(p.value)}`} />
            </li>
          ))}
        </ul>
      </div>
    </ChartFrame>
  )
}
