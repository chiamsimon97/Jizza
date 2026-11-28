import { AlertCircle, CheckCircle2, HelpCircle, Siren, Stethoscope, UserRound } from 'lucide-react'
import { Pill } from '@/portfolio/components/Panel'
import type { ReadingKind, TargetStatus, TrendPoint } from '@/types/vita'
import type { Units } from '../data'
import { formatWhen } from '../data'
import { STATUS_WORDS, cToF, showValue } from '../readings'
import { NeedsVerification } from './FigureLabel'

const STATUS_ICON = { in_range: CheckCircle2, outside_range: AlertCircle, red_flag: Siren, not_set: HelpCircle }
const STATUS_TONE = { in_range: 'ok', outside_range: 'warn', red_flag: 'bad', not_set: 'neutral' } as const

/** Target status in words with an icon: never colour alone (spec 7.7). */
export function StatusBadge({ status, outside }: { status: TargetStatus | null | undefined; outside?: string[] }) {
  const s = status ?? 'not_set'
  const Icon = STATUS_ICON[s]
  const extra = s === 'outside_range' && outside?.length ? `: ${outside.join(', ')}` : ''
  return <Pill tone={STATUS_TONE[s]}><Icon className="h-3 w-3" aria-hidden />{STATUS_WORDS[s]}{extra}</Pill>
}

/**
 * Who set the range a status was measured against, in the server's words: "from Dr …, set on …",
 * "Your own range", "Vita default" with the needs-verification badge, or "Not set: ask your doctor".
 */
export function TargetLabel({ source, label, needsVerification, range }: {
  source: string | null | undefined
  label: string
  needsVerification?: boolean
  range?: string | null
}) {
  if (!source || source === 'not_set') {
    return <span className="inline-flex items-center gap-1 text-xs font-medium text-gray-600 dark:text-gray-300"><HelpCircle className="h-3 w-3" aria-hidden />{label}</span>
  }
  const Icon = source === 'doctor' ? Stethoscope : source === 'own' ? UserRound : null
  const words = label.replace(/,?\s*needs verification$/i, '')
  return (
    <span className="inline-flex flex-wrap items-center gap-1 text-xs text-gray-600 dark:text-gray-300">
      {range && <span>Target <b className="font-mono font-medium text-gray-800 dark:text-gray-100">{range}</b> ·</span>}
      {Icon && <Icon className="h-3 w-3 text-brand-600 dark:text-brand-300" aria-hidden />}
      {words}
      {needsVerification && <NeedsVerification />}
    </span>
  )
}

export interface Band { low: number | null; high: number | null }

/**
 * The bounds in a range the server wrote in words ("below 140/90", "80–130", "7.0 or lower",
 * "from 90 to 180"), for drawing the band only; the words themselves are always shown beside it.
 * Returns one band per value (two for blood pressure), or null when the words don't parse.
 */
export function bandsFromText(text: string | null | undefined): Band[] | null {
  if (!text) return null
  const t = text.trim()
  const pair = t.match(/^(below |)([\d.]+)\/([\d.]+)( or lower|)$/)
  if (pair) return [{ low: null, high: Number(pair[2]) }, { low: null, high: Number(pair[3]) }]
  const parts = t.split(' / ')
  const bands = parts.map(one)
  return bands.every(Boolean) ? bands as Band[] : null
}

function one(t: string): Band | null {
  let m = t.match(/^([\d.]+)–([\d.]+)$/)
  if (m) return { low: Number(m[1]), high: Number(m[2]) }
  m = t.match(/^(?:from|above) ([\d.]+) (?:to|and below) ([\d.]+)$/)
  if (m) return { low: Number(m[1]), high: Number(m[2]) }
  m = t.match(/^(?:below )?([\d.]+)(?: or lower)?$/)
  if (m && (t.startsWith('below') || t.endsWith('or lower'))) return { low: null, high: Number(m[1]) }
  m = t.match(/^(?:above )?([\d.]+)(?: or higher)?$/)
  if (m && (t.startsWith('above') || t.endsWith('or higher'))) return { low: Number(m[1]), high: null }
  return null
}

function toShown(kind: ReadingKind, v: number, units: Units) {
  if (kind === 'weight' && units.weight === 'lb') return v / 0.45359237
  if (kind === 'temperature' && units.temperature === 'F') return cToF(v)
  return v
}

/**
 * The Vitals chart: readings over time with the target as a shaded band. Status is drawn by shape
 * too (filled dot in range, ring outside, diamond for a red flag), and every chart has a table.
 */
