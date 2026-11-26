import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, Lock } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { formatINR } from '@/lib/money'
import type { FinPerson, GoalInput, GoalLibraryCard, GoalTier, GoalType, RiskProfile } from '@/types/finance'
import { AmountField, SelectField, TextField } from '../components/Fields'
import { Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText } from '../data'
import { BUCKETS, GOAL_TIERS, RISK_PROFILES, bucketFor, monthsUntil } from '../labels'
import { financeKeys } from '../queryKeys'
import { TreeEditor } from './AssetTree'
import { ruleWords } from './Rules'

const STEPS = [
  { title: 'Pick a goal', why: 'Kai fills in every later step. You only correct what is wrong.' },
  { title: 'Whose goal is it?', why: 'You, or someone you manage.' },
  { title: 'How much, in today’s rupees?', why: 'Kai grows it by inflation so you don’t have to.' },
  { title: 'When, and how much risk?', why: 'The date decides the bucket.' },
  { title: 'Starting mix', why: 'Comes from the plan builder’s tables. Each part’s share of its parent adds up to 100%.' },
  { title: 'Money in', why: 'What is saved for it already, and what goes in each month.' },
  { title: 'Glide path', why: 'How the mix moves as the date gets closer.', later: true },
  { title: 'Rules and review', why: 'Kai starts in Observe: it watches and flags, and proposes nothing.' },
]

interface Draft {
  type: GoalType | null
  name: string
  ownerPersonId: string | null
  targetAmountToday: number | null
  targetDate: string
  priorityTier: GoalTier
  riskProfile: RiskProfile | ''
  currentValue: number | null
  monthlyContribution: number | null
  stepupPct: number | null
}

const blank: Draft = {
  type: null, name: '', ownerPersonId: null, targetAmountToday: null, targetDate: '', priorityTier: 'important',
  riskProfile: '', currentValue: null, monthlyContribution: null, stepupPct: null,
}

function fromCard(c: GoalLibraryCard, d: Draft): Draft {
  return {
    ...d, type: c.type, name: c.name, priorityTier: c.priority, targetAmountToday: c.targetAmountToday,
    targetDate: c.targetDate ?? '', ownerPersonId: c.ownerPersonId,
  }
}

/** Today's amount grown to the target year at the card's inflation, for the preview line. */
function grown(today: number, pct: number, date: string) {
  const years = Math.max(0, monthsUntil(date) / 12)
  return Math.round((today * Math.pow(1 + pct / 100, years)) / 1000) * 1000
}

/**
 * The goal wizard (7.3): pick a card, then confirm whose goal it is, the amount, the date and the
 * risk, and what goes in each month. Steps 5, 7 and 8 need holdings and say so. Also edits a goal.
 */
