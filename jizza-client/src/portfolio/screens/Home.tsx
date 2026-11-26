import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowDownRight, ArrowRight, ArrowUpRight, Bell, CalendarClock, ChevronRight, Coins, Compass, FileWarning, Gauge, ListChecks, PartyPopper, PiggyBank,
  RefreshCw, ShieldAlert, Sparkles, Target, UserX, Wallet, Zap, type LucideIcon,
} from 'lucide-react'
import { type ReactNode, useState } from 'react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { CheckKey, CheckStatus, FinProfileView, HealthCheck, HealthView, QuickStartStep } from '@/types/finance'
import { formatINRShort } from '@/lib/money'
import { ActionCard } from '../components/ActionCard'
import { AsOf, daysUntil, formatDay } from '../components/AsOf'
import { CHECKS, CheckTile } from '../components/CheckTile'
import { Money } from '../components/Money'
import { ScoreRing, scoreBand } from '../components/ScoreRing'
import { stagger, useMounted } from '../components/motion'
import { SkeletonRows } from '../components/SkeletonRows'
import { Sparkline } from '../components/Sparkline'
import { POLICY_KINDS } from '../labels'
import { errorText, useCashFlow, useGoalChecks, useHealth, useNetWorth, usePolicies, useSample } from '../data'
import { VERDICTS, VerdictPill } from '../components/GoalVerdict'
import { financeKeys } from '../queryKeys'
import { QUICK_START_STEPS } from './QuickStart'
import { SetupCard, type SetupStep } from './SetupGuide'

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
export function Home({ profile, onOpen, onAnswer, onSample, onSetupStep = () => onOpen('setup'), onOpenGoal = () => onOpen('goals') }: {
  profile: FinProfileView
  onOpen: (view: string) => void
  onAnswer: (step: QuickStartStep) => void
  onSample: () => void
  /** Opens a setup guide step with the guide bar. */
  onSetupStep?: (step: SetupStep) => void
  /** Opens one goal's page. */
  onOpenGoal?: (id: string) => void
}) {
  const sample = useSample()
  const fresh = !sample && profile.setupProgress.quickStart === 'not_started'
  if (fresh) return <FirstRun profile={profile} onOpen={onOpen} onSample={onSample} />
  return <MoneyHealth profile={profile} onOpen={onOpen} onAnswer={onAnswer} onSetupStep={onSetupStep} onOpenGoal={onOpenGoal} />
}

