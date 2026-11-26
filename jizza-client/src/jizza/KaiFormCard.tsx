import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, ClipboardList } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { KaiForm, KaiFormKind } from '@/types'
import type { FinAccountInput, FinAssetInput, FinLoanGivenInput, FinLoanInput, FinPolicyInput, GoalInput } from '@/types/finance'
import { AmountField, SelectField, TextField } from '@/portfolio/components/Fields'
import { ASSET_KINDS, LOAN_KINDS, POLICY_KINDS } from '@/portfolio/labels'
import { errorText } from '@/portfolio/data'
import { financeKeys } from '@/portfolio/queryKeys'

type Values = Record<string, unknown>

/** One input on a Kai form. Names are the Portfolio forms' own field names. */
interface FieldDef {
  name: string
  label: string
  kind: 'text' | 'money' | 'date' | 'select' | 'number'
  options?: Record<string, string>
  placeholder?: string
  hint?: string
  required?: boolean
}

interface FormDef {
  title: string
  saved: string
  fields: FieldDef[]
  save: (body: Values) => Promise<unknown>
}

const GOAL_TYPES: Record<string, string> = {
  emergency: 'Emergency fund', retirement: 'Retirement', education: 'Education', home: 'Home', car: 'Car',
  travel: 'Travel', wedding: 'Wedding', wealth: 'Wealth', custom: 'Something else',
}
const PRIORITIES: Record<string, string> = { survival: 'Must have', important: 'Important', aspirational: 'Nice to have' }
const FREQUENCIES: Record<string, string> = { monthly: 'Monthly', quarterly: 'Quarterly', yearly: 'Yearly', single: 'One time' }
const ACCOUNT_KINDS: Record<string, string> = {
  savings: 'Savings', current: 'Current', credit_card: 'Credit card', demat: 'Demat', mf_folio: 'Mutual fund folio',
  retirement: 'Retirement', deposit: 'Deposit', wallet: 'Wallet', cash: 'Cash', us_broker: 'US broker', other: 'Other',
}

/** What each form asks, in the order a person thinks of it, and where it saves. */
export const FORMS: Record<KaiFormKind, FormDef> = {
  goal: {
    title: 'Add a goal', saved: 'Goal saved',
    fields: [
      { name: 'type', label: 'Kind of goal', kind: 'select', options: GOAL_TYPES, required: true },
      { name: 'name', label: 'Name', kind: 'text', placeholder: 'College fund', required: true },
      { name: 'targetAmountToday', label: 'Costs today', kind: 'money', hint: 'In today’s rupees; Kai adds inflation.', required: true },
      { name: 'targetDate', label: 'Needed by', kind: 'date', required: true },
      { name: 'currentValue', label: 'Saved so far', kind: 'money' },
      { name: 'monthlyContribution', label: 'Putting in each month', kind: 'money' },
      { name: 'priorityTier', label: 'How important', kind: 'select', options: PRIORITIES },
    ],
    save: body => financeApi.addGoal(body as GoalInput),
  },
  loan: {
    title: 'Add a loan', saved: 'Loan saved',
    fields: [
      { name: 'kind', label: 'Kind', kind: 'select', options: LOAN_KINDS, required: true },
      { name: 'outstanding', label: 'Still owed', kind: 'money', required: true },
      { name: 'emiOrInterest', label: 'Paid a month', kind: 'money' },
      { name: 'rateInput', label: 'Interest', kind: 'text', placeholder: '9.5% a year, ₹2 per ₹100 a month, no interest', hint: 'As you know it; Kai works out the yearly rate.' },
      { name: 'lenderName', label: 'Lender', kind: 'text' },
      { name: 'startDate', label: 'Taken on', kind: 'date' },
      { name: 'maturityOrPromisedDate', label: 'Ends or due on', kind: 'date' },
    ],
    save: body => financeApi.addLoan(body as FinLoanInput),
  },
  money_lent: {
    title: 'Add money you lent', saved: 'Saved',
    fields: [
      { name: 'borrowerName', label: 'Who', kind: 'text', required: true },
      { name: 'amount', label: 'Amount', kind: 'money', required: true },
      { name: 'givenOn', label: 'Given on', kind: 'date' },
      { name: 'expectedBackOn', label: 'Expected back by', kind: 'date' },
    ],
    save: body => financeApi.addLoanGiven(body as FinLoanGivenInput),
  },
  policy: {
    title: 'Add a policy', saved: 'Policy saved',
    fields: [
      { name: 'kind', label: 'Kind', kind: 'select', options: POLICY_KINDS, required: true },
      { name: 'providerName', label: 'Insurer', kind: 'text' },
      { name: 'sumAssured', label: 'Cover', kind: 'money', required: true },
      { name: 'premium', label: 'Premium', kind: 'money' },
      { name: 'premiumFrequency', label: 'Paid', kind: 'select', options: FREQUENCIES },
      { name: 'renewalDate', label: 'Renews on', kind: 'date' },
    ],
    save: body => financeApi.addPolicy(body as FinPolicyInput),
  },
  asset: {
    title: 'Add a saving or asset', saved: 'Saved',
    fields: [
      { name: 'kind', label: 'Kind', kind: 'select', options: ASSET_KINDS, required: true },
      { name: 'name', label: 'Name', kind: 'text', placeholder: 'SBI FD' },
      { name: 'value', label: 'Worth now', kind: 'money', required: true },
      { name: 'rate', label: 'Interest (% a year)', kind: 'number' },
      { name: 'maturityDate', label: 'Matures on', kind: 'date' },
    ],
    save: body => financeApi.addAsset(body as FinAssetInput),
  },
  account: {
    title: 'Add an account', saved: 'Account saved',
    fields: [
      { name: 'kind', label: 'Kind', kind: 'select', options: ACCOUNT_KINDS, required: true },
      { name: 'provider', label: 'Bank or broker', kind: 'text' },
      { name: 'nickname', label: 'Nickname', kind: 'text' },
    ],
    save: body => financeApi.addAccount(body as FinAccountInput),
  },
}

