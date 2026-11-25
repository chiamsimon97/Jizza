import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type {
  FinPerson, GoalType, PersonKind, PolicyKind, QuickStartAnswers, QuickStartGoal, QuickStartLoan, QuickStartPerson,
  QuickStartPolicy, QuickStartSaving, QuickStartStep,
} from '@/types/finance'
import { AmountField, CheckField, SelectField, TextField } from '../../components/Fields'
import { COMMON_LOAN_KINDS, LOAN_KINDS, POLICY_KINDS, SAVING_TYPES, isInformal } from '../../labels'
import { KIND_LABELS } from '../People'
import { financeKeys } from '../../queryKeys'

/** What a step hands its parent: the answers to save, or why they can't be saved yet. */
export type Payload<S extends QuickStartStep> = { ok: true; answers: QuickStartAnswers[S] } | { ok: false; message: string }

export interface StepProps<S extends QuickStartStep> {
  saved: QuickStartAnswers[S] | undefined
  people: FinPerson[]
  /** Everything answered so far, for steps that build on earlier ones. */
  all: QuickStartAnswers
  onChange: (p: Payload<S>) => void
}

const rowClass = 'flex flex-col gap-3 rounded-xl border border-gray-200 p-3 dark:border-gray-800'
const removeButton = (label: string, onClick: () => void) => (
  <Button size="sm" variant="ghost" onClick={onClick} aria-label={label} className="self-end"><Trash2 className="h-3.5 w-3.5" /></Button>
)

// ── 1. Whose money ─────────────────────────────────────────────────────────────

export function PeopleStep({ saved, people, onChange }: StepProps<'people'>) {
  const self = people.find(p => p.kind === 'self')
  const [dob, setDob] = useState(saved?.me?.dob ?? self?.dob ?? '')
  const [earning, setEarning] = useState(saved?.me?.isEarning ?? true)
  const [rows, setRows] = useState<QuickStartPerson[]>(() => saved?.people
    ?? people.filter(p => p.kind !== 'self' && p.status === 'active').map(p => ({
      id: p.id, displayName: p.displayName, kind: p.kind, type: p.type, dob: p.dob, isEarning: p.earning, isDependent: p.dependent,
    })))
  const set = (i: number, patch: Partial<QuickStartPerson>) => setRows(r => r.map((x, j) => (j === i ? { ...x, ...patch } : x)))

  useEffect(() => {
    const unnamed = rows.find(r => !r.displayName.trim())
    if (unnamed) return onChange({ ok: false, message: 'Give each person a name.' })
    const minorNoDob = rows.find(r => r.type === 'minor' && !r.dob)
    if (minorNoDob) return onChange({ ok: false, message: `Add ${minorNoDob.displayName}'s date of birth.` })
    onChange({ ok: true, answers: { me: { dob: dob || null, isEarning: earning }, people: rows.map(r => ({ ...r, dob: r.dob || null })) } })
  }, [dob, earning, rows]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      <div className={rowClass}>
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">You</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Your date of birth" type="date" value={dob} onChange={setDob} hint="Used for term cover by age." />
          <div className="flex items-end pb-2"><CheckField label="I earn an income" checked={earning} onChange={setEarning} /></div>
        </div>
      </div>
      {rows.map((r, i) => (
        <div key={r.id ?? `new-${i}`} className={rowClass}>
          <div className="grid gap-3 sm:grid-cols-3">
            <TextField label="Name" value={r.displayName} maxLength={80} onChange={v => set(i, { displayName: v })} />
            <SelectField<PersonKind> label="Relationship" value={r.kind}
              options={(['spouse', 'child', 'parent', 'huf', 'other'] as PersonKind[]).map(k => ({ value: k, label: KIND_LABELS[k] }))}
              onChange={kind => set(i, { kind, type: kind === 'huf' ? 'huf' : kind === 'child' ? 'minor' : 'individual', isDependent: kind === 'child' || kind === 'parent' ? true : r.isDependent })} />
            <TextField label="Date of birth" type="date" value={r.dob ?? ''} onChange={v => set(i, { dob: v })} />
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <CheckField label="Earns an income" checked={!!r.isEarning} onChange={v => set(i, { isEarning: v })} />
            <CheckField label="Depends on the family's income" checked={!!r.isDependent} onChange={v => set(i, { isDependent: v })} />
            {!r.id && <span className="ml-auto">{removeButton(`Remove ${r.displayName || 'this person'}`, () => setRows(rs => rs.filter((_, j) => j !== i)))}</span>}
          </div>
          {r.type === 'individual' && r.kind !== 'spouse' && (
            <p className="text-xs text-gray-500 dark:text-gray-400">An adult: record their consent later under People.</p>
          )}
        </div>
      ))}
      <Button variant="secondary" onClick={() => setRows(r => [...r, { displayName: '', kind: 'child', type: 'minor', isDependent: true }])}>
        <Plus className="h-4 w-4" /> Add a person
      </Button>
    </div>
  )
}