export function GoalWizard({ people, editId, onDone, onCancel }: {
  people: FinPerson[]
  editId?: string | null
  onDone: (id: string) => void
  onCancel: () => void
}) {
  const qc = useQueryClient()
  const library = useQuery({ queryKey: financeKeys.goalLibrary, queryFn: financeApi.goalLibrary })
  const existing = useQuery({ queryKey: financeKeys.goal(editId ?? ''), queryFn: () => financeApi.goal(editId!), enabled: !!editId })
  const [step, setStep] = useState(editId ? 1 : 0)
  const [d, setD] = useState<Draft>(blank)
  const [problem, setProblem] = useState<string | null>(null)
  const set = (patch: Partial<Draft>) => { setD(x => ({ ...x, ...patch })); setProblem(null) }

  useEffect(() => {
    const g = existing.data?.goal
    if (!g) return
    setD({
      type: g.type, name: g.name, ownerPersonId: g.ownerPersonId, targetAmountToday: g.targetAmountToday, targetDate: g.targetDate,
      priorityTier: g.priorityTier, riskProfile: g.riskProfile ?? '', currentValue: g.currentValue, monthlyContribution: g.monthlyContribution,
      stepupPct: g.stepupPct,
    })
  }, [existing.data])

  const save = useMutation({
    mutationFn: () => {
      const body: GoalInput = {
        name: d.name.trim(), ownerPersonId: d.ownerPersonId, targetAmountToday: d.targetAmountToday ?? 0, targetDate: d.targetDate,
        priorityTier: d.priorityTier, riskProfile: d.riskProfile || null, currentValue: d.currentValue ?? 0,
        monthlyContribution: d.monthlyContribution ?? 0, stepupPct: d.stepupPct ?? 0,
      }
      return editId ? financeApi.updateGoal(editId, body) : financeApi.addGoal({ ...body, type: d.type! })
    },
    onSuccess: v => { qc.invalidateQueries({ queryKey: financeKeys.all }); onDone(v.goal.id) },
  })

  if (library.isLoading || (editId && existing.isLoading)) return <SkeletonRows rows={5} />
  const cards = library.data ?? []
  const card = cards.find(c => c.type === d.type)
  const active = people.filter(p => p.status === 'active')
  const months = d.targetDate ? monthsUntil(d.targetDate) : null
  const bucket = months != null && months > 0 ? bucketFor(months) : null
  const meta = STEPS[step]

  const check = (): string | null => {
    if (step === 0 && !d.type) return 'Pick a goal to start from.'
    if (step === 2 && !(d.targetAmountToday && d.targetAmountToday > 0)) return 'Enter what the goal costs in today’s rupees.'
    if (step === 2 && !d.name.trim()) return 'Give the goal a name.'
    if (step === 3 && (!d.targetDate || (months ?? 0) <= 0)) return 'Pick a date in the future.'
    return null
  }
  const next = () => {
    const p = check()
    if (p) { setProblem(p); return }
    if (step === STEPS.length - 1) save.mutate()
    else setStep(step + 1)
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">{editId ? 'Edit goal' : 'New goal'}</h2>
        <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
      <section className="flex flex-col gap-4 rounded-2xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900 sm:p-5" aria-labelledby="wz-title">
        <div className="flex flex-col gap-1.5">
          <div className="flex justify-between text-xs text-gray-500 dark:text-gray-400">
            <span>Step {step + 1} of {STEPS.length}</span>
            <span>{meta.title}</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" role="progressbar" aria-label="Goal wizard progress"
            aria-valuemin={0} aria-valuemax={STEPS.length} aria-valuenow={step + 1}>
            <div className="h-full rounded-full bg-amber-500 transition-all" style={{ width: `${((step + 1) / STEPS.length) * 100}%` }} />
          </div>
        </div>
        <div>
          <h3 id="wz-title" className="text-lg font-semibold text-gray-900 dark:text-gray-100">{meta.title}</h3>
          <p className="mt-1 flex gap-1.5 text-sm text-gray-500 dark:text-gray-400"><Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />{meta.why}</p>
        </div>

        {step === 0 && (
          <div className="grid gap-2 sm:grid-cols-2">
            {cards.map(c => (
              <button key={c.type} type="button" aria-pressed={d.type === c.type} onClick={() => set(fromCard(c, d))}
                className={`flex flex-col items-start gap-0.5 rounded-xl border p-3 text-left ${d.type === c.type
                  ? 'border-brand-500 bg-brand-50 dark:bg-brand-900/30'
                  : 'border-gray-200 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-800'}`}>
                <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{c.name}</span>
                <span className="text-xs text-gray-500 dark:text-gray-400">{c.hint}</span>
              </button>
            ))}
          </div>
        )}

        {step === 1 && (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Whose goal">
              {active.map(p => {
                const on = (d.ownerPersonId ?? active.find(x => x.kind === 'self')?.id) === p.id
                return (
                  <button key={p.id} type="button" role="radio" aria-checked={on} onClick={() => set({ ownerPersonId: p.id })}
                    className={`rounded-full border px-3 py-1.5 text-sm ${on
                      ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200'
                      : 'border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
                    {p.kind === 'self' ? 'Me' : p.displayName}
                  </button>
                )
              })}
            </div>
            <p className="text-xs text-gray-500 dark:text-gray-400">A goal several people pay into is a family goal; those arrive with Family sharing.</p>
          </div>
        )}

        {step === 2 && (
          <div className="flex flex-col gap-3">
            <TextField label="Name" value={d.name} maxLength={80} onChange={v => set({ name: v })} />
            <AmountField label="Target in today’s rupees" value={d.targetAmountToday} onChange={v => set({ targetAmountToday: v })} hint={card?.hint} />
            {d.targetAmountToday && d.targetDate && card?.inflationPct != null && (months ?? 0) > 0 ? (
              <p className="text-sm text-gray-600 dark:text-gray-300">
                At {card.inflationPct}% inflation this is about <b className="tabular-nums">{formatINR(grown(d.targetAmountToday, card.inflationPct, d.targetDate))}</b> in {d.targetDate.slice(0, 4)}. <Pill tone="warn">Kai default</Pill>
              </p>
            ) : null}
          </div>
        )}

        {step === 3 && (
          <div className="flex flex-col gap-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField label="Target date" type="date" value={d.targetDate} onChange={v => set({ targetDate: v })} />
              <SelectField<RiskProfile | ''> label="Risk profile" value={d.riskProfile}
                options={[{ value: '', label: 'Use my risk profile' }, ...(Object.keys(RISK_PROFILES) as RiskProfile[]).map(r => ({ value: r, label: RISK_PROFILES[r] }))]}
                onChange={v => set({ riskProfile: v })} />
              <SelectField<GoalTier> label="Priority" value={d.priorityTier}
                options={(Object.keys(GOAL_TIERS) as GoalTier[]).map(t => ({ value: t, label: GOAL_TIERS[t] }))}
                onChange={v => set({ priorityTier: v })} hint="Important goals count toward the Goal funding check." />
            </div>
            {bucket && (
              <p className="flex flex-wrap items-center gap-2 text-sm text-gray-600 dark:text-gray-300">
                <Pill tone={bucket === 'short' ? 'warn' : 'info'}>{BUCKETS[bucket]}</Pill>
                {bucket === 'short' ? 'No direct equity in this bucket.' : bucket === 'mid' ? 'A balanced mix of equity, debt and gold.' : 'Mostly equity, with debt and gold by your age.'}
              </p>
            )}
          </div>
        )}

        {step === 4 && (editId ? <TreeEditor goalId={editId} /> : (
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {bucket === 'short' ? 'Debt and cash only: no direct shares, since the goal is under 3 years away.'
              : 'Kai sets the mix from your age and risk. Once the goal is saved, open it again or go to Asset tree to change any part; Save stays off until each group adds up to 100%.'}
          </p>
        ))}

        {step === 7 && (editId ? <RulesReview goalId={editId} /> : (
          <p className="text-sm text-gray-600 dark:text-gray-300">
            Kai uses its default rules: the 5/25 rebalance band, and Observe. You can change them under Rules (Advanced) after saving.
          </p>
        ))}

        {step === 5 && (
          <div className="grid gap-3 sm:grid-cols-2">
            <AmountField label="Saved for it already" value={d.currentValue} onChange={v => set({ currentValue: v })} />
            <AmountField label="Going in each month" value={d.monthlyContribution} onChange={v => set({ monthlyContribution: v })} />
            <TextField label="Yearly step-up (%)" type="number" value={d.stepupPct == null ? '' : String(d.stepupPct)}
              onChange={v => set({ stepupPct: v === '' ? null : Math.max(0, Math.min(100, Number(v))) })} hint="How much the monthly amount rises each year." />
            <p className="text-xs text-gray-500 dark:text-gray-400 sm:col-span-2">Kai tracks these figures only. Matching them to real SIPs comes with schedules.</p>
          </div>
        )}

        {meta.later && (
          <div className="flex items-start gap-2 rounded-xl bg-gray-50 p-3 text-sm text-gray-600 dark:bg-gray-800/60 dark:text-gray-300">
            <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span>Available in a later release. Press Next to carry on.</span>
          </div>
        )}

        {problem && <p className="text-sm text-red-600" role="alert">{problem}</p>}
        {save.error && <p className="text-sm text-red-600" role="alert">{errorText(save.error, 'Could not save this goal.')}</p>}
        <div className="flex flex-wrap items-center gap-2 border-t border-gray-100 pt-3 dark:border-gray-800">
          <Button variant="secondary" disabled={step === 0 || (!!editId && step === 1)} onClick={() => { setProblem(null); setStep(step - 1) }}>Back</Button>
          <span className="flex-1" />
          <Button loading={save.isPending} onClick={next}>{step === STEPS.length - 1 ? 'Save goal' : 'Next'}</Button>
        </div>
      </section>
    </div>
  )
}

/** Step 8 for a saved goal: the rules in force for it, and where each comes from. Saving keeps Observe. */
function RulesReview({ goalId }: { goalId: string }) {
  const { data, isLoading } = useQuery({ queryKey: financeKeys.rules(goalId, null), queryFn: () => financeApi.rules(goalId) })
  if (isLoading || !data) return <SkeletonRows rows={4} />
  return (
    <div className="flex flex-col gap-1.5">
      {data.fields.map(f => (
        <div key={f.field} className="flex flex-wrap justify-between gap-2 text-sm">
          <span className="text-gray-600 dark:text-gray-400">{f.label}</span>
          <span className="text-gray-900 dark:text-gray-100">
            {!f.effective || f.effective.state === 'off' ? 'Off' : ruleWords(f.field, f.effective.value as never)}
            <span className="ml-2 text-xs text-gray-500">{!f.effective || f.effective.sourceLevel === 'kai' ? 'Kai default' : `From ${f.effective.sourceName}`}</span>
          </span>
        </div>
      ))}
      <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Saving keeps the goal in Observe. Change rules under Rules with Advanced on.</p>
    </div>
  )
}
