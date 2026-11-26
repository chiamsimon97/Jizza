import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft, ArrowRight, BookOpen, Check, CheckCircle2, Circle, CircleDashed, Clock, Compass, Eye, EyeOff, FileSpreadsheet, FileText,
  GitBranch, LayoutGrid, ListChecks, type LucideIcon, Receipt, SkipForward, Shield, SlidersHorizontal, Sparkles, Target, Undo2, Users, Wallet, X,
} from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { FinProfileView, ImportKind } from '@/types/finance'
import { useMounted } from '../components/motion'
import { errorText, usePolicies } from '../data'
import { findView } from '../nav'
import { financeKeys } from '../queryKeys'
import { useGoals } from './Goals'

export type SetupStepId = 'quick-start' | 'people' | 'settings' | 'goals' | 'plan-builder' | 'bank-files' | 'investment-files'
  | 'holdings' | 'asset-tree' | 'protection' | 'tax'

export type StepStatus = 'done' | 'partial' | 'todo' | 'skipped'

export interface SetupStep {
  id: SetupStepId
  title: string
  /** One line in the list. */
  short: string
  /** Why it matters, in a sentence or two. */
  why: string
  /** What to have to hand. */
  need: string[]
  minutes: number
  /** The Portfolio view the step happens on. */
  view: string
  optional?: boolean
  icon: LucideIcon
  /** Kai can see when this is done; otherwise the user marks it. */
  auto: boolean
}

export interface SetupStage { title: string; text: string; steps: SetupStepId[] }

export const SETUP_STEPS: SetupStep[] = [
  {
    id: 'quick-start', title: 'Answer Quick start', icon: Sparkles, view: 'quick-start', minutes: 10, auto: true,
    short: 'Eight short questions about income, spending, loans, cover, savings and goals.',
    why: 'This gives Kai enough to work out your first Money Health score and the three things to do first. You can skip any question and come back.',
    need: ['Your monthly take-home pay', 'A rough idea of monthly spending', 'Loan and card balances with their rates', 'Term and health policy cover amounts'],
  },
  {
    id: 'people', title: 'Add the people you manage', icon: Users, view: 'people', minutes: 3, auto: true, optional: true,
    short: 'A spouse, child or parent whose money you look after.',
    why: 'Kai then works out cover, tax and goals for each person. Skip this if you manage only your own money.',
    need: ['Name and date of birth of each person', 'Whether they earn or depend on you'],
  },
  {
    id: 'settings', title: "Look over Kai's defaults", icon: SlidersHorizontal, view: 'settings', minutes: 5, auto: false, optional: true,
    short: 'Inflation, expected returns, emergency months and tax figures.',
    why: 'Kai plans with these numbers. Change any that do not fit you; figures marked "needs verification" are worth a second look.',
    need: ['Nothing; read and change what you like'],
  },
  {
    id: 'goals', title: 'Set your goals', icon: Target, view: 'goals', minutes: 8, auto: true,
    short: 'What you are saving for, how much it costs today and when you need it.',
    why: 'Every goal gets a target with inflation, a monthly amount to put in and a starting mix that suits how far away it is.',
    need: ['Each goal’s cost today and its date', 'What you have already set aside for it'],
  },
  {
    id: 'plan-builder', title: 'Build your plan', icon: LayoutGrid, view: 'plan-builder', minutes: 5, auto: true,
    short: 'Split what you invest each month across short, mid and long term.',
    why: 'The plan turns your goals into one monthly figure, split down to each kind of fund, so you know where each rupee goes.',
    need: ['How much you can invest each month'],
  },
  {
    id: 'bank-files', title: 'Bring in bank statements', icon: FileSpreadsheet, view: 'connect', minutes: 5, auto: true,
    short: 'A CSV or PDF statement from your bank, one account at a time.',
    why: 'Kai sorts spending into needs, wants and savings, so the Money Health checks use what you really spend rather than a guess.',
    need: ['A statement file for the last one to three months'],
  },
  {
    id: 'investment-files', title: 'Bring in your investments', icon: FileText, view: 'connect', minutes: 5, auto: true,
    short: 'Your mutual fund statement (CAS) and your broker’s holdings or tradebook.',
    why: 'Kai builds your holdings with each purchase as a tax lot, so gains and tax terms are right when you sell.',
    need: ['CAS PDF from CAMS or KFintech, and its password', 'Your broker’s holdings or tradebook export'],
  },
  {
    id: 'holdings', title: 'Give each unit a goal', icon: Wallet, view: 'holdings', minutes: 5, auto: true,
    short: 'Say how many units of each holding belong to which goal.',
    why: 'Once every unit has a goal, Kai can tell how funded each goal is and whether its mix has drifted. Moving units between goals is not a sale.',
    need: ['Your goals saved', 'Your investment files imported'],
  },
  {
    id: 'asset-tree', title: 'Check each goal’s mix', icon: GitBranch, view: 'asset-tree', minutes: 5, auto: false, optional: true,
    short: 'Asset classes and their parts, as a share of each goal.',
    why: 'Kai starts every goal from a mix that suits its distance. Save it as it is, or change the targets to make it yours.',
    need: ['Your goals saved'],
  },
  {
    id: 'protection', title: 'Record your cover', icon: Shield, view: 'protection', minutes: 4, auto: true,
    short: 'Term and health policies, with their cover and renewal dates.',
    why: 'Kai compares cover against what each person needs and reminds you before a policy renews.',
    need: ['Each policy’s cover amount, premium and renewal date'],
  },
  {
    id: 'tax', title: 'Compare tax regimes', icon: Receipt, view: 'tax', minutes: 5, auto: false, optional: true,
    short: 'Your deductions, and which regime leaves you more.',
    why: 'Kai works out both regimes for the year with your deductions, so you can choose with the numbers in front of you.',
    need: ['Your 80C, 80D and home loan interest figures'],
  },
]