// ── 2. Income ──────────────────────────────────────────────────────────────────

export function IncomeStep({ saved, people, onChange }: StepProps<'income'>) {
  const earners = people.filter(p => p.status === 'active' && (p.kind === 'self' || p.earning))
  const [lines, setLines] = useState<Record<string, { takeHome: number | null; gross: number | null }>>(() => {
    const out: Record<string, { takeHome: number | null; gross: number | null }> = {}
    for (const p of earners) {
      const s = saved?.incomes.find(i => i.personId === p.id)
      out[p.id] = { takeHome: s?.takeHomeMonthly ?? null, gross: s?.grossMonthly ?? null }
    }
    return out
  })
  const [showGross, setShowGross] = useState(() => !!saved?.incomes.some(i => i.grossMonthly))

  useEffect(() => {
    const incomes = Object.entries(lines).filter(([, l]) => l.takeHome != null && l.takeHome > 0)
      .map(([personId, l]) => ({ personId, takeHomeMonthly: l.takeHome!, ...(l.gross ? { grossMonthly: l.gross } : {}) }))
    if (!incomes.length) return onChange({ ok: false, message: 'Enter at least one take-home amount, or skip this question.' })
    onChange({ ok: true, answers: { incomes } })
  }, [lines]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      {earners.map(p => (
        <div key={p.id} className={rowClass}>
          <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{p.kind === 'self' ? 'You' : p.displayName}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <AmountField label="Take-home a month" value={lines[p.id]?.takeHome}
              onChange={v => setLines(l => ({ ...l, [p.id]: { ...l[p.id], takeHome: v } }))} hint="What reaches the bank after tax and PF." />
            {showGross && (
              <AmountField label="Gross a month (optional)" value={lines[p.id]?.gross}
                onChange={v => setLines(l => ({ ...l, [p.id]: { ...l[p.id], gross: v } }))} hint="Before tax; used for the debt check and term cover." />
            )}
          </div>
        </div>
      ))}
      {!showGross && (
        <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => setShowGross(true)}>
          Add gross pay too (optional)
        </button>
      )}
      {earners.length === 1 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">Someone else earns? Mark them as earning in the first question.</p>
      )}
    </div>
  )
}

// ── 3. Spending ────────────────────────────────────────────────────────────────

export function SpendingStep({ saved, all, onChange }: StepProps<'spending'>) {
  const takeHome = (all.income?.incomes ?? []).reduce((s, i) => s + (i.takeHomeMonthly ?? 0), 0)
  const [essential, setEssential] = useState<number | null>(saved?.essentialMonthly ?? null)
  const [other, setOther] = useState<number | null>(saved?.otherMonthly ?? null)
  const [investing, setInvesting] = useState<number | null>(saved?.investingMonthly ?? null)
  const [needsPct, setNeedsPct] = useState(50)

  const typical = (pct: number) => {
    setNeedsPct(pct)
    const save = Math.min(20, 100 - pct)
    setEssential(Math.round((takeHome * pct) / 100 / 100) * 100)
    setInvesting(Math.round((takeHome * save) / 100 / 100) * 100)
    setOther(Math.round((takeHome * (100 - pct - save)) / 100 / 100) * 100)
  }

  useEffect(() => {
    if (essential == null && other == null && investing == null) return onChange({ ok: false, message: 'Enter what you spend, or skip this question.' })
    onChange({ ok: true, answers: { essentialMonthly: essential, otherMonthly: other, investingMonthly: investing } })
  }, [essential, other, investing]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      <AmountField label="Needs a month" value={essential} onChange={setEssential}
        hint="Rent, food, bills, school fees. Leave out EMIs: Kai takes them from your loans." />
      <AmountField label="Everything else a month" value={other} onChange={setOther} hint="Eating out, shopping, travel." />
      <AmountField label="Into savings or SIPs a month" value={investing} onChange={setInvesting} hint="Money that moves into an investment each month." />
      {takeHome > 0 && (
        <div className="flex flex-col gap-1 rounded-xl bg-gray-50 p-3 dark:bg-gray-900">
          <label htmlFor="typical-split" className="text-sm font-medium text-gray-700 dark:text-gray-300">Not sure? Start from a typical split</label>
          <input id="typical-split" type="range" min={30} max={70} step={5} value={needsPct} onChange={e => typical(Number(e.target.value))} />
          <span className="text-xs text-gray-500 dark:text-gray-400">{needsPct}% on needs, the rest on wants and savings. Moving it fills the boxes above; change any of them after.</span>
        </div>
      )}
    </div>
  )
}

