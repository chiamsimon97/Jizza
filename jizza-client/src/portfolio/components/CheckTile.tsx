import type { CSSProperties } from 'react'
import { ArrowRight, Landmark, LifeBuoy, ShieldCheck, ShoppingBag, Target, TrendingUp, type LucideIcon } from 'lucide-react'
import type { CheckKey, CheckStatus, HealthCheck, QuickStartStep } from '@/types/finance'
import { useMounted } from './motion'
import { StatusBadge } from './StatusBadge'

/** An icon for each check, so the six tiles can be told apart at a glance. */
export const CHECK_ICONS: Record<CheckKey, LucideIcon> = {
  emergencyFund: LifeBuoy, debt: Landmark, savingsRate: TrendingUp, goalFunding: Target, protection: ShieldCheck, spendingControl: ShoppingBag,
}

/** Colours per status: the tile's tint, its icon chip and its points bar. */
export const TONES: Record<CheckStatus, { tint: string; chip: string; bar: string; ring: string }> = {
  green: {
    tint: 'from-emerald-50/80 dark:from-emerald-500/10', chip: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300',
    bar: 'bg-gradient-to-r from-emerald-400 to-emerald-600', ring: 'ring-emerald-400/60',
  },
  amber: {
    tint: 'from-amber-50/80 dark:from-amber-500/10', chip: 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300',
    bar: 'bg-gradient-to-r from-amber-300 to-orange-500', ring: 'ring-amber-400/60',
  },
  red: {
    tint: 'from-rose-50/90 dark:from-rose-500/10', chip: 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300',
    bar: 'bg-gradient-to-r from-rose-400 to-rose-600', ring: 'ring-rose-400/60',
  },
  unknown: {
    tint: 'from-gray-50 dark:from-gray-800/40', chip: 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400',
    bar: 'bg-gray-300 dark:bg-gray-700', ring: 'ring-gray-300',
  },
}

/** What each check is called, how its number reads, and what to answer when it has no data. */
export const CHECKS: Record<CheckKey, {
  title: string
  value: (c: HealthCheck) => string
  target: (c: HealthCheck) => string
  question: string
  /** The Quick start step that fills it in. */
  step?: QuickStartStep
  /** The Portfolio view that shows the detail. */
  view: string
}> = {
  emergencyFund: {
    title: 'Emergency fund',
    value: c => `${c.shown} months`,
    target: c => `Target ${c.target} months`,
    question: 'How much do you keep aside for emergencies?',
    step: 'savings', view: 'net-worth',
  },
  debt: {
    title: 'Debt',
    value: c => `${c.shown}% of income on loans`,
    target: c => {
      const high = Array.isArray(c.facts.highInterestLoanIds) ? c.facts.highInterestLoanIds.length : 0
      return high ? `${high === 1 ? 'A loan costs' : `${high} loans cost`} more than the high-interest line` : `Target ${c.target}% or less`
    },
    question: 'What loans do you have, and what do you earn?',
    step: 'loans', view: 'loans',
  },
  savingsRate: {
    title: 'Savings rate',
    value: c => `${c.shown}% of take-home`,
    target: c => `Target ${c.target}%`,
    question: 'How much goes into savings or SIPs each month?',
    step: 'spending', view: 'cash-flow',
  },
  goalFunding: {
    title: 'Goal funding',
    value: c => `${c.shown}% funded`,
    target: c => `Target ${c.target}%`,
    question: 'What are you saving for, and by when?',
    step: 'goals', view: 'goals',
  },
  protection: {
    title: 'Protection',
    value: c => (c.shown === 0 ? 'No gaps' : `${c.shown} ${c.shown === 1 ? 'gap' : 'gaps'}`),
    target: () => 'Term and health cover against need',
    question: 'What term and health cover do you have?',
    step: 'insurance', view: 'protection',
  },
  spendingControl: {
    title: 'Spending',
    value: c => `${c.shown}% on needs`,
    target: c => `Target under ${c.target}%`,
    question: 'What do you spend each month on needs and on the rest?',
    step: 'spending', view: 'cash-flow',
  },
}

/**
 * One Money Health check (7.14): its number, target and points, and a way to fix it. A check with
 * no data is grey, "Not enough data", and asks its question; it never counts as red.
 */
export function CheckTile({ check, onOpen, onAnswer, highlight = false, onHover, style }: {
  check: HealthCheck
  onOpen?: (view: string) => void
  onAnswer?: (step: QuickStartStep) => void
  /** Ringed while its part of the score bar is pointed at. */
  highlight?: boolean
  onHover?: (key: CheckKey | null) => void
  style?: CSSProperties
}) {
  const meta = CHECKS[check.key]
  const unknown = check.status === 'unknown'
  const tone = TONES[check.status]
  const Icon = CHECK_ICONS[check.key]
  const mounted = useMounted()
  const pct = check.weight ? (check.points / check.weight) * 100 : 0
  return (
    <div className={`group relative flex flex-col gap-1.5 overflow-hidden rounded-2xl border border-gray-200 bg-gradient-to-br to-white to-60% p-3.5 shadow-sm transition-all duration-200 motion-safe:hover:-translate-y-0.5 hover:shadow-md dark:border-gray-800 dark:to-gray-900 ${tone.tint} ${highlight ? `ring-2 ${tone.ring}` : ''}`}
      style={style} data-testid={`check-${check.key}`} data-status={check.status}
      onMouseEnter={() => onHover?.(check.key)} onMouseLeave={() => onHover?.(null)}>
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-xl transition-transform duration-200 motion-safe:group-hover:scale-110 ${tone.chip}`} aria-hidden>
            <Icon className="h-4 w-4" />
          </span>
          <h3 className="text-sm font-medium text-gray-900 dark:text-gray-100">{meta.title}</h3>
        </div>
        <StatusBadge status={check.status} />
      </div>
      {unknown ? (
        <>
          <p className="text-xs text-gray-500 dark:text-gray-400">{meta.question}</p>
          {meta.step && onAnswer && (
            <button type="button" onClick={() => onAnswer(meta.step!)}
              className="mt-auto inline-flex items-center gap-1 self-start rounded-md text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
              Answer this
              <ArrowRight className="h-3 w-3 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5" aria-hidden />
            </button>
          )}
        </>
      ) : (
        <>
          <p className="text-lg font-semibold tabular-nums tracking-tight text-gray-900 dark:text-gray-100">{meta.value(check)}</p>
          <div className="h-1.5 overflow-hidden rounded-full bg-gray-200/70 dark:bg-gray-800" aria-hidden>
            <div className={`h-full rounded-full ${tone.bar}`}
              style={{ width: `${mounted ? Math.max(pct, check.points > 0 ? 4 : 0) : 0}%`, transition: 'width 0.9s cubic-bezier(0.16, 1, 0.3, 1)' }} />
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {meta.target(check)} · {fmtPoints(check.points)} of {fmtPoints(check.weight)} points
          </p>
          {check.status !== 'green' && onOpen && (
            <button type="button" onClick={() => onOpen(meta.view)}
              className="mt-auto inline-flex items-center gap-1 self-start rounded-md text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
              Fix this
              <ArrowRight className="h-3 w-3 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5" aria-hidden />
            </button>
          )}
        </>
      )}
    </div>
  )
}

function fmtPoints(n: number) {
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}