export const SETUP_STAGES: SetupStage[] = [
  { title: 'Start with the basics', text: 'Your score and who you plan for', steps: ['quick-start', 'people', 'settings'] },
  { title: 'Set goals and a plan', text: 'What you save for and how', steps: ['goals', 'plan-builder'] },
  { title: 'Bring your money in', text: 'Statements and investment files', steps: ['bank-files', 'investment-files'] },
  { title: 'Put investments to work', text: 'Goals for every unit', steps: ['holdings', 'asset-tree'] },
  { title: 'Protect and tidy up', text: 'Cover and tax', steps: ['protection', 'tax'] },
]

const BANK_KINDS: ImportKind[] = ['bank_csv', 'bank_pdf']
const INVESTMENT_KINDS: ImportKind[] = ['cas_pdf', 'broker_holdings', 'broker_tradebook']

export interface StepState { status: StepStatus; evidence: string | null; marked: 'done' | 'skipped' | null }

export interface SetupProgress {
  steps: Record<SetupStepId, StepState>
  done: number
  total: number
  /** The first step that is neither done nor skipped. */
  next: SetupStep | null
  minutesLeft: number
  loading: boolean
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

/**
 * Where setup has got to. Steps Kai can see are worked out from the data (a goal saved, a file
 * imported, every unit given a goal); the rest count once the user marks them done or skipped.
 * A step the data shows as done stays done whatever its mark says.
 */
export function useSetupProgress(profile: FinProfileView): SetupProgress {
  const marks = profile.settings.setupMarks ?? {}
  const goals = useGoals()
  const builder = useQuery({ queryKey: financeKeys.builder, queryFn: financeApi.builder })
  const imports = useQuery({ queryKey: financeKeys.imports, queryFn: financeApi.imports })
  const holdings = useQuery({ queryKey: financeKeys.holdings(null, null), queryFn: () => financeApi.holdings() })
  const policies = usePolicies()

  const live = (goals.data ?? []).filter(g => g.goal.status !== 'archived')
  const committed = (imports.data ?? []).filter(b => b.status === 'committed')
  const rows = holdings.data?.rows ?? []
  const unassigned = rows.filter(r => r.unassignedUnits > 0).length

  const auto: Partial<Record<SetupStepId, { status: StepStatus; evidence: string | null }>> = {
    'quick-start': profile.setupProgress.quickStart === 'done'
      ? { status: 'done', evidence: 'All questions answered or skipped' }
      : profile.setupProgress.quickStart === 'in_progress' ? { status: 'partial', evidence: 'Started; some questions left' } : { status: 'todo', evidence: null },
    people: profile.setupProgress.peopleManaged > 0
      ? { status: 'done', evidence: `${plural(profile.setupProgress.peopleManaged, 'person', 'people')} added` } : { status: 'todo', evidence: null },
    goals: live.length ? { status: 'done', evidence: `${plural(live.length, 'goal')} saved` } : { status: 'todo', evidence: null },
    'plan-builder': builder.data?.savedAt ? { status: 'done', evidence: 'Plan saved' } : { status: 'todo', evidence: null },
    'bank-files': (() => {
      const n = committed.filter(b => BANK_KINDS.includes(b.kind)).length
      return n ? { status: 'done' as const, evidence: `${plural(n, 'statement')} imported` } : { status: 'todo' as const, evidence: null }
    })(),
    'investment-files': (() => {
      const n = committed.filter(b => INVESTMENT_KINDS.includes(b.kind)).length
      return n ? { status: 'done' as const, evidence: `${plural(n, 'file')} imported` } : { status: 'todo' as const, evidence: null }
    })(),
    holdings: !rows.length ? { status: 'todo', evidence: null }
      : unassigned ? { status: 'partial', evidence: `${plural(unassigned, 'holding')} still ${unassigned === 1 ? 'has' : 'have'} unassigned units` }
        : { status: 'done', evidence: `Every unit of ${plural(rows.length, 'holding')} has a goal` },
    protection: (policies.data ?? []).length ? { status: 'done', evidence: `${plural(policies.data!.length, 'policy', 'policies')} recorded` } : { status: 'todo', evidence: null },
  }

  const steps = {} as Record<SetupStepId, StepState>
  for (const s of SETUP_STEPS) {
    const a = auto[s.id]
    const mark = marks[s.id] ?? null
    let status: StepStatus = a?.status ?? 'todo'
    if (status !== 'done' && mark) status = mark
    steps[s.id] = { status, evidence: a?.evidence ?? (mark === 'done' ? 'You marked this done' : null), marked: mark }
  }
  const settled = (id: SetupStepId) => steps[id].status === 'done' || steps[id].status === 'skipped'
  const done = SETUP_STEPS.filter(s => settled(s.id)).length
  return {
    steps, done, total: SETUP_STEPS.length,
    next: SETUP_STEPS.find(s => !settled(s.id)) ?? null,
    minutesLeft: SETUP_STEPS.filter(s => !settled(s.id)).reduce((m, s) => m + s.minutes, 0),
    loading: goals.isLoading || builder.isLoading || imports.isLoading || holdings.isLoading || policies.isLoading,
  }
}

/** Saves a hand mark on a step ('' clears it) or puts the guide card away. */
export function useSetupMarks() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: { mark?: [SetupStepId, 'done' | 'skipped' | '']; hidden?: boolean }) => financeApi.updateSettings({
      ...(patch.mark ? { setupMarks: { [patch.mark[0]]: patch.mark[1] } } : {}),
      ...(patch.hidden != null ? { setupGuideHidden: patch.hidden } : {}),
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.profile }),
  })
}