// ── 4. Loans ───────────────────────────────────────────────────────────────────

export function LoansStep({ saved, onChange }: StepProps<'loans'>) {
  const [rows, setRows] = useState<QuickStartLoan[]>(saved?.loans ?? [])
  const set = (i: number, patch: Partial<QuickStartLoan>) => setRows(r => r.map((x, j) => (j === i ? { ...x, ...patch } : x)))

  useEffect(() => {
    const bad = rows.find(r => !(r.outstanding > 0))
    if (bad) return onChange({ ok: false, message: `Enter what you still owe on the ${LOAN_KINDS[bad.kind].toLowerCase()}.` })
    onChange({ ok: true, answers: { loans: rows } })
  }, [rows]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      {rows.map((r, i) => (
        <div key={i} className={rowClass}>
          <div className="grid gap-3 sm:grid-cols-2">
            <SelectField label="Kind" value={r.kind} options={COMMON_LOAN_KINDS.map(k => ({ value: k, label: LOAN_KINDS[k] }))}
              onChange={kind => set(i, { kind })} />
            <AmountField label="Still owed" value={r.outstanding} onChange={v => set(i, { outstanding: v ?? 0 })} />
            <AmountField label={r.kind === 'GOLD_LOAN' ? 'Interest paid a month (if any)' : 'Paid a month'} value={r.monthlyPayment} onChange={v => set(i, { monthlyPayment: v })} />
            <TextField label="Interest" value={r.rateInput ?? ''} maxLength={80} onChange={v => set(i, { rateInput: v })}
              placeholder={isInformal(r.kind) ? 'no interest, or ₹2 per ₹100 a month' : '14% a year'}
              hint="As you know it: % a year, % a month, or ₹ per ₹100 a month." />
            {isInformal(r.kind) && <TextField label="Who lent it" value={r.lenderName ?? ''} onChange={v => set(i, { lenderName: v })} hint="Kept private: anyone you share with sees “Family / friend”." />}
            {(isInformal(r.kind) || r.kind === 'GOLD_LOAN') && <TextField label="Due by" type="date" value={r.dueDate ?? ''} onChange={v => set(i, { dueDate: v || null })} />}
            {r.kind === 'GOLD_LOAN' && <>
              <TextField label="Grams pledged" type="number" value={r.grams == null ? '' : String(r.grams)} onChange={v => set(i, { grams: v ? Number(v) : null })} />
              <AmountField label="Gold value now" value={r.goldValue} onChange={v => set(i, { goldValue: v })} />
            </>}
          </div>
          {removeButton(`Remove the ${LOAN_KINDS[r.kind].toLowerCase()}`, () => setRows(rs => rs.filter((_, j) => j !== i)))}
        </div>
      ))}
      <Button variant="secondary" onClick={() => setRows(r => [...r, { kind: 'PERSONAL', outstanding: 0 }])}><Plus className="h-4 w-4" /> Add a loan or card balance</Button>
      {rows.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">No loans? Press Next and Kai notes that you have none.</p>}
    </div>
  )
}

// ── 5. Insurance ───────────────────────────────────────────────────────────────

export function InsuranceStep({ saved, people, onChange }: StepProps<'insurance'>) {
  const active = people.filter(p => p.status === 'active')
  const self = active.find(p => p.kind === 'self')
  const [rows, setRows] = useState<QuickStartPolicy[]>(saved?.policies ?? [])
  const set = (i: number, patch: Partial<QuickStartPolicy>) => setRows(r => r.map((x, j) => (j === i ? { ...x, ...patch } : x)))

  useEffect(() => {
    const bad = rows.find(r => !(r.sumAssured > 0))
    if (bad) return onChange({ ok: false, message: 'Enter the cover amount of each policy.' })
    onChange({ ok: true, answers: { policies: rows } })
  }, [rows]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      {rows.map((r, i) => (
        <div key={i} className={rowClass}>
          <div className="grid gap-3 sm:grid-cols-2">
            <SelectField<PolicyKind> label="Kind" value={r.kind}
              options={(['term', 'health', 'super_topup'] as PolicyKind[]).map(k => ({ value: k, label: POLICY_KINDS[k] }))}
              onChange={kind => set(i, { kind })} />
            <AmountField label="Cover" value={r.sumAssured} onChange={v => set(i, { sumAssured: v ?? 0 })} />
            {r.kind === 'term' ? (
              <SelectField label="Whose life" value={r.personId ?? self?.id ?? ''}
                options={active.filter(p => p.type !== 'minor').map(p => ({ value: p.id, label: p.kind === 'self' ? 'You' : p.displayName }))}
                onChange={personId => set(i, { personId })} />
            ) : (
              <fieldset className="flex flex-col gap-1">
                <legend className="text-sm font-medium text-gray-700 dark:text-gray-300">Who it covers</legend>
                <div className="flex flex-wrap gap-3">
                  {active.map(p => (
                    <CheckField key={p.id} label={p.kind === 'self' ? 'You' : p.displayName}
                      checked={(r.coveredPersonIds ?? []).includes(p.id)}
                      onChange={on => set(i, { coveredPersonIds: on ? [...(r.coveredPersonIds ?? []), p.id] : (r.coveredPersonIds ?? []).filter(x => x !== p.id) })} />
                  ))}
                </div>
              </fieldset>
            )}
            <AmountField label="Premium a year (optional)" value={r.premium} onChange={v => set(i, { premium: v })} />
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            {r.kind !== 'term' && <CheckField label="Only through my employer" checked={!!r.employerProvided} onChange={v => set(i, { employerProvided: v })} />}
            {removeButton(`Remove this ${POLICY_KINDS[r.kind].toLowerCase()} policy`, () => setRows(rs => rs.filter((_, j) => j !== i)))}
          </div>
        </div>
      ))}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => setRows(r => [...r, { kind: 'term', sumAssured: 0, personId: self?.id }])}><Plus className="h-4 w-4" /> Term cover</Button>
        <Button variant="secondary" onClick={() => setRows(r => [...r, { kind: 'health', sumAssured: 0, coveredPersonIds: active.map(p => p.id) }])}><Plus className="h-4 w-4" /> Health cover</Button>
      </div>
      {rows.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">No cover at all? Press Next; Kai counts it as a gap, not as unknown.</p>}
    </div>
  )
}

// ── 6. Savings ─────────────────────────────────────────────────────────────────

export function SavingsStep({ saved, onChange }: StepProps<'savings'>) {
  const [values, setValues] = useState<Record<string, { value: number | null; emergency: boolean }>>(() => {
    const out: Record<string, { value: number | null; emergency: boolean }> = {}
    for (const t of SAVING_TYPES) {
      const s = saved?.totals.find(x => x.kind === t.kind && (x.name ?? '') === t.name) ?? (t.id === 'savings' ? saved?.totals.find(x => x.kind === 'savings_balance') : undefined)
      out[t.id] = { value: s?.value ?? null, emergency: s?.isEmergency ?? t.emergency }
    }
    return out
  })

  useEffect(() => {
    const totals: QuickStartSaving[] = SAVING_TYPES.filter(t => (values[t.id]?.value ?? 0) > 0).map(t => ({
      kind: t.kind, value: values[t.id].value!, isEmergency: values[t.id].emergency, name: t.name,
      ...(t.kind === 'other' ? { assetClass: t.assetClass } : {}),
    }))
    onChange({ ok: true, answers: { totals } })
  }, [values]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-3 sm:grid-cols-2">
        {SAVING_TYPES.map(t => (
          <div key={t.id} className="flex flex-col gap-1">
            <AmountField label={t.label} value={values[t.id].value} onChange={v => setValues(x => ({ ...x, [t.id]: { ...x[t.id], value: v } }))} />
            {(t.id === 'savings' || t.id === 'fd' || t.id === 'mf') && (
              <CheckField label="Counts as emergency money" checked={values[t.id].emergency}
                onChange={v => setValues(x => ({ ...x, [t.id]: { ...x[t.id], emergency: v } }))} />
            )}
          </div>
        ))}
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">Rough totals are fine; Kai marks them as estimates. Importing statements comes with Connect.</p>
    </div>
  )
}

// ── 7. Goals ───────────────────────────────────────────────────────────────────

/** Quick start offers up to three goals; the emergency fund is a check of its own, so it is not here. */
export const QUICK_GOAL_TYPES: GoalType[] = ['retirement', 'education', 'home', 'car', 'travel', 'wedding', 'custom']
export const MAX_QUICK_GOALS = 3

export function GoalsStep({ saved, onChange }: StepProps<'goals'>) {
  const { data: library } = useQuery({ queryKey: financeKeys.goalLibrary, queryFn: financeApi.goalLibrary })
  const [picked, setPicked] = useState<QuickStartGoal[]>(saved?.goals ?? [])
  const card = (t: GoalType) => library?.find(c => c.type === t)
  const toggle = (t: GoalType) => setPicked(p => p.some(g => g.type === t)
    ? p.filter(g => g.type !== t)
    : p.length >= MAX_QUICK_GOALS ? p : [...p, { type: t, targetAmountToday: card(t)?.targetAmountToday ?? null }])
  const set = (t: GoalType, patch: Partial<QuickStartGoal>) => setPicked(p => p.map(g => (g.type === t ? { ...g, ...patch } : g)))

  useEffect(() => {
    const missing = picked.find(g => !(g.targetAmountToday && g.targetAmountToday > 0) && !card(g.type)?.targetAmountToday)
    if (missing) return onChange({ ok: false, message: `Enter roughly what ${card(missing.type)?.name.toLowerCase() ?? 'this goal'} costs today.` })
    onChange({ ok: true, answers: { goals: picked.map(g => ({ type: g.type, ...(g.targetAmountToday ? { targetAmountToday: g.targetAmountToday } : {}) })) } })
  }, [picked, library]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2" role="group" aria-label="Goals">
        {QUICK_GOAL_TYPES.map(t => {
          const on = picked.some(g => g.type === t)
          return (
            <button key={t} type="button" aria-pressed={on} onClick={() => toggle(t)}
              disabled={!on && picked.length >= MAX_QUICK_GOALS}
              className={`rounded-full border px-3 py-1.5 text-sm disabled:opacity-40 ${on
                ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200'
                : 'border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
              {card(t)?.name ?? t}
            </button>
          )
        })}
      </div>
      {picked.map(g => {
        const c = card(g.type)
        return (
          <div key={g.type} className={rowClass}>
            <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{c?.name ?? g.type}</p>
            <AmountField label="What it costs in today’s rupees" value={g.targetAmountToday}
              onChange={v => set(g.type, { targetAmountToday: v })} hint={c?.hint} />
          </div>
        )
      })}
      <p className="text-xs text-gray-500 dark:text-gray-400">
        Pick up to {MAX_QUICK_GOALS}. Kai fills in the date and grows the amount by inflation; change any of it later under Goals.
      </p>
    </div>
  )
}

// ── 8. Risk ────────────────────────────────────────────────────────────────────

export function RiskStep({ saved, people, onChange }: StepProps<'risk'>) {
  const self = people.find(p => p.kind === 'self')
  const { data } = useQuery({ queryKey: financeKeys.risk(self?.id ?? ''), queryFn: () => financeApi.risk(self!.id), enabled: !!self })
  const questions = data?.questions ?? []
  const [answers, setAnswers] = useState<(number | null)[]>(() => saved?.answers ?? [])

  useEffect(() => {
    if (!questions.length) return onChange({ ok: false, message: 'The questions are still loading.' })
    const open = questions.findIndex((_, i) => !answers[i])
    if (open >= 0) return onChange({ ok: false, message: `Answer question ${open + 1} too, or skip this step.` })
    onChange({ ok: true, answers: { answers: answers.slice(0, questions.length) as number[] } })
  }, [answers, questions.length]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="flex flex-col gap-4">
      {questions.map((q, i) => (
        <fieldset key={q.key} className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-gray-900 dark:text-gray-100">{i + 1}. {q.text}</legend>
          <div className="grid gap-2 sm:grid-cols-3">
            {q.answers.map((a, j) => {
              const on = answers[i] === j + 1
              return (
                <button key={a} type="button" aria-pressed={on}
                  onClick={() => setAnswers(x => { const n = [...x]; n[i] = j + 1; return n })}
                  className={`rounded-lg border px-3 py-2 text-left text-sm ${on
                    ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200'
                    : 'border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800'}`}>
                  {a}
                </button>
              )
            })}
          </div>
        </fieldset>
      ))}
      {data?.note && <p className="text-xs text-gray-500 dark:text-gray-400">{data.note}</p>}
    </div>
  )
}
