import { useEffect, useRef, useState } from 'react'
import { CalendarClock, ChevronDown, RotateCcw } from 'lucide-react'
import { PRESETS, describeRange, resolveRange, type TimeRange } from '@/lib/timeRange'

/** "2026-10-22T14:05", the value a datetime-local input takes, in the viewer's zone. */
function toLocalInput(d?: Date): string {
  if (!d) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

interface TimeRangePickerProps {
  value: TimeRange
  onChange: (range: TimeRange) => void
  /** Shown as a one-click way back when the range was zoomed into. */
  defaultRange?: TimeRange
}

/**
 * Quick presets on the left, exact bounds on the right. Presets keep sliding with the clock;
 * exact bounds stay put, which is also what zooming into the timeline produces.
 */
export function TimeRangePicker({ value, onChange, defaultRange }: TimeRangePickerProps) {
  const [open, setOpen] = useState(false)
  const resolved = resolveRange(value)
  const [from, setFrom] = useState(toLocalInput(resolved.from))
  const [to, setTo] = useState(toLocalInput(resolved.to))
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const r = resolveRange(value)
    setFrom(toLocalInput(r.from))
    setTo(toLocalInput(r.to))
    const onDown = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey) }
  }, [open, value])

  const custom = !value.preset
  const zoomed = custom && defaultRange != null

  return (
    <div ref={box} className="relative flex items-center gap-1">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="inline-flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 shadow-sm transition-all hover:border-gray-400 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200"
      >
        <CalendarClock className="h-4 w-4 text-brand-500" />
        <span className="max-w-[16rem] truncate">{describeRange(value)}</span>
        <ChevronDown className={`h-3.5 w-3.5 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {zoomed && (
        <button type="button" onClick={() => onChange(defaultRange!)} title="Back to the default range" className="grid h-8 w-8 place-items-center rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:hover:bg-gray-800 dark:hover:text-gray-100">
          <RotateCcw className="h-4 w-4" />
        </button>
      )}

      {open && (
        <div role="dialog" aria-label="Time range" className="absolute left-0 top-full z-30 mt-2 flex w-[min(34rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-xl border border-gray-200 bg-white shadow-xl animate-fade-in sm:flex-row dark:border-gray-800 dark:bg-gray-900">
          <ul className="grid grid-cols-2 gap-0.5 border-b border-gray-100 p-2 sm:w-48 sm:grid-cols-1 sm:border-b-0 sm:border-r dark:border-gray-800">
            {PRESETS.map(p => (
              <li key={p.key}>
                <button
                  type="button"
                  onClick={() => { onChange({ preset: p.key }); setOpen(false) }}
                  className={`w-full rounded-md px-2.5 py-1.5 text-left text-sm transition-colors ${
                    value.preset === p.key
                      ? 'bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200'
                      : 'text-gray-600 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-gray-800'
                  }`}
                >
                  {p.label}
                </button>
              </li>
            ))}
          </ul>
          <form
            className="flex flex-1 flex-col gap-3 p-4"
            onSubmit={e => {
              e.preventDefault()
              onChange({
                from: from ? new Date(from).toISOString() : undefined,
                to: to ? new Date(to).toISOString() : undefined,
              })
              setOpen(false)
            }}
          >
            <p className="text-xs font-semibold uppercase tracking-wide text-gray-400">Exact range</p>
            <label className="flex flex-col gap-1 text-xs text-gray-500">
              From
              <input type="datetime-local" value={from} max={to || undefined} onChange={e => setFrom(e.target.value)} className="rounded-lg border border-gray-300 bg-white px-2.5 py-1.5 text-sm text-gray-900 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100" />
            </label>
            <label className="flex flex-col gap-1 text-xs text-gray-500">
              To <span className="text-gray-400">(leave empty for now)</span>
              <input type="datetime-local" value={to} min={from || undefined} onChange={e => setTo(e.target.value)} className="rounded-lg border border-gray-300 bg-white px-2.5 py-1.5 text-sm text-gray-900 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-100" />
            </label>
            <p className="text-xs text-gray-400">Tip: drag across the timeline to zoom into a stretch of time.</p>
            <div className="mt-auto flex justify-end gap-2">
              <button type="button" onClick={() => setOpen(false)} className="rounded-lg px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800">Cancel</button>
              <button type="submit" disabled={!from && !to} className={`rounded-lg px-3 py-1.5 text-sm font-medium text-white shadow-sm disabled:opacity-50 ${custom ? 'bg-brand-600' : 'bg-brand-500'} hover:bg-brand-600`}>Apply</button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