export const stepById = (id: string) => SETUP_STEPS.find(s => s.id === id)

const STATUS_ICON: Record<StepStatus, { icon: LucideIcon; className: string; word: string }> = {
  done: { icon: CheckCircle2, className: 'text-emerald-500', word: 'Done' },
  partial: { icon: CircleDashed, className: 'text-amber-500', word: 'Started' },
  todo: { icon: Circle, className: 'text-gray-300 dark:text-gray-600', word: 'To do' },
  skipped: { icon: SkipForward, className: 'text-gray-400', word: 'Skipped' },
}

/** A ring showing how many steps are settled. */
export function SetupRing({ done, total, size = 88 }: { done: number; total: number; size?: number }) {
  const mounted = useMounted()
  const r = 40
  const c = 2 * Math.PI * r
  const pct = total ? done / total : 0
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={`${done} of ${total} setup steps done`}>
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="10" className="stroke-gray-200/80 dark:stroke-gray-800" />
        <circle cx="50" cy="50" r={r} fill="none" strokeWidth="10" strokeLinecap="round" className="stroke-brand-500"
          strokeDasharray={`${(mounted ? pct : 0) * c} ${c}`} style={{ transition: 'stroke-dasharray 1s cubic-bezier(0.16, 1, 0.3, 1)' }} />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center" aria-hidden>
        <span className="text-xl font-bold tabular-nums text-gray-900 dark:text-gray-100">{done}/{total}</span>
        <span className="text-[10px] text-gray-500 dark:text-gray-400">steps</span>
      </div>
    </div>
  )
}