export function TrendChart({ kind, points, bands, units, label, width = 640, height = 200 }: {
  kind: ReadingKind
  points: TrendPoint[]
  bands: Band[] | null
  units: Units
  label: string
  width?: number
  height?: number
}) {
  const pts = [...points].sort((a, b) => a.takenAt.localeCompare(b.takenAt))
  if (!pts.length) return null
  const v1 = pts.map(p => toShown(kind, p.value, units))
  const v2 = pts.map(p => (p.value2 == null ? null : toShown(kind, p.value2, units)))
  const b = (bands ?? []).map(x => ({ low: x.low == null ? null : toShown(kind, x.low, units), high: x.high == null ? null : toShown(kind, x.high, units) }))
  const all = [...v1, ...v2.filter((x): x is number => x != null), ...b.flatMap(x => [x.low, x.high]).filter((x): x is number => x != null)]
  let min = Math.min(...all)
  let max = Math.max(...all)
  const padV = (max - min || Math.max(1, max * 0.1)) * 0.15
  min -= padV; max += padV
  const L = 40, R = 10, T = 10, B = 24
  const t0 = new Date(pts[0].takenAt).getTime()
  const t1 = new Date(pts[pts.length - 1].takenAt).getTime()
  const x = (iso: string) => pts.length === 1 ? (L + width - R) / 2 : L + ((new Date(iso).getTime() - t0) / (t1 - t0 || 1)) * (width - L - R)
  const y = (v: number) => T + (1 - (v - min) / (max - min || 1)) * (height - T - B)
  const ticks = [min + padV, (min + max) / 2, max - padV]
  const fmt = (v: number) => (kind === 'weight' || kind === 'temperature' ? v.toFixed(1) : String(Math.round(v)))
  const mark = (cx: number, cy: number, status: TargetStatus | null, key: string, series: 1 | 2) => {
    const cls = series === 1 ? 'fill-[rgb(var(--agent-health))] stroke-[rgb(var(--agent-health))]' : 'fill-gray-500 stroke-gray-500 dark:fill-gray-400 dark:stroke-gray-400'
    if (status === 'red_flag') return <path key={key} d={`M${cx},${cy - 6} L${cx + 6},${cy} L${cx},${cy + 6} L${cx - 6},${cy} Z`} className="fill-red-600 stroke-red-800 dark:fill-red-500" />
    if (status === 'outside_range') return <circle key={key} cx={cx} cy={cy} r={4} strokeWidth={2} className={`${cls} !fill-white dark:!fill-gray-900`} />
    return <circle key={key} cx={cx} cy={cy} r={3.5} className={cls} />
  }
  const line = (vals: (number | null)[]) => pts.map((p, i) => (vals[i] == null ? null : `${x(p.takenAt).toFixed(1)},${y(vals[i] as number).toFixed(1)}`)).filter(Boolean).join(' ')
  const unit = showValue(kind, 0, 0, units).replace(/^[\d/.–-]+\s?/, '')

  return (
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label} className="h-auto w-full max-w-full" preserveAspectRatio="xMidYMid meet">
      {b.map((band, i) => {
        const top = y(band.high ?? max)
        const bottom = y(band.low ?? min)
        return <rect key={i} x={L} width={width - L - R} y={top} height={Math.max(0, bottom - top)}
          className={i === 0 ? 'fill-emerald-500/10 dark:fill-emerald-400/10' : 'fill-sky-500/10 dark:fill-sky-400/10'} />
      })}
      {b.map((band, i) => [band.low, band.high].map((v, j) => v == null ? null : (
        <line key={`${i}-${j}`} x1={L} x2={width - R} y1={y(v)} y2={y(v)} strokeDasharray="4 3" className="stroke-emerald-600/60 dark:stroke-emerald-400/60" />
      )))}
      {ticks.map(v => (
        <g key={v}>
          <line x1={L} x2={width - R} y1={y(v)} y2={y(v)} className="stroke-gray-200 dark:stroke-gray-800" />
          <text x={L - 6} y={y(v) + 4} textAnchor="end" className="fill-gray-500 text-[10px] dark:fill-gray-400">{fmt(v)}</text>
        </g>
      ))}
      <text x={L} y={height - 6} className="fill-gray-500 text-[10px] dark:fill-gray-400">{formatWhen(pts[0].takenAt).split(',')[0]}</text>
      {pts.length > 1 && <text x={width - R} y={height - 6} textAnchor="end" className="fill-gray-500 text-[10px] dark:fill-gray-400">{formatWhen(pts[pts.length - 1].takenAt).split(',')[0]}</text>}
      <text x={width - R} y={T + 8} textAnchor="end" className="fill-gray-400 text-[10px]">{unit}</text>
      {pts.length > 1 && <polyline points={line(v1)} fill="none" strokeWidth={1.5} className="stroke-[rgb(var(--agent-health))]" />}
      {pts.length > 1 && kind === 'bp' && <polyline points={line(v2)} fill="none" strokeWidth={1.5} className="stroke-gray-500 dark:stroke-gray-400" />}
      {pts.map((p, i) => mark(x(p.takenAt), y(v1[i]), p.status, `a${p.id}`, 1))}
      {kind === 'bp' && pts.map((p, i) => v2[i] == null ? null : mark(x(p.takenAt), y(v2[i] as number), p.status, `b${p.id}`, 2))}
    </svg>
  )
}

/** The chart's key: what each shape means, and the band. */
export function ChartKey({ kind }: { kind: ReadingKind }) {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
      <li className="inline-flex items-center gap-1"><svg width="10" height="10" aria-hidden><circle cx="5" cy="5" r="3.5" className="fill-[rgb(var(--agent-health))]" /></svg>In range</li>
      <li className="inline-flex items-center gap-1"><svg width="10" height="10" aria-hidden><circle cx="5" cy="5" r="3.5" strokeWidth="2" className="fill-white stroke-[rgb(var(--agent-health))] dark:fill-gray-900" /></svg>Outside range</li>
      <li className="inline-flex items-center gap-1"><svg width="12" height="12" aria-hidden><path d="M6,0 L12,6 L6,12 L0,6 Z" className="fill-red-600" /></svg>Red flag</li>
      <li className="inline-flex items-center gap-1"><span className="inline-block h-2.5 w-4 rounded-sm bg-emerald-500/20" aria-hidden />Target</li>
      {kind === 'bp' && <li>Upper line systolic, lower line diastolic</li>}
    </ul>
  )
}
