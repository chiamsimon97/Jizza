import type { CheckKey, HealthCheck, QuickStartStep } from '@/types/finance'
import { StatusBadge } from './StatusBadge'

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

const EDGE = { green: 'border-l-green-500', amber: 'border-l-amber-500', red: 'border-l-red-500', unknown: 'border-l-gray-300 dark:border-l-gray-700' }

/**
 * One Money Health check (7.14): its number, target and points, and a way to fix it. A check with
 * no data is grey, "Not enough data", and asks its question; it never counts as red.
 */
export function CheckTile({ check, onOpen, onAnswer }: {
  check: HealthCheck
  onOpen?: (view: string) => void
  onAnswer?: (step: QuickStartStep) => void
}) {
  const meta = CHECKS[check.key]
  const unknown = check.status === 'unknown'
  return (
    <div className={`flex flex-col gap-1.5 rounded-xl border border-l-4 border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-gray-900 ${EDGE[check.status]}`}
      data-testid={`check-${check.key}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="text-sm font-medium text-gray-900 dark:text-gray-100">{meta.title}</h3>
        <StatusBadge status={check.status} />
      </div>
      {unknown ? (
        <>
          <p className="text-xs text-gray-500 dark:text-gray-400">{meta.question}</p>
          {meta.step && onAnswer && (
            <button type="button" onClick={() => onAnswer(meta.step!)} className="self-start text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
              Answer this
            </button>
          )}
        </>
      ) : (
        <>
          <p className="text-base font-semibold tabular-nums text-gray-900 dark:text-gray-100">{meta.value(check)}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {meta.target(check)} · {fmtPoints(check.points)} of {fmtPoints(check.weight)} points
          </p>
          {check.status !== 'green' && onOpen && (
            <button type="button" onClick={() => onOpen(meta.view)} className="self-start text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
              Fix this
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