/**
 * The setup walkthrough: every step to set the Portfolio up, in five stages, each with why it
 * matters, what to have to hand and how long it takes. "Go to" opens the step's screen with the
 * guide bar along the bottom, so the user can come back or move on from there.
 */
export function SetupGuide({ profile, onGo }: { profile: FinProfileView; onGo: (step: SetupStep) => void }) {
  const progress = useSetupProgress(profile)
  const marks = useSetupMarks()
  const [picked, setPicked] = useState<SetupStepId | null>(null)
  const current = stepById(picked ?? progress.next?.id ?? SETUP_STEPS[0].id)!
  const state = progress.steps[current.id]
  const hidden = profile.settings.setupGuideHidden ?? false
  const allDone = progress.done === progress.total
  const index = SETUP_STEPS.findIndex(s => s.id === current.id)

  return (
    <div className="flex flex-col gap-5">
      <section className="relative overflow-hidden rounded-3xl border border-gray-200 bg-gradient-to-br from-brand-50 via-white to-emerald-50/60 p-5 shadow-sm motion-safe:animate-fade-in-up dark:border-gray-800 dark:from-brand-500/10 dark:via-gray-900 dark:to-emerald-500/5 sm:p-6">
        <div aria-hidden className="pointer-events-none absolute -right-20 -top-20 h-64 w-64 rounded-full bg-brand-300/25 blur-3xl dark:bg-brand-500/15" />
        <div className="relative flex flex-wrap items-center gap-5">
          <SetupRing done={progress.done} total={progress.total} />
          <div className="flex min-w-0 flex-1 basis-64 flex-col gap-1.5">
            <p className="inline-flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-brand-600 dark:text-brand-300">
              <Compass className="h-3.5 w-3.5" aria-hidden /> Setup guide
            </p>
            <h2 className="text-xl font-bold tracking-tight text-gray-900 dark:text-gray-100">
              {allDone ? 'Your Portfolio is set up' : progress.done === 0 ? 'Set up your Portfolio, one step at a time' : 'Keep going: you are on your way'}
            </h2>
            <p className="text-sm text-gray-600 dark:text-gray-300">
              {allDone
                ? 'Every step is done or skipped. Kai keeps your score, goals and holdings up to date from here.'
                : `${plural(progress.total - progress.done, 'step')} left, about ${progress.minutesLeft} minutes in all. Steps Kai can see tick themselves; the rest you mark. Optional steps can be skipped.`}
            </p>
            <div className="mt-1 flex flex-wrap gap-2">
              {progress.next && (
                <Button onClick={() => onGo(progress.next!)}>
                  {progress.done === 0 ? 'Start' : 'Continue'}: {progress.next.title} <ArrowRight className="h-4 w-4" aria-hidden />
                </Button>
              )}
              <Button variant="ghost" size="sm" loading={marks.isPending && marks.variables?.hidden != null}
                onClick={() => marks.mutate({ hidden: !hidden })}>
                {hidden ? <Eye className="h-4 w-4" aria-hidden /> : <EyeOff className="h-4 w-4" aria-hidden />}
                {hidden ? 'Show the guide on Home' : 'Hide the guide on Home'}
              </Button>
            </div>
          </div>
        </div>
      </section>

      <div className="grid gap-5 lg:grid-cols-5">
        <nav aria-label="Setup steps" className="flex flex-col gap-4 lg:col-span-2">
          {SETUP_STAGES.map((stage, si) => {
            const settled = stage.steps.filter(id => ['done', 'skipped'].includes(progress.steps[id].status)).length
            const complete = settled === stage.steps.length
            return (
              <section key={stage.title} className="motion-safe:animate-fade-in-up" style={{ animationDelay: `${si * 60}ms` }}>
                <div className="mb-1.5 flex items-center gap-2">
                  <span className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold ${complete
                    ? 'bg-emerald-500 text-white' : 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900'}`} aria-hidden>
                    {complete ? <Check className="h-3.5 w-3.5" /> : si + 1}
                  </span>
                  <h3 className="flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">{stage.title}</h3>
                  <span className="text-xs tabular-nums text-gray-500 dark:text-gray-400">{settled}/{stage.steps.length}</span>
                </div>
                <ol className="ml-3 flex flex-col gap-1 border-l-2 border-gray-200 pl-3 dark:border-gray-800">
                  {stage.steps.map(id => {
                    const step = stepById(id)!
                    const st = progress.steps[id]
                    const S = STATUS_ICON[st.status]
                    const on = id === current.id
                    return (
                      <li key={id}>
                        <button type="button" onClick={() => setPicked(id)} aria-current={on ? 'step' : undefined}
                          className={`group flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left transition-all ${on
                            ? 'bg-white shadow-sm ring-1 ring-brand-200 dark:bg-gray-900 dark:ring-brand-800/60'
                            : 'hover:bg-white/70 dark:hover:bg-gray-900/60'}`}>
                          <S.icon className={`h-5 w-5 shrink-0 ${S.className}`} aria-label={S.word} />
                          <span className="flex min-w-0 flex-1 flex-col">
                            <span className={`text-sm font-medium ${st.status === 'skipped' ? 'text-gray-400 line-through' : 'text-gray-900 dark:text-gray-100'}`}>{step.title}</span>
                            <span className="truncate text-xs text-gray-500 dark:text-gray-400">{st.evidence ?? step.short}</span>
                          </span>
                          {step.optional && st.status === 'todo' && <span className="rounded-full bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500 dark:bg-gray-800 dark:text-gray-400">Optional</span>}
                        </button>
                      </li>
                    )
                  })}
                </ol>
              </section>
            )
          })}
        </nav>

        <StepDetail key={current.id} step={current} state={state} index={index} onGo={onGo} marks={marks}
          onNext={() => { const n = SETUP_STEPS[index + 1]; if (n) setPicked(n.id) }}
          onPrev={() => { const p = SETUP_STEPS[index - 1]; if (p) setPicked(p.id) }} />
      </div>

      <p className="inline-flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
        <BookOpen className="h-3.5 w-3.5" aria-hidden />
        The Help page has the full guide under Kai Portfolio, “Set up your Portfolio”.
      </p>
    </div>
  )
}

function StepDetail({ step, state, index, onGo, onNext, onPrev, marks }: {
  step: SetupStep
  state: StepState
  index: number
  onGo: (step: SetupStep) => void
  onNext: () => void
  onPrev: () => void
  marks: ReturnType<typeof useSetupMarks>
}) {
  const S = STATUS_ICON[state.status]
  const busy = (v: string) => marks.isPending && marks.variables?.mark?.[1] === v
  const autoDone = step.auto && state.status === 'done' && state.marked !== 'done'
  return (
    <article className="flex flex-col gap-4 self-start rounded-2xl border border-gray-200 bg-white p-5 shadow-sm motion-safe:animate-fade-in dark:border-gray-800 dark:bg-gray-900 lg:sticky lg:top-40 lg:col-span-3"
      aria-labelledby="setup-step-title">
      <div className="flex items-start gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-400 to-brand-600 text-white shadow-glow" aria-hidden>
          <step.icon className="h-5 w-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <p className="text-xs font-medium text-gray-500 dark:text-gray-400">Step {index + 1} of {SETUP_STEPS.length}</p>
          <h3 id="setup-step-title" className="text-lg font-semibold tracking-tight text-gray-900 dark:text-gray-100">{step.title}</h3>
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700 dark:bg-gray-800 dark:text-gray-300">
              <S.icon className={`h-3.5 w-3.5 ${S.className}`} aria-hidden />{S.word}
            </span>
            <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
              <Clock className="h-3 w-3" aria-hidden />About {step.minutes} min
            </span>
            {step.optional && <span className="rounded-full bg-cyan-50 px-2 py-0.5 text-xs font-medium text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300">Optional</span>}
          </div>
        </div>
      </div>

      {state.evidence && (
        <p className={`flex items-center gap-2 rounded-xl px-3 py-2 text-sm ${state.status === 'done'
          ? 'bg-emerald-50 text-emerald-800 dark:bg-emerald-900/20 dark:text-emerald-200'
          : 'bg-amber-50 text-amber-800 dark:bg-amber-900/20 dark:text-amber-200'}`}>
          {state.status === 'done' ? <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden /> : <CircleDashed className="h-4 w-4 shrink-0" aria-hidden />}
          {autoDone ? `Kai can see this is done: ${state.evidence.toLowerCase()}.` : `${state.evidence}.`}
        </p>
      )}

      <p className="text-sm leading-relaxed text-gray-700 dark:text-gray-300">{step.why}</p>

      <div className="rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50">
        <p className="mb-1.5 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
          <ListChecks className="h-3.5 w-3.5" aria-hidden /> Have to hand
        </p>
        <ul className="flex flex-col gap-1">
          {step.need.map(n => (
            <li key={n} className="flex items-start gap-2 text-sm text-gray-700 dark:text-gray-300">
              <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand-500" aria-hidden />{n}
            </li>
          ))}
        </ul>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => onGo(step)}>
          {state.status === 'done' ? 'Open' : 'Go to'} {findView(step.view).label} <ArrowRight className="h-4 w-4" aria-hidden />
        </Button>
        {!autoDone && state.status !== 'done' && (
          <Button variant="secondary" loading={busy('done')} onClick={() => marks.mutate({ mark: [step.id, 'done'] })}>
            <Check className="h-4 w-4" aria-hidden /> Mark as done
          </Button>
        )}
        {state.status !== 'done' && state.status !== 'skipped' && step.optional && (
          <Button variant="ghost" loading={busy('skipped')} onClick={() => marks.mutate({ mark: [step.id, 'skipped'] })}>
            <SkipForward className="h-4 w-4" aria-hidden /> Skip this
          </Button>
        )}
        {state.marked && !autoDone && (
          <Button variant="ghost" loading={busy('')} onClick={() => marks.mutate({ mark: [step.id, ''] })}>
            <Undo2 className="h-4 w-4" aria-hidden /> {state.marked === 'done' ? 'Not done yet' : 'Undo skip'}
          </Button>
        )}
      </div>
      {marks.error && <p className="text-sm text-red-600" role="alert">{errorText(marks.error, 'Could not save that.')}</p>}

      <div className="flex justify-between border-t border-gray-100 pt-3 dark:border-gray-800">
        <Button variant="ghost" size="sm" disabled={index === 0} onClick={onPrev}><ArrowLeft className="h-4 w-4" aria-hidden />Previous step</Button>
        <Button variant="ghost" size="sm" disabled={index === SETUP_STEPS.length - 1} onClick={onNext}>Next step<ArrowRight className="h-4 w-4" aria-hidden /></Button>
      </div>
    </article>
  )
}

