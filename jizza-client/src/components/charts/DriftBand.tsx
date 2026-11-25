import { ChartFrame } from './ChartFrame'
import { formatPercent } from '@/lib/money'

export interface DriftRow {
  label: string
  /** Target share of the plan, 0–1. */
  target: number
  /** The band inside which no rebalance is suggested, 0–1. */
  low: number
  high: number
  /** Where the plan actually is, 0–1. */
  actual: number
}

/** Whether a row has left its band, as a word, so the meaning never rests on colour alone. */
export function driftState(r: DriftRow): 'Under' | 'Over' | 'In band' {
  if (r.actual < r.low) return 'Under'
  if (r.actual > r.high) return 'Over'
  return 'In band'
}

/**
 * Allocation against target: each row shows the target, its rebalance band and where the plan
 * actually is. Rows outside their band are marked with a word as well as a colour.
 */
export function DriftBand({ title, description, rows }: { title: string; description?: string; rows: DriftRow[] }) {
  const scale = Math.max(0.01, ...rows.flatMap(r => [r.high, r.actual])) * 1.1
  const pos = (v: number) => (Math.min(v, scale) / scale) * 100

  const table = {
    columns: ['Class', 'Target', 'Band', 'Actual', 'Status'],
    rows: rows.map(r => [r.label, formatPercent(r.target), `${formatPercent(r.low)}–${formatPercent(r.high)}`,
      formatPercent(r.actual), driftState(r)]),
  }

  return (
    <ChartFrame title={title} description={description} table={table}>
      <ul className="flex flex-col gap-2.5">
        {rows.map(r => {
          const state = driftState(r)
          const out = state !== 'In band'
          return (
            <li key={r.label} className="grid grid-cols-[minmax(0,8rem)_1fr_auto] items-center gap-2 text-xs">
              <span className="truncate text-gray-600 dark:text-gray-300" title={r.label}>{r.label}</span>
              <svg viewBox="0 0 100 12" preserveAspectRatio="none" className="h-4 w-full">
                <rect x="0" y="5" width="100" height="2" rx="1" fill="rgb(var(--gray-200))" />
                <rect x={pos(r.low)} y="2" width={Math.max(0.5, pos(r.high) - pos(r.low))} height="8" rx="1.5"
                  fill="rgb(var(--accent-500) / 0.25)" />
                <line x1={pos(r.target)} x2={pos(r.target)} y1="1" y2="11" stroke="rgb(var(--gray-500))" strokeWidth="0.8" />
                <circle cx={pos(r.actual)} cy="6" r="2.6"
                  fill={out ? 'rgb(var(--agent-finance))' : 'rgb(var(--brand-500))'} />
              </svg>
              <span className={`tabular-nums ${out ? 'font-medium text-amber-700 dark:text-amber-400' : 'text-gray-800 dark:text-gray-200'}`}>
                {formatPercent(r.actual)} · {state}
              </span>
            </li>
          )
        })}
      </ul>
    </ChartFrame>
  )
}
