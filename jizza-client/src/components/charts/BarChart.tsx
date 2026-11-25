import { ChartFrame, colourAt } from './ChartFrame'
import { formatINRShort } from '@/lib/money'

export interface Bar {
  label: string
  value: number
  /** A target to mark on the bar, such as a goal's required amount. */
  target?: number
  colour?: string
}

interface BarChartProps {
  title: string
  description?: string
  bars: Bar[]
  format?: (n: number) => string
  /** Start on the table view. */
  initialTable?: boolean
}

/**
 * One measure across items, as horizontal bars (labels stay readable on a phone). Negative values
 * grow left from a zero line, so a shortfall and a surplus read at a glance.
 */
export function BarChart({ title, description, bars, format = formatINRShort, initialTable = false }: BarChartProps) {
  const values = bars.flatMap(b => [b.value, b.target ?? 0])
  const max = Math.max(0, ...values)
  const min = Math.min(0, ...values)
  const span = max - min || 1
  const zero = ((0 - min) / span) * 100
  const pos = (v: number) => ((v - min) / span) * 100

  const hasTargets = bars.some(b => b.target != null)
  const table = {
    columns: hasTargets ? ['Item', 'Value', 'Target'] : ['Item', 'Value'],
    rows: bars.map(b => hasTargets ? [b.label, format(b.value), b.target != null ? format(b.target) : '—'] : [b.label, format(b.value)]),
  }

  return (
    <ChartFrame title={title} description={description} table={table} initialView={initialTable ? 'table' : 'chart'}>
      <ul className="flex flex-col gap-2">
        {bars.map((b, i) => {
          const left = Math.min(zero, pos(b.value))
          const width = Math.abs(pos(b.value) - zero)
          return (
            <li key={b.label} className="grid grid-cols-[minmax(0,8rem)_1fr_auto] items-center gap-2 text-xs">
              <span className="truncate text-gray-600 dark:text-gray-300" title={b.label}>{b.label}</span>
              <svg viewBox="0 0 100 10" preserveAspectRatio="none" className="h-3 w-full">
                <rect x="0" y="0" width="100" height="10" rx="2" fill="rgb(var(--gray-100))" />
                <rect x={left} y="0" width={width} height="10" rx="2"
                  fill={b.value < 0 ? 'rgb(var(--agent-health))' : (b.colour ?? colourAt(i))} />
                {min < 0 && <line x1={zero} x2={zero} y1="0" y2="10" stroke="rgb(var(--gray-500))" strokeWidth="0.6" />}
                {b.target != null && (
                  <line x1={pos(b.target)} x2={pos(b.target)} y1="-1" y2="11" stroke="rgb(var(--gray-900))" strokeWidth="1"
                    className="dark:[stroke:rgb(var(--gray-100))]" />
                )}
              </svg>
              <span className="tabular-nums text-gray-800 dark:text-gray-200">{format(b.value)}</span>
            </li>
          )
        })}
      </ul>
    </ChartFrame>
  )
}
