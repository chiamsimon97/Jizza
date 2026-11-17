import { X } from 'lucide-react'

/** The most taps one step makes (a group's seats). */
export const MAX_TAPS = 20

/** A tap as the card keeps it: "x,y", each a fraction of the picture's width or height. */
export function tapAt(x: number, y: number): string {
  const round = (n: number) => Math.round(Math.min(1, Math.max(0, n)) * 10_000) / 10_000
  return `${round(x)},${round(y)}`
}

export function parseTap(tap: string): { x: number; y: number } | undefined {
  const [x, y] = tap.split(',').map(Number)
  return x === undefined || y === undefined || Number.isNaN(x) || Number.isNaN(y) ? undefined : { x, y }
}

/**
 * A picture of a page area (a seat map) to tap on: each tap is marked and numbered, and the
 * browser clicks the same places in that order. Tapping a mark again takes it away.
 */
export function TapPicture({ src, label, taps, readOnly, onChange }: {
  src: string
  label: string
  taps: string[]
  readOnly?: boolean
  onChange: (taps: string[]) => void
}) {
  const marks = taps.map(parseTap)
  return (
    <div className="space-y-1">
      <div className="relative inline-block max-w-full overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
        <img src={src} alt={label} draggable={false} data-testid="tap-picture"
          className={`block max-h-96 max-w-full select-none ${readOnly ? '' : 'cursor-crosshair'}`}
          onClick={e => {
            if (readOnly || taps.length >= MAX_TAPS) return
            const box = e.currentTarget.getBoundingClientRect()
            if (box.width === 0 || box.height === 0) return
            onChange([...taps, tapAt((e.clientX - box.left) / box.width, (e.clientY - box.top) / box.height)])
          }} />
        {marks.map((m, i) => m && (
          <button key={`${taps[i]}-${i}`} type="button" disabled={readOnly}
            aria-label={`Take away tap ${i + 1}`}
            onClick={() => onChange(taps.filter((_, j) => j !== i))}
            style={{ left: `${m.x * 100}%`, top: `${m.y * 100}%` }}
            className="absolute flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-white bg-brand-600 text-[11px] font-semibold text-white shadow">
            {i + 1}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 text-[11px] text-gray-500 dark:text-gray-400">
        {readOnly ? `${taps.length} tap${taps.length === 1 ? '' : 's'}` : taps.length === 0
          ? 'Tap the places to pick, in order. Tap a number to take it away.'
          : `${taps.length} tap${taps.length === 1 ? '' : 's'}, clicked in this order.`}
        {!readOnly && taps.length > 0 && (
          <button type="button" onClick={() => onChange([])}
            className="inline-flex items-center gap-0.5 text-gray-600 hover:text-gray-900 dark:text-gray-300">
            <X className="h-3 w-3" />Clear
          </button>
        )}
      </div>
    </div>
  )
}
