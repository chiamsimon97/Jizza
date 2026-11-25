import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { FinProfileView, QuickStartAnswers, QuickStartStep } from '@/types/finance'
import { ActionCard } from '../components/ActionCard'
import { Money } from '../components/Money'
import { ScoreRing } from '../components/ScoreRing'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText, useHealth, useNetWorth } from '../data'
import { financeKeys } from '../queryKeys'
import { IncomeStep, InsuranceStep, LoansStep, PeopleStep, SavingsStep, SpendingStep, type Payload, type StepProps } from './quickstart/steps'

/** Quick start's questions (7.13), one per screen. Goals and risk (7 and 8) come with Phase 1b. */
export const QUICK_START_STEPS: { id: QuickStartStep; title: string; why: string }[] = [
  { id: 'people', title: 'Whose money do you look after here?',
    why: 'You, and anyone without their own login, such as a child or a parent. Family members with their own account join through a Family.' },
  { id: 'income', title: 'What comes in each month?', why: 'Every check is measured against take-home pay.' },
  { id: 'spending', title: 'What goes out each month?', why: 'Needs set the size of your emergency fund; the rest shows what is left to save.' },
  { id: 'loans', title: 'Any loans or borrowings?', why: 'Bank loans, cards, gold loans, money from family or a moneylender: Kai puts them in the order to repay.' },
  { id: 'insurance', title: 'What cover do you have?', why: 'Term and health cover protect everything else. Kai never names or recommends an insurer.' },
  { id: 'savings', title: 'What have you saved so far?', why: 'Rough totals are enough to see your net worth and emergency money.' },
]

type StepComponent = (props: StepProps<QuickStartStep>) => JSX.Element
const BODIES: Record<QuickStartStep, StepComponent> = {
  people: PeopleStep as StepComponent, income: IncomeStep as StepComponent, spending: SpendingStep as StepComponent,
  loans: LoansStep as StepComponent, insurance: InsuranceStep as StepComponent, savings: SavingsStep as StepComponent,
}

/**
 * Quick start (7.13): six questions, each saved as it is answered so the user can stop and come
 * back; each can be skipped, which leaves its checks "Not enough data". Then the result.
 */