const FIRST_RUN_POINTS: { icon: LucideIcon; title: string; text: string; tone: string }[] = [
  { icon: Gauge, title: 'A Money Health score', text: 'Six checks out of 100: emergency fund, debt, savings, goals, cover and spending.', tone: 'bg-rose-100 text-rose-600 dark:bg-rose-500/15 dark:text-rose-300' },
  { icon: Wallet, title: 'Your net worth', text: 'What you own and owe, by person and by kind, month by month.', tone: 'bg-brand-100 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300' },
  { icon: ListChecks, title: 'What to do first', text: 'A short list in order, each with the points it adds. You decide; Kai never acts.', tone: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-500/15 dark:text-emerald-300' },
]

function FirstRun({ profile, onOpen, onSample }: { profile: FinProfileView; onOpen: (view: string) => void; onSample: () => void }) {
  const managed = profile.setupProgress.peopleManaged
  return (
    <div className="flex flex-col gap-5">
      <section className="relative overflow-hidden rounded-3xl border border-gray-200 bg-gradient-to-br from-brand-50 via-white to-emerald-50/70 p-6 shadow-sm motion-safe:animate-fade-in-up dark:border-gray-800 dark:from-brand-500/10 dark:via-gray-900 dark:to-emerald-500/5 sm:p-8">
        <div aria-hidden className="pointer-events-none absolute -right-16 -top-16 h-56 w-56 rounded-full bg-brand-300/30 blur-3xl motion-safe:animate-orb-pulse dark:bg-brand-500/20" />
        <div className="relative flex flex-col items-start gap-3">
          <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-400 to-brand-600 text-white shadow-glow" aria-hidden>
            <PiggyBank className="h-6 w-6" />
          </span>
          <h2 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">No plan yet</h2>
          <p className="max-w-xl text-sm leading-relaxed text-gray-600 dark:text-gray-300">
            Answer six short questions and Kai shows your Money Health score, net worth and a short list of what to do first. It takes about 10 minutes, and you can skip any question.
          </p>
          <div className="mt-1 flex flex-wrap gap-2">
            <Button onClick={() => onOpen('quick-start')}>Start Quick start <ArrowRight className="h-4 w-4" aria-hidden /></Button>
            <Button variant="secondary" onClick={() => onOpen('setup')}><Compass className="h-4 w-4" aria-hidden /> See every setup step</Button>
            <Button variant="ghost" onClick={onSample}><Sparkles className="h-4 w-4" /> Look around with sample data</Button>
          </div>
        </div>
      </section>
      <div className="grid gap-3 sm:grid-cols-3">
        {FIRST_RUN_POINTS.map((p, i) => (
          <div key={p.title} className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-all motion-safe:animate-fade-in-up motion-safe:hover:-translate-y-0.5 hover:shadow-md dark:border-gray-800 dark:bg-gray-900"
            style={stagger(i + 1, 90)}>
            <span className={`flex h-9 w-9 items-center justify-center rounded-xl ${p.tone}`} aria-hidden><p.icon className="h-5 w-5" /></span>
            <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{p.title}</h3>
            <p className="text-xs leading-relaxed text-gray-600 dark:text-gray-400">{p.text}</p>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap justify-center gap-2 text-sm">
        <Button size="sm" variant="ghost" onClick={() => onOpen('people')}>People you manage{managed ? ` (${managed})` : ''}</Button>
        <Button size="sm" variant="ghost" onClick={() => onOpen('settings')}>Kai's defaults</Button>
      </div>
    </div>
  )
}

type CheckFilter = 'all' | 'fix' | 'ok' | 'unknown'

const FILTERS: { id: CheckFilter; label: string; match: (s: CheckStatus) => boolean }[] = [
  { id: 'all', label: 'All', match: () => true },
  { id: 'fix', label: 'Needs work', match: s => s === 'red' || s === 'amber' },
  { id: 'ok', label: 'On track', match: s => s === 'green' },
  { id: 'unknown', label: 'Not enough data', match: s => s === 'unknown' },
]

/** The band tint behind the score card. */
const HERO_TINT = {
  red: 'from-rose-50 via-white to-orange-50/60 dark:from-rose-500/10 dark:via-gray-900 dark:to-orange-500/5',
  amber: 'from-amber-50 via-white to-orange-50/60 dark:from-amber-500/10 dark:via-gray-900 dark:to-orange-500/5',
  green: 'from-emerald-50 via-white to-cyan-50/60 dark:from-emerald-500/10 dark:via-gray-900 dark:to-cyan-500/5',
  none: 'from-gray-50 via-white to-gray-50 dark:from-gray-800/40 dark:via-gray-900 dark:to-gray-900',
}

function MoneyHealth({ profile, onOpen, onAnswer, onSetupStep, onOpenGoal }: {
  profile: FinProfileView
  onOpen: (view: string) => void
  onAnswer: (step: QuickStartStep) => void
  onSetupStep: (step: SetupStep) => void
  onOpenGoal: (id: string) => void
}) {
  const qc = useQueryClient()
  const sample = useSample()
  const health = useHealth()
  const [filter, setFilter] = useState<CheckFilter>('all')
  const [hovered, setHovered] = useState<CheckKey | null>(null)
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
  const active = FILTERS.find(f => f.id === filter)!
  const checks = h.checks.filter(c => active.match(c.status))
  const counts = Object.fromEntries(FILTERS.map(f => [f.id, h.checks.filter(c => f.match(c.status)).length])) as Record<CheckFilter, number>
  const gain = h.topActions.reduce((sum, a) => sum + (a.impactPoints ?? 0), 0)
  const last = h.trend.length >= 2 ? h.trend[h.trend.length - 1].score - h.trend[h.trend.length - 2].score : null
  const band = h.score == null ? 'none' : scoreBand(h.score)

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold tracking-tight text-gray-900 dark:text-gray-100">Money Health</h2>
        <AsOf date={h.asOf} />
        <span className="flex-1" />
        {!sample && (
          <Button size="sm" variant="secondary" disabled={run.isPending} onClick={() => run.mutate()}>
            <RefreshCw className={`h-4 w-4 ${run.isPending ? 'motion-safe:animate-spin' : ''}`} aria-hidden />
            {run.isPending ? 'Checking…' : 'Run check'}
          </Button>
        )}
      </div>
      {run.error && <p className="text-sm text-red-600">{errorText(run.error, 'Could not run the check.')}</p>}
      {inProgress && (
        <div className="flex flex-wrap items-center gap-2 rounded-xl bg-blue-50 px-3 py-2 text-sm text-blue-900 dark:bg-blue-900/30 dark:text-blue-100">
          <Sparkles className="h-4 w-4" aria-hidden /> Quick start isn't finished. The remaining questions fill in the grey checks.
          <button type="button" className="font-medium underline" onClick={() => onOpen('quick-start')}>Continue</button>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="flex min-w-0 flex-col gap-5 lg:col-span-2">
          <section className={`relative overflow-hidden rounded-2xl border border-gray-200 bg-gradient-to-br p-5 shadow-sm motion-safe:animate-fade-in-up dark:border-gray-800 ${HERO_TINT[band]}`}>
            <div className="flex flex-wrap items-center gap-5">
              <ScoreRing score={h.score} size={128} />
              <div className="flex min-w-0 flex-1 basis-56 flex-col gap-1.5">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="text-base font-semibold text-gray-900 dark:text-gray-100">{h.score == null ? 'No score yet' : `Score ${h.score}`}</p>
                  {last != null && Math.round(last) !== 0 && (
                    <span className={`inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-semibold ${last > 0
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                      : 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'}`}>
                      {last > 0 ? <ArrowUpRight className="h-3 w-3" aria-hidden /> : <ArrowDownRight className="h-3 w-3" aria-hidden />}
                      {last > 0 ? '+' : '−'}{Math.abs(Math.round(last))} since the last check
                    </span>
                  )}
                </div>
                <p className="text-sm leading-relaxed text-gray-600 dark:text-gray-300">{scoreSummary(h)}</p>
                {gain > 0 && (
                  <button type="button" onClick={() => document.getElementById('kai-top-actions')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
                    className="group mt-1 inline-flex items-center gap-1.5 self-start rounded-full bg-white/80 px-3 py-1 text-xs font-medium text-gray-800 shadow-sm ring-1 ring-gray-200 transition hover:ring-brand-300 dark:bg-gray-800/80 dark:text-gray-100 dark:ring-gray-700">
                    <Zap className="h-3.5 w-3.5 text-amber-500" aria-hidden />
                    The top three actions add up to +{gain} points
                    <ArrowRight className="h-3 w-3 transition-transform motion-safe:group-hover:translate-x-0.5" aria-hidden />
                  </button>
                )}
              </div>
              {h.trend.length >= 2 && (
                <div className="flex flex-col items-end gap-1">
                  <span className="text-[11px] font-medium uppercase tracking-wide text-gray-400">Trend</span>
                  <Sparkline values={h.trend.map(t => t.score)} width={160} height={48} dates={h.trend.map(t => formatDay(t.at))}
                    label={`Score over the last ${h.trend.length} checks: ${h.trend.map(t => Math.round(t.score)).join(', ')}`} />
                </div>
              )}
            </div>
            {h.score != null && <ScoreBar checks={h.checks} hovered={hovered} onHover={setHovered} />}
          </section>

          <section aria-label="Checks" className="flex flex-col gap-3">
            <div role="group" aria-label="Show checks" className="flex flex-wrap gap-1.5">
              {FILTERS.filter(f => f.id === 'all' || counts[f.id] > 0).map(f => (
                <button key={f.id} type="button" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}
                  className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors ${filter === f.id
                    ? 'bg-gray-900 text-white shadow-sm dark:bg-gray-100 dark:text-gray-900'
                    : 'bg-white text-gray-600 ring-1 ring-gray-200 hover:bg-gray-50 dark:bg-gray-900 dark:text-gray-300 dark:ring-gray-800 dark:hover:bg-gray-800'}`}>
                  {f.label}
                  <span className={`rounded-full px-1.5 tabular-nums ${filter === f.id ? 'bg-white/20' : 'bg-gray-100 dark:bg-gray-800'}`}>{counts[f.id]}</span>
                </button>
              ))}
            </div>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {checks.map((c, i) => (
                <div key={c.key} className="flex motion-safe:animate-fade-in-up [&>*]:flex-1" style={stagger(i)}>
                  <CheckTile check={c} onOpen={onOpen} onAnswer={sample ? undefined : onAnswer}
                    highlight={hovered === c.key} onHover={setHovered} />
                </div>
              ))}
            </div>
          </section>

          <section id="kai-top-actions" className="flex scroll-mt-40 flex-col gap-2.5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Top three actions</h3>
              <button type="button" className="group inline-flex items-center gap-1 text-sm font-medium text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('actions')}>
                All actions <ArrowRight className="h-3.5 w-3.5 transition-transform motion-safe:group-hover:translate-x-0.5" aria-hidden />
              </button>
            </div>
            {h.topActions.length
              ? h.topActions.map((a, i) => (
                <div key={a.id} className="motion-safe:animate-fade-in-up" style={stagger(i + 2, 80)}>
                  <ActionCard action={a} index={i + 1} />
                </div>
              ))
              : (
                <div className="flex items-center gap-2 rounded-2xl border border-dashed border-gray-300 p-4 text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
                  <PartyPopper className="h-4 w-4 text-emerald-500" aria-hidden /> Nothing to do right now.
                </div>
              )}
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          {!sample && <div className="motion-safe:animate-fade-in-up"><SetupCard profile={profile} onGuide={() => onOpen('setup')} onGo={onSetupStep} /></div>}
          {!sample && <div className="motion-safe:animate-fade-in-up" style={stagger(1, 90)}><GoalChecksCard onOpen={onOpen} onOpenGoal={onOpenGoal} /></div>}
          <div className="motion-safe:animate-fade-in-up" style={stagger(1, 90)}><NetWorthCard onOpen={onOpen} /></div>
          <div className="motion-safe:animate-fade-in-up" style={stagger(2, 90)}><SurplusCard surplus={h.surplus} onOpen={onOpen} /></div>
          <div className="motion-safe:animate-fade-in-up" style={stagger(3, 90)}><NeedsAttention h={h} onOpen={onOpen} /></div>
          {!sample && <SetupChecklist onAnswer={onAnswer} />}
        </div>
      </div>
    </div>
  )
}

const SEGMENT = { green: 'bg-emerald-500', amber: 'bg-amber-400', red: 'bg-rose-500', unknown: 'bg-gray-300 dark:bg-gray-600' }

/**
 * Where the score comes from: one segment per check, as wide as its weight and filled by the
 * points it earns. Pointing at a segment rings its tile, and the other way round.
 */
function ScoreBar({ checks, hovered, onHover }: { checks: HealthCheck[]; hovered: CheckKey | null; onHover: (k: CheckKey | null) => void }) {
  const mounted = useMounted()
  const total = checks.reduce((s, c) => s + c.weight, 0) || 1
  const focus = checks.find(c => c.key === hovered)
  return (
    <div className="mt-4 flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[11px] text-gray-500 dark:text-gray-400">
        <span className="font-medium uppercase tracking-wide">Where the points come from</span>
        <span aria-live="polite" className="tabular-nums">
          {focus ? `${CHECKS[focus.key].title}: ${fmtPts(focus.points)} of ${fmtPts(focus.weight)} points` : 'Point at a part to see it'}
        </span>
      </div>
      <div className="flex h-3 gap-1" role="list" aria-label="Points by check">
        {checks.map(c => {
          const fill = c.weight ? (c.points / c.weight) * 100 : 0
          return (
            <div key={c.key} role="listitem" tabIndex={0}
              aria-label={`${CHECKS[c.key].title}: ${fmtPts(c.points)} of ${fmtPts(c.weight)} points`}
              onMouseEnter={() => onHover(c.key)} onMouseLeave={() => onHover(null)} onFocus={() => onHover(c.key)} onBlur={() => onHover(null)}
              className={`relative cursor-default overflow-hidden rounded-full bg-gray-200/80 outline-none transition-transform duration-150 focus-visible:ring-2 focus-visible:ring-brand-500 dark:bg-gray-800 ${hovered === c.key ? 'scale-y-150' : ''}`}
              style={{ flexGrow: c.weight / total, flexBasis: 0 }}>
              <div className={`h-full rounded-full ${SEGMENT[c.status]}`}
                style={{ width: `${mounted ? fill : 0}%`, transition: 'width 1s cubic-bezier(0.16, 1, 0.3, 1)' }} />
            </div>
          )
        })}
      </div>
    </div>
  )
}

const fmtPts = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(1))

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-2 text-sm">
      <span className="text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}

function Card({ title, icon: Icon, iconTone, children, badge }: { title: string; icon: LucideIcon; iconTone: string; children: ReactNode; badge?: ReactNode }) {
  return (
    <section className="group flex flex-col gap-2.5 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center gap-2">
        <span className={`flex h-7 w-7 items-center justify-center rounded-lg ${iconTone}`} aria-hidden><Icon className="h-4 w-4" /></span>
        <h3 className="flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
        {badge}
      </div>
      {children}
    </section>
  )
}

function LinkOut({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick}
      className="group/link mt-0.5 inline-flex items-center gap-1 self-start text-sm font-medium text-brand-600 hover:underline dark:text-brand-300">
      {children}<ArrowRight className="h-3.5 w-3.5 transition-transform motion-safe:group-hover/link:translate-x-0.5" aria-hidden />
    </button>
  )
}

function NetWorthCard({ onOpen }: { onOpen: (view: string) => void }) {
  const { data } = useNetWorth()
  const mounted = useMounted()
  if (!data) return <SkeletonRows rows={2} />
  const t = data.trend
  const change = t.length >= 2 ? t[t.length - 1].netWorth - t[t.length - 2].netWorth : null
  const gross = data.assets + data.liabilities
  const ownPct = gross > 0 ? (data.assets / gross) * 100 : 100
  return (
    <Card title="Net worth" icon={Wallet} iconTone="bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200"
      badge={change != null && change !== 0 ? (
        <span className={`inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-semibold ${change > 0
          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
          : 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'}`}>
          {change > 0 ? <ArrowUpRight className="h-3 w-3" aria-hidden /> : <ArrowDownRight className="h-3 w-3" aria-hidden />}
          <Money amount={change} short signed />
        </span>
      ) : undefined}>
      <div className="flex items-end justify-between gap-2">
        <p className="text-3xl font-bold tracking-tight text-gray-900 dark:text-gray-100"><Money amount={data.netWorth} short animate /></p>
        {t.length >= 2 && (
          <Sparkline values={t.map(p => p.netWorth)} width={96} height={32} dates={t.map(p => formatDay(p.asOf))}
            format={v => formatINRShort(v)} label={`Net worth over ${t.length} months`} />
        )}
      </div>
      <div className="flex h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" aria-hidden>
        <div className="h-full bg-gradient-to-r from-emerald-400 to-emerald-500" style={{ width: `${mounted ? ownPct : 0}%`, transition: 'width 1s cubic-bezier(0.16, 1, 0.3, 1)' }} />
        <div className="h-full flex-1 bg-gradient-to-r from-rose-400 to-rose-500" />
      </div>
      <Row label="What you own"><span className="inline-flex items-center gap-1.5"><Dot className="bg-emerald-500" /><Money amount={data.assets} short /></span></Row>
      <Row label="What you owe"><span className="inline-flex items-center gap-1.5"><Dot className="bg-rose-500" /><Money amount={data.liabilities} short /></span></Row>
      {change != null && <Row label="Change since last month"><Money amount={change} short signed /></Row>}
      <LinkOut onClick={() => onOpen('net-worth')}>See assets</LinkOut>
    </Card>
  )
}

const Dot = ({ className }: { className: string }) => <span className={`h-2 w-2 rounded-full ${className}`} aria-hidden />

/** Where take-home goes each month, as one bar: needs, loans, cover, the rest, and what is left. */
function SurplusCard({ surplus, onOpen }: { surplus: number; onOpen: (view: string) => void }) {
  const { data } = useCashFlow()
  const mounted = useMounted()
  const [hover, setHover] = useState<string | null>(null)
  const parts = data && data.takeHome > 0 ? [
    { key: 'needs', label: 'Needs', amount: data.essential, className: 'bg-brand-500' },
    { key: 'loans', label: 'Loan payments', amount: data.loanPayments, className: 'bg-rose-400' },
    { key: 'cover', label: 'Insurance', amount: data.premiumsMonthly, className: 'bg-cyan-500' },
    { key: 'other', label: 'Other spending and SIPs', amount: Math.max(0, data.takeHome - data.essential - data.loanPayments - data.premiumsMonthly - Math.max(0, surplus)), className: 'bg-amber-400' },
    { key: 'left', label: 'Left', amount: Math.max(0, surplus), className: 'bg-emerald-500' },
  ].filter(p => p.amount > 0) : []
  const focus = parts.find(p => p.key === hover)
  return (
    <Card title="Left each month" icon={Coins} iconTone="bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">
      <p className={`text-3xl font-bold tracking-tight ${surplus < 0 ? 'text-red-600 dark:text-red-400' : 'text-gray-900 dark:text-gray-100'}`}><Money amount={surplus} animate /></p>
      {parts.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <div className="flex h-2.5 gap-0.5 overflow-hidden rounded-full" role="list" aria-label="Where take-home goes">
            {parts.map(p => (
              <div key={p.key} role="listitem" aria-label={p.label} onMouseEnter={() => setHover(p.key)} onMouseLeave={() => setHover(null)}
                className={`h-full transition-all duration-700 first:rounded-l-full last:rounded-r-full ${p.className} ${hover && hover !== p.key ? 'opacity-40' : ''}`}
                style={{ flexGrow: mounted ? p.amount : 0, flexBasis: 0 }} />
            ))}
          </div>
          <p className="min-h-[1.25rem] text-xs text-gray-500 dark:text-gray-400">
            {focus
              ? <><span className="font-medium text-gray-700 dark:text-gray-200">{focus.label}</span> <Money amount={focus.amount} /> a month</>
              : <>Take-home <Money amount={data!.takeHome} /> minus needs, loans, other spending and SIPs.</>}
          </p>
        </div>
      )}
      <LinkOut onClick={() => onOpen('cash-flow')}>Cash flow</LinkOut>
    </Card>
  )
}

/** Unscored flags, renewals in the next 30 days, and figures nobody has verified. */
/** Do the goals meet their targets: each goal's verdict and chance, worst first. */
function GoalChecksCard({ onOpen, onOpenGoal }: { onOpen: (view: string) => void; onOpenGoal: (id: string) => void }) {
  const { data } = useGoalChecks()
  if (!data || !data.length) return null
  const meeting = data.filter(c => c.verdict === 'on_track').length
  const worst = data[0].verdict
  return (
    <Card title="Your goals" icon={Target} iconTone="bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200"
      badge={<span className={`rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums ${meeting === data.length ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300' : 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'}`}>{meeting} of {data.length} on track</span>}>
      <ul className="-mx-2 flex flex-col" aria-label="Goal checks">
        {data.slice(0, 5).map(c => (
          <li key={c.goalId}>
            <button type="button" onClick={() => onOpenGoal(c.goalId)}
              className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-gray-50 dark:hover:bg-gray-800/60">
              <span className={`h-2 w-2 shrink-0 rounded-full ${VERDICTS[c.verdict].bar}`} aria-hidden />
              <span className="min-w-0 flex-1 truncate text-gray-800 dark:text-gray-200">{c.name}</span>
              {c.successPct != null && <span className="text-xs tabular-nums text-gray-500 dark:text-gray-400">{Math.round(c.successPct)}%</span>}
              <VerdictPill check={c} />
            </button>
          </li>
        ))}
      </ul>
      {worst !== 'on_track' && worst !== 'not_enough_data' && <p className="text-xs text-gray-600 dark:text-gray-300"><span className="font-medium">{data[0].name}:</span> {data[0].fix}</p>}
      <LinkOut onClick={() => onOpen('goals')}>See every goal</LinkOut>
    </Card>
  )
}

function NeedsAttention({ h, onOpen }: { h: HealthView; onOpen: (view: string) => void }) {
  const { data: policies } = usePolicies()
  const renewals = (policies ?? []).filter(p => p.renewalDate && daysUntil(p.renewalDate) >= 0 && daysUntil(p.renewalDate) <= 30)
  const items: { icon: LucideIcon; text: string; view: string }[] = []
  if (h.flags.nomineeMissing) items.push({ icon: UserX, text: 'A nominee is missing on an account', view: 'net-worth' })
  if (h.flags.willMissing) items.push({ icon: FileWarning, text: 'No will recorded', view: 'protection' })
  for (const p of renewals) {
    items.push({ icon: CalendarClock, text: `${POLICY_KINDS[p.kind]} policy renews on ${formatDay(p.renewalDate)}`, view: 'protection' })
  }
  if (h.flags.needsVerification) items.push({ icon: ShieldAlert, text: 'Some tax or regulatory figures need verification', view: 'settings' })
  if (!items.length) return null
  return (
    <Card title="Needs attention" icon={Bell} iconTone="bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
      badge={<span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold tabular-nums text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">{items.length}</span>}>
      <ul className="-mx-2 flex flex-col">
        {items.map(i => (
          <li key={i.text}>
            <button type="button" onClick={() => onOpen(i.view)}
              className="group/item flex w-full items-start gap-2.5 rounded-xl px-2 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-50 dark:text-gray-200 dark:hover:bg-gray-800/70">
              <i.icon className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" aria-hidden />
              <span className="flex-1">{i.text}</span>
              <ChevronRight className="mt-0.5 h-4 w-4 shrink-0 text-gray-300 transition-transform motion-safe:group-hover/item:translate-x-0.5 dark:text-gray-600" aria-hidden />
            </button>
          </li>
        ))}
      </ul>
    </Card>
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
    <Card title="Still to answer" icon={ListChecks} iconTone="bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300">
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
    </Card>
  )
}
