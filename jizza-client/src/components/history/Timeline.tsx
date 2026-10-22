import { useMemo, useState } from 'react'

export interface TimelineSeries { key: string; label: string; color: string }
export interface TimelineBucket { start: string; values: Record<string, number> }

interface TimelineProps {
  buckets: TimelineBucket[]
  series: TimelineSeries[]
  unit: 'hour' | 'day'
  /** Called with the bounds of the slices clicked or dragged across. */
  onZoom?: (from: string, to: string) => void
  formatValue?: (n: number) => string
  height?: number
  /** Names what a bar counts, for the tooltip: "events", "runs". */
  noun?: string
}

const HOUR = 3600_000

function sliceEnd(buckets: TimelineBucket[], i: number, unit: 'hour' | 'day'): string {
  if (i + 1 < buckets.length) return buckets[i + 1].start
  const start = new Date(buckets[i].start)
  if (unit === 'hour') return new Date(start.getTime() + HOUR).toISOString()
  return new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1).toISOString()
}

function sliceLabel(iso: string, unit: 'hour' | 'day', long = false): string {
  const d = new Date(iso)
  if (unit === 'hour') {
    return long
      ? d.toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
      : d.toLocaleTimeString(undefined, { hour: 'numeric' })
  }
  return d.toLocaleDateString(undefined, long ? { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' } : { day: 'numeric', month: 'short' })
}

/**
 * Stacked bars over time. Hover a bar for its breakdown; click one, or drag across several, to
 * zoom the whole view into that stretch.
 */
export function Timeline({ buckets, series, unit, onZoom, formatValue = n => n.toLocaleString(), height = 88, noun = 'events' }: TimelineProps) {
  const [hover, setHover] = useState<number | null>(null)
  const [drag, setDrag] = useState<{ from: number; to: number } | null>(null)

  const totals = useMemo(() => buckets.map(b => series.reduce((sum, s) => sum + (b.values[s.key] ?? 0), 0)), [buckets, series])
  const max = Math.max(1, ...totals)
  const sum = totals.reduce((a, b) => a + b, 0)
  const selLo = drag ? Math.min(drag.from, drag.to) : -1
  const selHi = drag ? Math.max(drag.from, drag.to) : -1

  const finish = () => {
    if (drag && onZoom) onZoom(buckets[selLo].start, sliceEnd(buckets, selHi, unit))
    setDrag(null)
  }

  if (buckets.length === 0) return null
  const tickEvery = Math.max(1, Math.ceil(buckets.length / 6))
  const shown = hover ?? (drag ? drag.to : null)

  return (
    <div className="select-none">
      <div className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
        {series.filter(s => buckets.some(b => (b.values[s.key] ?? 0) > 0)).map(s => (
          <span key={s.key} className="inline-flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-sm" style={{ backgroundColor: s.color }} />
            {s.label}
          </span>
        ))}
        <span className="ml-auto tabular-nums">{formatValue(sum)} {noun} · per {unit}</span>
      </div>
      <div
        className="relative flex items-end gap-px rounded-lg bg-gray-50 px-1 pt-1 dark:bg-gray-950/50"
        style={{ height }}
        onMouseLeave={() => { setHover(null); if (drag) finish() }}
        onMouseUp={finish}
        role="img"
        aria-label={`Timeline of ${noun}, ${buckets.length} ${unit}s`}
      >
        {buckets.map((b, i) => {
          const selected = drag != null && i >= selLo && i <= selHi
          const pct = (totals[i] / max) * 100
          return (
            <div
              key={b.start}
              className={`group relative flex h-full flex-1 cursor-crosshair flex-col justify-end rounded-sm ${selected ? 'bg-brand-500/15' : hover === i ? 'bg-gray-200/60 dark:bg-gray-800/60' : ''}`}
              onMouseEnter={() => { setHover(i); if (drag) setDrag({ ...drag, to: i }) }}
              onMouseDown={e => { e.preventDefault(); setDrag({ from: i, to: i }) }}
            >
              <div className="flex w-full flex-col-reverse overflow-hidden rounded-t-sm transition-[height] duration-300" style={{ height: `${pct}%`, minHeight: totals[i] > 0 ? 2 : 0 }}>
                {series.map(s => {
                  const v = b.values[s.key] ?? 0
                  if (v === 0) return null
                  return <div key={s.key} style={{ flexGrow: v, backgroundColor: s.color }} className="w-full opacity-90 group-hover:opacity-100" />
                })}
              </div>
            </div>
          )
        })}

        {shown != null && buckets[shown] && (
          <div
            className="pointer-events-none absolute bottom-full z-20 mb-2 w-max min-w-[10rem] -translate-x-1/2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-lg dark:border-gray-700 dark:bg-gray-900"
            style={{ left: `${((shown + 0.5) / buckets.length) * 100}%` }}
          >
            <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">
              {drag && selLo !== selHi
                ? `${sliceLabel(buckets[selLo].start, unit, true)} → ${sliceLabel(sliceEnd(buckets, selHi, unit), unit, true)}`
                : sliceLabel(buckets[shown].start, unit, true)}
            </p>
            {series.map(s => {
              const v = drag && selLo !== selHi
                ? buckets.slice(selLo, selHi + 1).reduce((a, x) => a + (x.values[s.key] ?? 0), 0)
                : buckets[shown].values[s.key] ?? 0
              return v > 0 ? (
                <p key={s.key} className="flex items-center gap-1.5 text-gray-600 dark:text-gray-300">
                  <span className="h-2 w-2 rounded-sm" style={{ backgroundColor: s.color }} />
                  <span className="flex-1">{s.label}</span>
                  <span className="tabular-nums">{formatValue(v)}</span>
                </p>
              ) : null
            })}
            {totals[shown] === 0 && !(drag && selLo !== selHi) && <p className="text-gray-400">Nothing</p>}
            {onZoom && <p className="mt-1 text-[10px] text-gray-400">{drag ? 'Release to zoom in' : 'Click or drag to zoom in'}</p>}
          </div>
        )}
      </div>
      <div className="mt-1 flex px-1 text-[10px] text-gray-400">
        {buckets.map((b, i) => (
          <span key={b.start} className="flex-1 truncate text-center">{i % tickEvery === 0 ? sliceLabel(b.start, unit) : ''}</span>
        ))}
      </div>
    </div>
  )
}
