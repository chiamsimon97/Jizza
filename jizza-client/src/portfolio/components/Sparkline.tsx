import { useId, useState } from 'react'

/**
 * A small trend line with no axes and a soft fill under it; its label carries the figures for
 * screen readers. Pointing at it shows the value (and its date, when given) of the nearest point.
 */
export function Sparkline({ values, label, width = 120, height = 32, dates, format = v => String(Math.round(v)) }: {
  values: number[]
  label: string
  width?: number
  height?: number
  dates?: string[]
  format?: (v: number) => string
}) {
  const gid = useId().replace(/:/g, '')
  const [hover, setHover] = useState<number | null>(null)
  if (values.length < 2) return null
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const pad = 4
  const x = (i: number) => (i / (values.length - 1)) * (width - pad * 2) + pad
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2)
  const points = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const area = `M${x(0).toFixed(1)},${height} L${points.replace(/ /g, ' L')} L${x(values.length - 1).toFixed(1)},${height} Z`
  const at = hover ?? values.length - 1
  const pick = (clientX: number, rect: DOMRect) => {
    const rel = ((clientX - rect.left) / rect.width) * width
    setHover(Math.max(0, Math.min(values.length - 1, Math.round(((rel - pad) / (width - pad * 2)) * (values.length - 1)))))
  }
  return (
    <div className="relative shrink-0">
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label} className="overflow-visible"
        onMouseMove={e => pick(e.clientX, e.currentTarget.getBoundingClientRect())} onMouseLeave={() => setHover(null)}>
        <defs>
          <linearGradient id={`spark-${gid}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" className="[stop-color:rgb(var(--brand-500))]" stopOpacity="0.28" />
            <stop offset="100%" className="[stop-color:rgb(var(--brand-500))]" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={area} fill={`url(#spark-${gid})`} />
        <polyline points={points} fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="stroke-brand-500" />
        {hover != null && <line x1={x(at)} x2={x(at)} y1={0} y2={height} strokeDasharray="2 3" className="stroke-gray-300 dark:stroke-gray-600" />}
        <circle cx={x(at)} cy={y(values[at])} r="5" className="fill-brand-500/20" />
        <circle cx={x(at)} cy={y(values[at])} r="2.75" className="fill-brand-500 stroke-white dark:stroke-gray-900" strokeWidth="1.5" />
      </svg>
      {hover != null && (
        <div aria-hidden className="pointer-events-none absolute -top-8 z-10 -translate-x-1/2 whitespace-nowrap rounded-md bg-gray-900 px-2 py-0.5 text-xs font-medium text-white shadow-lg dark:bg-gray-100 dark:text-gray-900"
          style={{ left: x(at) }}>
          {format(values[hover])}{dates?.[hover] ? ` · ${dates[hover]}` : ''}
        </div>
      )}
    </div>
  )
}
