import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, CalendarClock, FileWarning, PiggyBank, ShieldAlert, Sparkles, UserX } from 'lucide-react'
import type { ReactNode } from 'react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import type { CheckKey, FinProfileView, HealthView, QuickStartStep } from '@/types/finance'
import { ActionCard } from '../components/ActionCard'
import { AsOf, daysUntil, formatDay } from '../components/AsOf'
import { CHECKS, CheckTile } from '../components/CheckTile'
import { Money } from '../components/Money'
import { ScoreRing } from '../components/ScoreRing'
import { SkeletonRows } from '../components/SkeletonRows'
import { Sparkline } from '../components/Sparkline'
import { POLICY_KINDS } from '../labels'
import { errorText, useCashFlow, useHealth, useNetWorth, usePolicies, useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { QUICK_START_STEPS } from './QuickStart'

/** One sentence under the score: what to look at first. */
export function scoreSummary(h: HealthView): string {
  if (h.score == null) return 'Answer a Quick start question to get a score.'
  const red = h.checks.filter(c => c.status === 'red').length
  const unknown = h.checks.filter(c => c.status === 'unknown').length
  const parts: string[] = []
  if (red) parts.push(`${red === 1 ? 'One check is' : `${red} checks are`} red. Fix ${red === 1 ? 'it' : 'those'} first; goals come after.`)
  else if (h.checks.every(c => c.status === 'green' || c.status === 'unknown')) parts.push('Everything Kai can check is on track.')
  else parts.push('Close on a few checks; the actions below say what moves the score most.')
  if (unknown) parts.push(`${unknown === 1 ? 'One check has' : `${unknown} checks have`} not enough data and ${unknown === 1 ? 'is' : 'are'} left out of the score.`)
  return parts.join(' ')
}

/**
 * Money Health home (7.14): the score with its trend, six check tiles, net worth and surplus,
 * the top three actions, and what needs attention. Before Quick start it is the way in.
 */
export function Home({ profile, onOpen, onAnswer, onSample }: {
  profile: FinProfileView
  onOpen: (view: string) => void
  onAnswer: (step: QuickStartStep) => void
  onSample: () => void
}) {
  const sample = useSample()
  const fresh = !sample && profile.setupProgress.quickStart === 'not_started'
  if (fresh) return <FirstRun profile={profile} onOpen={onOpen} onSample={onSample} />
  return <MoneyHealth profile={profile} onOpen={onOpen} onAnswer={onAnswer} />
}

function FirstRun({ profile, onOpen, onSample }: { profile: FinProfileView; onOpen: (view: string) => void; onSample: () => void }) {
  const managed = profile.setupProgress.peopleManaged
  return (
    <div className="flex flex-col gap-4">
      <EmptyState icon={PiggyBank} title="No plan yet"
        description="Answer six short questions and Kai shows your Money Health score, net worth and a short list of what to do first. It takes about 10 minutes, and you can skip any question."
        action={{ label: 'Start Quick start', onClick: () => onOpen('quick-start') }} />
      <div className="flex flex-wrap justify-center gap-2 text-sm">
        <Button size="sm" variant="secondary" onClick={onSample}><Sparkles className="h-4 w-4" /> Look around with sample data</Button>
        <Button size="sm" variant="ghost" onClick={() => onOpen('people')}>People you manage{managed ? ` (${managed})` : ''}</Button>
        <Button size="sm" variant="ghost" onClick={() => onOpen('settings')}>Kai's defaults</Button>
      </div>
    </div>
  )
}

function MoneyHealth({ profile, onOpen, onAnswer }: { profile: FinProfileView; onOpen: (view: string) => void; onAnswer: (step: QuickStartStep) => void }) {
  const qc = useQueryClient()
  const sample = useSample()
  const health = useHealth()
  const run = useMutation({
    mutationFn: financeApi.runHealth,
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })

  if (health.isLoading) return <SkeletonRows rows={6} />
  if (health.error || !health.data) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 rounded-xl border border-red-200 p-6 text-center dark:border-red-900/50">
        <p className="text-sm text-red-700 dark:text-red-300">Could not work out your Money Health. Nothing you saved is lost.</p>
        <Button size="sm" variant="secondary" onClick={() => health.refetch()}>Try again</Button>
      </div>
    )
  }
  const h = health.data
  const inProgress = !sample && profile.setupProgress.quickStart === 'in_progress'

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Money Health</h2>
        <AsOf date={h.asOf} />
        <span className="flex-1" />
        {!sample && <Button size="sm" variant="secondary" loading={run.isPending} onClick={() => run.mutate()}>Run check</Button>}
      </div>
      {run.error && <p className="text-sm text-red-600">{errorText(run.error, 'Could not run the check.')}</p>}
      {inProgress && (
        <div className="flex flex-wrap items-center gap-2 rounded-xl bg-blue-50 px-3 py-2 text-sm text-blue-900 dark:bg-blue-900/30 dark:text-blue-100">
          <Sparkles className="h-4 w-4" aria-hidden /> Quick start isn't finished. The remaining questions fill in the grey checks.
          <button type="button" className="font-medium underline" onClick={() => onOpen('quick-start')}>Continue</button>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="flex min-w-0 flex-col gap-4 lg:col-span-2">
          <section className="flex flex-wrap items-center gap-4 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
            <ScoreRing score={h.score} />
            <div className="min-w-0 flex-1 basis-56">
              <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{h.score == null ? 'No score yet' : `Score ${h.score}`}</p>
              <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">{scoreSummary(h)}</p>
            </div>
            <Sparkline values={h.trend.map(t => t.score)}
              label={`Score over the last ${h.trend.length} checks: ${h.trend.map(t => Math.round(t.score)).join(', ')}`} />
          </section>

          <section aria-label="Checks" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {h.checks.map(c => <CheckTile key={c.key} check={c} onOpen={onOpen} onAnswer={sample ? undefined : onAnswer} />)}
          </section>

          <section className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Top three actions</h3>
              <button type="button" className="text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('actions')}>All actions</button>
            </div>
            {h.topActions.length
              ? h.topActions.map((a, i) => <ActionCard key={a.id} action={a} index={i + 1} />)
              : <p className="text-sm text-gray-500 dark:text-gray-400">Nothing to do right now.</p>}
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          <NetWorthCard onOpen={onOpen} />
          <SurplusCard surplus={h.surplus} onOpen={onOpen} />
          <NeedsAttention h={h} onOpen={onOpen} />
          {!sample && <SetupChecklist onAnswer={onAnswer} />}
        </div>
      </div>
    </div>
  )
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
      {children}
    </section>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-2 text-sm">
      <span className="text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}

function NetWorthCard({ onOpen }: { onOpen: (view: string) => void }) {
  const { data } = useNetWorth()
  if (!data) return <SkeletonRows rows={2} />
  const t = data.trend
  const change = t.length >= 2 ? t[t.length - 1].netWorth - t[t.length - 2].netWorth : null
  return (
    <Panel title="Net worth">
      <p className="text-2xl font-semibold text-gray-900 dark:text-gray-100"><Money amount={data.netWorth} short /></p>
      <Row label="What you own"><Money amount={data.assets} short /></Row>
      <Row label="What you owe"><Money amount={data.liabilities} short /></Row>
      {change != null && <Row label="Change since last month"><Money amount={change} short signed /></Row>}
      <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('net-worth')}>See assets</button>
    </Panel>
  )
}

