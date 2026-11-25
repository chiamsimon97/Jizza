/** A small trend line with no axes; its label carries the figures for screen readers. */
export function Sparkline({ values, label, width = 120, height = 32 }: { values: number[]; label: string; width?: number; height?: number }) {
  if (values.length < 2) return null
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const x = (i: number) => (i / (values.length - 1)) * (width - 4) + 2
  const y = (v: number) => height - 2 - ((v - min) / span) * (height - 4)
  const points = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label} className="shrink-0">
      <polyline points={points} fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="stroke-brand-500" />
      <circle cx={x(values.length - 1)} cy={y(values[values.length - 1])} r="2.5" className="fill-brand-500" />
    </svg>
  )
}