/**
 * The bar along the bottom while the user follows the guide on another screen: which step this
 * is, its status, and ways back to the guide or on to the next step.
 */
export function SetupGuideBar({ profile, stepId, onGuide, onGo, onClose }: {
  profile: FinProfileView
  stepId: string | null
  onGuide: () => void
  onGo: (step: SetupStep) => void
  onClose: () => void
}) {
  const progress = useSetupProgress(profile)
  const marks = useSetupMarks()
  const step = stepById(stepId ?? '') ?? progress.next
  if (!step) return null
  const state = progress.steps[step.id]
  const index = SETUP_STEPS.findIndex(s => s.id === step.id)
  const following = SETUP_STEPS.slice(index + 1).find(s => !['done', 'skipped'].includes(progress.steps[s.id].status)) ?? null
  const S = STATUS_ICON[state.status]
  const settled = state.status === 'done' || state.status === 'skipped'
  return (
    <div role="region" aria-label="Setup guide"
      className="sticky bottom-3 z-20 mx-auto flex w-full max-w-3xl flex-wrap items-center gap-x-3 gap-y-2 rounded-2xl border border-brand-200 bg-white/95 px-3 py-2 shadow-lg backdrop-blur motion-safe:animate-fade-in-up dark:border-brand-800/60 dark:bg-gray-900/95">
      <Compass className="h-5 w-5 shrink-0 text-brand-500" aria-hidden />
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="text-[11px] font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">Setup guide · step {index + 1} of {SETUP_STEPS.length}</span>
        <span className="inline-flex items-center gap-1.5 truncate text-sm font-semibold text-gray-900 dark:text-gray-100">
          <S.icon className={`h-4 w-4 shrink-0 ${S.className}`} aria-label={S.word} />{step.title}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-1">
        <Button size="sm" variant="ghost" onClick={onGuide}><ListChecks className="h-4 w-4" aria-hidden />All steps</Button>
        {!settled && !step.auto && (
          <Button size="sm" variant="secondary" loading={marks.isPending}
            onClick={() => marks.mutate({ mark: [step.id, 'done'] }, { onSuccess: () => { if (following) onGo(following) } })}>
            <Check className="h-4 w-4" aria-hidden />Mark done
          </Button>
        )}
        {following
          ? <Button size="sm" onClick={() => onGo(following)}>Next: {following.title}<ArrowRight className="h-4 w-4" aria-hidden /></Button>
          : <Button size="sm" onClick={onGuide}>Finish<Check className="h-4 w-4" aria-hidden /></Button>}
        <button type="button" onClick={onClose} aria-label="Close the setup guide"
          className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
          <X className="h-4 w-4" />
        </button>
      </div>
    </div>
  )
}