function SurplusCard({ surplus, onOpen }: { surplus: number; onOpen: (view: string) => void }) {
  const { data } = useCashFlow()
  return (
    <Panel title="Left each month">
      <p className={`text-2xl font-semibold ${surplus < 0 ? 'text-red-600 dark:text-red-400' : 'text-gray-900 dark:text-gray-100'}`}><Money amount={surplus} /></p>
      {data && (
        <p className="text-sm text-gray-500 dark:text-gray-400">
          Take-home <Money amount={data.takeHome} /> minus needs, loans, other spending and SIPs.
        </p>
      )}
      <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('cash-flow')}>Cash flow</button>
    </Panel>
  )
}

/** Unscored flags, renewals in the next 30 days, and figures nobody has verified. */
function NeedsAttention({ h, onOpen }: { h: HealthView; onOpen: (view: string) => void }) {
  const { data: policies } = usePolicies()
  const renewals = (policies ?? []).filter(p => p.renewalDate && daysUntil(p.renewalDate) >= 0 && daysUntil(p.renewalDate) <= 30)
  const items: { icon: typeof Bell; text: string; view: string }[] = []
  if (h.flags.nomineeMissing) items.push({ icon: UserX, text: 'A nominee is missing on an account', view: 'net-worth' })
  if (h.flags.willMissing) items.push({ icon: FileWarning, text: 'No will recorded', view: 'protection' })
  for (const p of renewals) {
    items.push({ icon: CalendarClock, text: `${POLICY_KINDS[p.kind]} policy renews on ${formatDay(p.renewalDate)}`, view: 'protection' })
  }
  if (h.flags.needsVerification) items.push({ icon: ShieldAlert, text: 'Some tax or regulatory figures need verification', view: 'settings' })
  if (!items.length) return null
  return (
    <Panel title="Needs attention">
      <ul className="flex flex-col gap-1.5">
        {items.map(i => (
          <li key={i.text} className="flex items-start gap-2 text-sm">
            <i.icon className="mt-0.5 h-4 w-4 shrink-0 text-gray-500" aria-hidden />
            <button type="button" className="text-left text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen(i.view)}>{i.text}</button>
          </li>
        ))}
      </ul>
    </Panel>
  )
}

/** The checks each Quick start answer feeds. */
const STEP_UNLOCKS: Record<QuickStartStep, CheckKey[]> = {
  people: ['protection'],
  income: ['debt', 'savingsRate', 'spendingControl'],
  spending: ['emergencyFund', 'savingsRate', 'spendingControl'],
  loans: ['debt'],
  insurance: ['protection'],
  savings: ['emergencyFund'],
  goals: ['goalFunding'],
  risk: [],
}

/** What is still missing and which checks each answer unlocks. */
function SetupChecklist({ onAnswer }: { onAnswer: (step: QuickStartStep) => void }) {
  const { data } = useQuery({ queryKey: financeKeys.quickStart, queryFn: financeApi.quickStart })
  const skipped = data?.progress.skipped ?? []
  if (!skipped.length) return null
  return (
    <Panel title="Still to answer">
      <ul className="flex flex-col gap-2">
        {skipped.map(step => {
          const unlocks = STEP_UNLOCKS[step].map(k => CHECKS[k].title)
          const meta = QUICK_START_STEPS.find(s => s.id === step)!
          return (
            <li key={step} className="text-sm">
              <button type="button" className="text-left font-medium text-brand-600 hover:underline dark:text-brand-300" onClick={() => onAnswer(step)}>{meta.title}</button>
              {unlocks.length > 0 && <p className="text-xs text-gray-500 dark:text-gray-400">Unlocks {unlocks.join(' and ')}</p>}
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}