/** The values as the form starts: what Kai filled in, with the first option for an empty choice. */
export function startValues(card: KaiForm): Values {
  const def = FORMS[card.form]
  const out: Values = {}
  for (const f of def.fields) {
    const v = card.values?.[f.name]
    if (v !== undefined && v !== null && (f.kind !== 'select' || (typeof v === 'string' && f.options && v in f.options))) out[f.name] = v
    else if (f.kind === 'select' && f.required && f.options) out[f.name] = Object.keys(f.options)[0]
  }
  return out
}

function blank(v: unknown): boolean {
  return v === undefined || v === null || (typeof v === 'string' && v.trim() === '')
}

/** What still has to be filled before Save. */
export function missing(card: KaiForm, values: Values): string[] {
  return FORMS[card.form].fields.filter(f => f.required && blank(values[f.name])).map(f => f.label)
}

/** The body to save: empty fields left out, text trimmed, numbers as numbers. */
export function bodyOf(card: KaiForm, values: Values): Values {
  const out: Values = {}
  for (const f of FORMS[card.form].fields) {
    const v = values[f.name]
    if (blank(v)) continue
    if (f.kind === 'number') {
      const n = Number(v)
      if (Number.isFinite(n)) out[f.name] = n
    } else out[f.name] = typeof v === 'string' ? v.trim() : v
  }
  return out
}

const read = (key: string): boolean => {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

/**
 * A short Portfolio form Kai put in the chat, filled in with what the person told it: they check
 * it, change anything, and save it here. Saving goes through the Portfolio's own endpoint with the
 * person's own session, so it is their action; Kai never saves it. Once saved the card says so,
 * also after the chat is opened again.
 */
export function KaiFormCard({ card }: { card: KaiForm }) {
  return FORMS[card.form] ? <FormCard card={card} /> : null
}

function FormCard({ card }: { card: KaiForm }) {
  const def = FORMS[card.form]
  const qc = useQueryClient()
  const key = `jizza.kaiform.${card.id}`
  const [values, setValues] = useState<Values>(() => startValues(card))
  const [done, setDone] = useState(() => read(key))
  const save = useMutation({
    mutationFn: () => def.save(bodyOf(card, values)),
    onSuccess: () => {
      try { localStorage.setItem(key, '1') } catch { /* the card just won't remember after a reload */ }
      setDone(true)
      qc.invalidateQueries({ queryKey: financeKeys.all })
    },
  })
  const set = (name: string, v: unknown) => setValues(prev => ({ ...prev, [name]: v }))
  const needed = missing(card, values)

  return (
    <section aria-label={card.title ?? def.title}
      className="mt-2 flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-3 dark:border-gray-700 dark:bg-gray-900">
      <header className="flex items-center gap-2">
        <ClipboardList className="h-4 w-4 text-brand-600 dark:text-brand-300" aria-hidden />
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{card.title ?? def.title}</h3>
      </header>
      {card.intro && <p className="text-sm text-gray-600 dark:text-gray-300">{card.intro}</p>}
      {done ? (
        <p role="status" className="flex items-center gap-1.5 text-sm font-medium text-green-700 dark:text-green-400">
          <Check className="h-4 w-4" aria-hidden />{def.saved}. You'll find it in your Portfolio.
        </p>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            {def.fields.map(f => {
              const label = f.required ? f.label : `${f.label} (optional)`
              const v = values[f.name]
              if (f.kind === 'money') {
                return <AmountField key={f.name} label={label} hint={f.hint} value={typeof v === 'number' ? v : v == null ? null : Number(v)} onChange={x => set(f.name, x)} />
              }
              if (f.kind === 'select') {
                const opts = Object.entries(f.options ?? {}).map(([value, l]) => ({ value, label: l }))
                const all = f.required ? opts : [{ value: '', label: 'Not set' }, ...opts]
                return <SelectField key={f.name} label={label} value={(v as string) ?? ''} onChange={x => set(f.name, x || null)} options={all} />
              }
              return (
                <TextField key={f.name} label={label} hint={f.hint} placeholder={f.placeholder}
                  type={f.kind === 'date' ? 'date' : f.kind === 'number' ? 'number' : 'text'}
                  value={v == null ? '' : String(v)} onChange={x => set(f.name, x)} />
              )
            })}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" loading={save.isPending} disabled={needed.length > 0} onClick={() => save.mutate()}>Save</Button>
            {needed.length > 0 && <span className="text-xs text-gray-500 dark:text-gray-400">Still needed: {needed.join(', ')}</span>}
          </div>
          {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save. Check the values and try again.')}</p>}
        </>
      )}
    </section>
  )
}
