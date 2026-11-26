import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, CircleHelp, RefreshCw, XCircle, type LucideIcon } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { GoalCheck, GoalVerdict } from '@/types/finance'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'
import { Money } from './Money'
import { Pill, Row } from './Panel'

export const VERDICTS: Record<GoalVerdict, { label: string; tone: 'ok' | 'warn' | 'bad' | 'neutral'; icon: LucideIcon; bar: string }> = {
  on_track: { label: 'On track', tone: 'ok', icon: CheckCircle2, bar: 'bg-green-600' },
  at_risk: { label: 'At risk', tone: 'warn', icon: AlertTriangle, bar: 'bg-amber-500' },
  off_track: { label: 'Off track', tone: 'bad', icon: XCircle, bar: 'bg-red-500' },
  not_enough_data: { label: 'Not enough data', tone: 'neutral', icon: CircleHelp, bar: 'bg-gray-400' },
}

/** The verdict as a small pill, with the chance in its tooltip. */
export function VerdictPill({ check }: { check: GoalCheck }) {
  const v = VERDICTS[check.verdict]
  const Icon = v.icon
  const title = check.successPct == null ? check.fix
    : `${pct(check.successPct)} chance of reaching it; ${pct(check.requiredPct ?? 0)} is the bar for this priority.`
  return <Pill tone={v.tone} title={title}><Icon className="h-3 w-3" aria-hidden />{v.label}</Pill>
}

/**
 * The chance against the bar: a track with the bar marked and the chance filled. The chance is
 * the share of simulated paths, with returns that go up and down, that reach the target.
 */
export function ChanceMeter({ check }: { check: GoalCheck }) {
  if (check.successPct == null) return null
  const bar = check.requiredPct ?? 75
  return (
    <div className="relative h-2.5 w-full rounded-full bg-gray-100 dark:bg-gray-800" role="img"
      aria-label={`${pct(check.successPct)} chance; the bar is ${pct(bar)}`}>
      <div className={`h-full rounded-full ${VERDICTS[check.verdict].bar} transition-[width] duration-700`} style={{ width: `${Math.min(100, check.successPct)}%` }} />
      <div className="absolute -top-[3px] h-4 w-0.5 rounded bg-gray-700 dark:bg-gray-200" style={{ left: `calc(${Math.min(100, bar)}% - 1px)` }} aria-hidden />
    </div>
  )
}

/** The goal check on a goal's own page: the verdict, the chance, the range and the fix. */
export function GoalCheckPanel({ check }: { check: GoalCheck }) {
  const v = VERDICTS[check.verdict]
  const Icon = v.icon
  return (
    <div className="flex flex-col gap-2.5" data-verdict={check.verdict}>
      <div className="flex items-center gap-2">
        <Icon className={`h-6 w-6 ${TEXT[v.tone]}`} aria-hidden />
        <span className="text-lg font-semibold text-gray-900 dark:text-gray-100">{v.label}</span>
        {check.successPct != null && <span className="ml-auto text-2xl font-semibold tabular-nums text-gray-900 dark:text-gray-100">{pct(check.successPct)}</span>}
      </div>
      <ChanceMeter check={check} />
      {check.successPct != null && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          Chance of reaching the target, against a bar of {pct(check.requiredPct ?? 0)} for a goal of this priority.
        </p>
      )}
      {check.p10 != null && <Row label="In a poor market (1 in 10)"><Money amount={check.p10} /></Row>}
      {check.p50 != null && <Row label="In a typical market"><Money amount={check.p50} /></Row>}
      {check.futureTarget != null && <Row label="Target then" strong><Money amount={check.futureTarget} /></Row>}
      {check.extraMonthly != null && check.extraMonthly > 0 && <Row label="More a month to close the gap"><Money amount={check.extraMonthly} /></Row>}
      <p className="rounded-lg bg-gray-50 px-3 py-2 text-sm text-gray-700 dark:bg-gray-800/60 dark:text-gray-200">{check.fix}</p>
      {check.previousVerdict && check.previousVerdict !== check.verdict && (
        <p className="text-xs text-gray-500 dark:text-gray-400">Was {VERDICTS[check.previousVerdict].label.toLowerCase()} at the last nightly check.</p>
      )}
    </div>
  )
}

/** Runs the checks now and keeps them, so the next change is measured from here. */
export function RunChecksButton() {
  const qc = useQueryClient()
  const run = useMutation({
    mutationFn: financeApi.runGoalChecks,
    onSuccess: data => qc.setQueryData(financeKeys.goalChecks(false), data),
  })
  return (
    <span className="inline-flex flex-col items-start gap-1">
      <Button size="sm" variant="ghost" loading={run.isPending} onClick={() => run.mutate()}>
        <RefreshCw className="h-3.5 w-3.5" /> Check again
      </Button>
      {run.error && <span role="alert" className="text-xs text-red-600">{errorText(run.error, 'Could not check the goals.')}</span>}
    </span>
  )
}

const TEXT = {
  ok: 'text-green-600 dark:text-green-400',
  warn: 'text-amber-500',
  bad: 'text-red-500',
  neutral: 'text-gray-400',
}

export function pct(v: number) {
  return `${Math.round(v)}%`
}
