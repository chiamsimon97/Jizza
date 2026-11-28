import { useState } from 'react'
import { ArrowDown, ArrowUp, Minus, OctagonAlert } from 'lucide-react'
import { Pill } from '@/portfolio/components/Panel'
import { formatDay } from '@/portfolio/components/AsOf'
import type { LabFlag, LabTarget, LabTrend, LabTrendPoint } from '@/types/vita'
import { NeedsVerification } from './FigureLabel'
import { StatusBadge, TargetLabel, bandsFromText } from './Readings'

const FLAG_ICON = { high: ArrowUp, low: ArrowDown, critical: OctagonAlert, normal: Minus, unknown: Minus } as const
const FLAG_TONE = { high: 'warn', low: 'warn', critical: 'bad', normal: 'neutral', unknown: 'neutral' } as const

/** The lab's own flag, in the server's words ("Lab: high (4.0–5.6)") with an icon, never colour alone. */
export function LabFlagPill({ flag, label }: { flag: LabFlag; label: string }) {
  const Icon = FLAG_ICON[flag] ?? Minus
  return <Pill tone={FLAG_TONE[flag] ?? 'neutral'}><Icon className="h-3 w-3" aria-hidden />{label}</Pill>
}

/** The target side of a lab value: its status and who set the target, or "Not set: ask your doctor". */
export function LabTargetCell({ target }: { target: LabTarget }) {
  if (target.status === 'not_set') return <TargetLabel source={null} label={target.label} />
  return (
    <span className="flex flex-col items-start gap-1">
      {target.status !== 'set' && <StatusBadge status={target.status} outside={target.outside} />}
      <TargetLabel source={target.source} label={target.label} needsVerification={target.needsVerification} range={target.range} />
    </span>
  )
}

/** "Printed 3.1 mmol/L, shown in mg/dL" with the badge while the conversion factor is unchecked. */
export function ConvertedNote({ shown }: { shown: LabTrendPoint['shown'] }) {
  if (!shown.converted) return null
  return (
    <span className="inline-flex flex-wrap items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
      Printed {shown.asPrinted}, shown in {shown.unit}{shown.needsVerification && <NeedsVerification />}
    </span>
  )
}

function numeric(p: LabTrendPoint): number | null {
  if (!p.comparable || p.shown.value == null) return null
  const n = Number(p.shown.value)
  return Number.isFinite(n) ? n : null
}

/**
 * One test across reports: a hand-drawn line with the target as a band where the range parses, each
 * point shaped by its target status (dot in range, ring outside, diamond not set), and a table view.
 */