/** The compact card on Home: progress, the next step and a way into the guide. */
export function SetupCard({ profile, onGuide, onGo }: { profile: FinProfileView; onGuide: () => void; onGo: (step: SetupStep) => void }) {
  const progress = useSetupProgress(profile)
  const marks = useSetupMarks()
  const mounted = useMounted()
  if (profile.settings.setupGuideHidden || progress.loading || !progress.next) return null
  const pct = (progress.done / progress.total) * 100
  return (
    <section className="relative flex flex-col gap-3 overflow-hidden rounded-2xl border border-brand-200 bg-gradient-to-br from-brand-50 to-white p-4 shadow-sm dark:border-brand-800/60 dark:from-brand-500/10 dark:to-gray-900"
      aria-label="Set up your Portfolio">
      <div className="flex items-center gap-2">
        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-500 text-white" aria-hidden><Compass className="h-4 w-4" /></span>
        <h3 className="flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">Set up your Portfolio</h3>
        <button type="button" onClick={() => marks.mutate({ hidden: true })} aria-label="Hide the setup guide"
          className="rounded-md p-1 text-gray-400 hover:bg-white hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200"><X className="h-4 w-4" /></button>
      </div>
      <div className="flex flex-col gap-1">
        <div className="flex justify-between text-xs text-gray-600 dark:text-gray-300">
          <span>{progress.done} of {progress.total} steps</span><span>About {progress.minutesLeft} min left</span>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-white dark:bg-gray-800" aria-hidden>
          <div className="h-full rounded-full bg-gradient-to-r from-brand-400 to-brand-600"
            style={{ width: `${mounted ? pct : 0}%`, transition: 'width 1s cubic-bezier(0.16, 1, 0.3, 1)' }} />
        </div>
      </div>
      <button type="button" onClick={() => onGo(progress.next!)}
        className="group flex items-center gap-3 rounded-xl bg-white p-2.5 text-left shadow-sm ring-1 ring-gray-200 transition hover:ring-brand-300 dark:bg-gray-900 dark:ring-gray-700">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200" aria-hidden>
          <progress.next.icon className="h-4 w-4" />
        </span>
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">Next step</span>
          <span className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{progress.next.title}</span>
        </span>
        <ArrowRight className="h-4 w-4 text-brand-500 transition-transform motion-safe:group-hover:translate-x-0.5" aria-hidden />
      </button>
      <button type="button" onClick={onGuide} className="self-start text-sm font-medium text-brand-600 hover:underline dark:text-brand-300">See all steps</button>
    </section>
  )
}
