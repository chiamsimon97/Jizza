import { ChartFrame, Swatch, colourAt } from './ChartFrame'
import { formatINRShort } from '@/lib/money'

export interface LineSeries {
  name: string
  /** One value per label in `labels`; null for a gap. */
  values: (number | null)[]
  colour?: string
}

interface LineChartProps {
  title: string
  description?: string
  /** The x axis, in order: months, years. */
  labels: string[]
  series: LineSeries[]
  format?: (n: number) => string
  height?: number
}

const W = 300
const PAD = { top: 8, right: 8, bottom: 18, left: 44 }

/** A level over time, such as net worth by month or the Money Health score. */
export function LineChart({ title, description, labels, series, format = formatINRShort, height = 160 }: LineChartProps) {
  const all = series.flatMap(s => s.values.filter((v): v is number => v != null))
  const rawMin = all.length ? Math.min(...all) : 0
  const rawMax = all.length ? Math.max(...all) : 1
  const ticks = niceTicks(rawMin, rawMax)
  const lo = ticks[0]
  const hi = ticks[ticks.length - 1]
  const plotW = W - PAD.left - PAD.right
  const plotH = height - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (labels.length > 1 ? (i / (labels.length - 1)) * plotW : plotW / 2)
  const y = (v: number) => PAD.top + plotH - ((v - lo) / (hi - lo || 1)) * plotH
  const labelEvery = Math.max(1, Math.ceil(labels.length / 6))

  const table = {
    columns: ['', ...series.map(s => s.name)],
    rows: labels.map((l, i) => [l, ...series.map(s => (s.values[i] == null ? '—' : format(s.values[i] as number)))]),
  }

  return (
    <ChartFrame title={title} description={description} table={table}>
      <svg viewBox={`0 0 ${W} ${height}`} className="h-auto w-full">
        {ticks.map(t => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="rgb(var(--gray-200))" strokeWidth="0.5" />
            <text x={PAD.left - 4} y={y(t) + 3} textAnchor="end" className="fill-gray-500" style={{ fontSize: 8 }}>{format(t)}</text>
          </g>
        ))}
        {labels.map((l, i) => i % labelEvery === 0 && (
          <text key={l} x={x(i)} y={height - 4} textAnchor="middle" className="fill-gray-500" style={{ fontSize: 8 }}>{l}</text>
        ))}
        {series.map((s, si) => (
          <path key={s.name} d={path(s.values, x, y)} fill="none" stroke={s.colour ?? colourAt(si)}
            strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
        ))}
      </svg>
      {series.length > 1 && (
        <div className="mt-1 flex flex-wrap gap-3">
          {series.map((s, si) => <Swatch key={s.name} colour={s.colour ?? colourAt(si)} label={s.name} />)}
        </div>
      )}
    </ChartFrame>
  )
}

/** An SVG path through the points, broken where a value is missing. */
export function path(values: (number | null)[], x: (i: number) => number, y: (v: number) => number): string {
  let d = ''
  let pen = false
  values.forEach((v, i) => {
    if (v == null) { pen = false; return }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`
    pen = true
  })
  return d
}

/** Three to six round axis values covering [min, max]. */
export function niceTicks(min: number, max: number): number[] {
  if (min === max) { min -= 1; max += 1 }
  const rough = (max - min) / 4
  const pow = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 2.5, 5, 10].map(m => m * pow).find(s => s >= rough) ?? 10 * pow
  const start = Math.floor(min / step) * step
  const out: number[] = []
  for (let t = start; t < max + step * 0.999; t += step) out.push(Number(t.toPrecision(12)))
  return out
}