export function LabChart({ trend, width = 640, height = 180 }: { trend: LabTrend; width?: number; height?: number }) {
  const [table, setTable] = useState(false)
  const pts = trend.points.map(p => ({ p, v: numeric(p) })).filter((x): x is { p: LabTrendPoint; v: number } => x.v != null)
  const band = trend.target.status !== 'not_set' ? bandsFromText(trend.target.range)?.[0] ?? null : null
  const unit = trend.test.unit
  const pad = { l: 44, r: 16, t: 14, b: 28 }
  const values = [...pts.map(x => x.v), ...(band ? [band.low, band.high].filter((x): x is number => x != null) : [])]
  const min = Math.min(...values), max = Math.max(...values)
  const span = max - min || Math.abs(max) || 1
  const lo = min - span * 0.15, hi = max + span * 0.15
  const x = (i: number) => pad.l + (pts.length < 2 ? (width - pad.l - pad.r) / 2 : (i * (width - pad.l - pad.r)) / (pts.length - 1))
  const y = (v: number) => pad.t + (1 - (v - lo) / (hi - lo)) * (height - pad.t - pad.b)
  const summary = `${trend.test.name} across ${pts.length} ${pts.length === 1 ? 'report' : 'reports'}`

  return (
    <div className="flex flex-col gap-2">
      {pts.length > 0 && !table && (
        <svg role="img" aria-label={summary} viewBox={`0 0 ${width} ${height}`} className="h-auto w-full text-gray-400">
          {band && (
            <rect x={pad.l} width={width - pad.l - pad.r}
              y={y(band.high ?? hi)} height={Math.max(0, y(band.low ?? lo) - y(band.high ?? hi))}
              className="fill-emerald-500/10 dark:fill-emerald-400/10" />
          )}
          {[lo + (hi - lo) * 0.1, (lo + hi) / 2, hi - (hi - lo) * 0.1].map(t => (
            <g key={t}>
              <line x1={pad.l} x2={width - pad.r} y1={y(t)} y2={y(t)} stroke="currentColor" strokeOpacity={0.15} />
              <text x={pad.l - 6} y={y(t) + 4} textAnchor="end" className="fill-gray-500 text-[10px] dark:fill-gray-400">{Number(t.toFixed(trend.test.decimals))}</text>
            </g>
          ))}
          <polyline fill="none" stroke="rgb(var(--agent-health))" strokeWidth={2} points={pts.map((q, i) => `${x(i)},${y(q.v)}`).join(' ')} />
          {pts.map((q, i) => {
            const s = q.p.target.status
            const cx = x(i), cy = y(q.v)
            return (
              <g key={q.p.id}>
                {s === 'outside_range'
                  ? <circle cx={cx} cy={cy} r={5} fill="white" stroke="rgb(var(--agent-health))" strokeWidth={2.5} className="dark:fill-gray-900" />
                  : s === 'in_range'
                    ? <circle cx={cx} cy={cy} r={4.5} fill="rgb(var(--agent-health))" />
                    : <rect x={cx - 4} y={cy - 4} width={8} height={8} transform={`rotate(45 ${cx} ${cy})`} fill="rgb(var(--agent-health))" />}
                <text x={cx} y={height - 8} textAnchor={pts.length > 1 && i === 0 ? 'start' : pts.length > 1 && i === pts.length - 1 ? 'end' : 'middle'} className="fill-gray-500 text-[10px] dark:fill-gray-400">{formatDay(q.p.date)}</text>
              </g>
            )
          })}
        </svg>
      )}
      {pts.length > 0 && !table && (
        <p className="flex flex-wrap gap-3 text-xs text-gray-500 dark:text-gray-400">
          <span className="inline-flex items-center gap-1"><span aria-hidden className="inline-block h-2.5 w-2.5 rounded-full bg-[rgb(var(--agent-health))]" />Within target</span>
          <span className="inline-flex items-center gap-1"><span aria-hidden className="inline-block h-2.5 w-2.5 rounded-full border-2 border-[rgb(var(--agent-health))]" />Outside target</span>
          <span className="inline-flex items-center gap-1"><span aria-hidden className="inline-block h-2 w-2 rotate-45 bg-[rgb(var(--agent-health))]" />No target</span>
          {band && <span className="inline-flex items-center gap-1"><span aria-hidden className="inline-block h-2.5 w-4 rounded-sm bg-emerald-500/20" />Target band</span>}
        </p>
      )}
      {table && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs text-gray-500 dark:text-gray-400">
              <tr><th className="py-1 pr-3 font-medium">Date</th><th className="py-1 pr-3 font-medium">Result</th><th className="py-1 pr-3 font-medium">Lab flag</th><th className="py-1 font-medium">Target</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
              {[...trend.points].reverse().map(p => (
                <tr key={p.id}>
                  <td className="py-1.5 pr-3 align-top text-gray-600 dark:text-gray-300">{formatDay(p.date)}{p.labName ? <span className="block text-xs text-gray-500 dark:text-gray-400">{p.labName}</span> : null}</td>
                  <td className="py-1.5 pr-3 align-top font-mono">{p.shown.value} <span className="text-xs text-gray-500 dark:text-gray-400">{p.shown.unit ?? unit}</span></td>
                  <td className="py-1.5 pr-3 align-top"><LabFlagPill flag={p.labFlag.flag} label={p.labFlag.label} /></td>
                  <td className="py-1.5 align-top">{p.target.status === 'not_set' ? <span className="text-xs text-gray-600 dark:text-gray-300">{p.target.label}</span> : <StatusBadge status={p.target.status === 'set' ? null : p.target.status} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!pts.length && !table && <p className="text-sm text-gray-500 dark:text-gray-400">No values in {unit} to draw yet. The table lists what each report printed.</p>}
      <div>
        <button type="button" onClick={() => setTable(!table)} className="text-xs text-brand-700 underline dark:text-brand-300">{table ? 'Show as a chart' : 'Show as a table'}</button>
      </div>
    </div>
  )
}