export function QuickStart({ profile, startAt, onOpen }: {
  profile: FinProfileView
  /** Open on this question (from "Answer this"); otherwise where the user stopped. */
  startAt?: QuickStartStep | null
  onOpen: (view: string) => void
}) {
  const qc = useQueryClient()
  const { data: qs, error: loadError, refetch } = useQuery({ queryKey: financeKeys.quickStart, queryFn: financeApi.quickStart })
  const { data: people } = useQuery({ queryKey: financeKeys.people, queryFn: financeApi.people, initialData: profile.people })
  const [step, setStep] = useState<QuickStartStep | 'result' | null>(null)
  const [answers, setAnswers] = useState<QuickStartAnswers>({})
  const [payload, setPayload] = useState<Payload<QuickStartStep> | null>(null)
  const [showProblem, setShowProblem] = useState(false)

  useEffect(() => {
    if (qs && step == null) {
      setAnswers(qs.answers ?? {})
      setStep(startAt ?? qs.progress.next ?? 'result')
    }
  }, [qs, step, startAt])

  const index = QUICK_START_STEPS.findIndex(s => s.id === step)
  /** Moves on from the question that was answered, named by the mutation, not by whatever is on screen when it settles. */
  const advance = (from: QuickStartStep) => {
    const at = QUICK_START_STEPS.findIndex(s => s.id === from)
    setPayload(null)
    setShowProblem(false)
    setStep(at >= 0 && at < QUICK_START_STEPS.length - 1 ? QUICK_START_STEPS[at + 1].id : 'result')
  }
  const refresh = () => qc.invalidateQueries({ queryKey: financeKeys.all })
  const save = useMutation({
    mutationFn: async ({ id, value }: { id: QuickStartStep; value: QuickStartAnswers[QuickStartStep] }) =>
      financeApi.saveQuickStart(id, value as never),
    onSuccess: (_, { id, value }) => { setAnswers(a => ({ ...a, [id]: value })); advance(id); refresh() },
  })
  const skip = useMutation({
    mutationFn: (id: QuickStartStep) => financeApi.skipQuickStart(id),
    onSuccess: (_, id) => { advance(id); refresh() },
  })

  if (loadError) {
    return <ErrorCard text="Could not open Quick start." onRetry={() => refetch()} />
  }
  if (!qs || step == null || !people) return <SkeletonRows rows={5} />
  if (step === 'result') return <Result onOpen={onOpen} onRestart={() => setStep('people')} />

  const meta = QUICK_START_STEPS[index]
  const Body = BODIES[meta.id]
  const next = () => {
    if (!payload || !payload.ok) { setShowProblem(true); return }
    save.mutate({ id: meta.id, value: payload.answers })
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Quick start</h2>
        <Button size="sm" variant="ghost" onClick={() => onOpen('home')}>Finish later</Button>
      </div>
      <section className="flex flex-col gap-4 rounded-2xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900 sm:p-5" aria-labelledby="qs-title">
        <div className="flex flex-col gap-1.5">
          <div className="flex justify-between text-xs text-gray-500 dark:text-gray-400">
            <span>Question {index + 1} of {QUICK_START_STEPS.length}</span>
            <span>About 10 minutes in all</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" role="progressbar"
            aria-valuemin={0} aria-valuemax={QUICK_START_STEPS.length} aria-valuenow={index + 1} aria-label="Quick start progress">
            <div className="h-full rounded-full bg-amber-500 transition-all" style={{ width: `${((index + 1) / QUICK_START_STEPS.length) * 100}%` }} />
          </div>
        </div>
        <div>
          <h3 id="qs-title" className="text-lg font-semibold text-gray-900 dark:text-gray-100">{meta.title}</h3>
          <p className="mt-1 flex gap-1.5 text-sm text-gray-500 dark:text-gray-400"><Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />Why we ask: {meta.why}</p>
        </div>
        <Body key={meta.id} saved={answers[meta.id] as never} people={people} all={answers} onChange={p => { setPayload(p); setShowProblem(false) }} />
        {showProblem && payload && !payload.ok && <p className="text-sm text-red-600" role="alert">{payload.message}</p>}
        {(save.error || skip.error) && <p className="text-sm text-red-600" role="alert">{errorText(save.error ?? skip.error, 'Could not save this answer.')}</p>}
        <div className="flex flex-wrap items-center gap-2 border-t border-gray-100 pt-3 dark:border-gray-800">
          <Button variant="secondary" disabled={index === 0} onClick={() => { setPayload(null); setStep(QUICK_START_STEPS[index - 1].id) }}>Back</Button>
          <span className="flex-1" />
          <Button variant="ghost" loading={skip.isPending} onClick={() => skip.mutate(meta.id)}>Skip for now</Button>
          <Button loading={save.isPending} onClick={next}>{index === QUICK_START_STEPS.length - 1 ? 'See my result' : 'Next'}</Button>
        </div>
      </section>
      <p className="text-center text-xs text-gray-500 dark:text-gray-400">A skipped question leaves its check as “Not enough data”. It never counts against you.</p>
    </div>
  )
}

function ErrorCard({ text, onRetry }: { text: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-xl border border-red-200 p-6 text-center dark:border-red-900/50">
      <p className="text-sm text-red-700 dark:text-red-300">{text}</p>
      <Button size="sm" variant="secondary" onClick={onRetry}>Try again</Button>
    </div>
  )
}

/** The result (7.13): score, net worth, monthly surplus and the top three actions. Nothing is executed. */
function Result({ onOpen, onRestart }: { onOpen: (view: string) => void; onRestart: () => void }) {
  const health = useHealth()
  const worth = useNetWorth()
  if (health.isLoading || worth.isLoading) return <SkeletonRows rows={6} />
  if (!health.data || !worth.data) return <ErrorCard text="Could not work out your result." onRetry={() => { health.refetch(); worth.refetch() }} />
  const h = health.data
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
      <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Your result</h2>
      <div className="grid gap-3 sm:grid-cols-3">
        <div className="flex items-center gap-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
          <ScoreRing score={h.score} size={84} />
          <div className="text-sm text-gray-600 dark:text-gray-300">Money Health{h.score == null ? ': answer a question to get a score.' : ''}</div>
        </div>
        <Stat label="Net worth"><Money amount={worth.data.netWorth} short /></Stat>
        <Stat label="Left each month"><Money amount={h.surplus} /></Stat>
      </div>
      {h.topActions.length > 0 && (
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Your next three steps</h3>
          {h.topActions.map((a, i) => <ActionCard key={a.id} action={a} index={i + 1} />)}
        </section>
      )}
      <p className="text-xs text-gray-500 dark:text-gray-400">Nothing has been bought, sold or moved. Goals and your risk profile come next, with the plan builder.</p>
      <div className="flex flex-wrap gap-2">
        <Button onClick={() => onOpen('home')}>Open Money Health</Button>
        <Button variant="secondary" onClick={onRestart}>Go through the answers again</Button>
      </div>
    </div>
  )
}

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col justify-center rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <span className="text-xs text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-xl font-semibold text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}
