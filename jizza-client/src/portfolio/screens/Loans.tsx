import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Banknote, Car, CreditCard, GraduationCap, Home as HomeIcon, Landmark, Lock, Pencil, Plus, Trash2, Users } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SlideOver } from '@/components/ui/SlideOver'
import { formatINR } from '@/lib/money'
import type { FinLoan, FinLoanGiven, FinLoanGivenInput, FinLoanInput, LoanKind, LoanPlanView, PlanOrderItem } from '@/types/finance'
import { AsOf, daysUntil, formatDay, formatMonth } from '../components/AsOf'
import { NeedsVerification } from '../components/Badges'
import { DataTable } from '../components/DataTable'
import { AmountField, CheckField, SelectField, TextField } from '../components/Fields'
import { Money } from '../components/Money'
import { SkeletonRows } from '../components/SkeletonRows'
import { StatusBadge } from '../components/StatusBadge'
import { LOAN_KINDS, isInformal } from '../labels'
import { errorText, useLoanPlan, useLoans, useLoansGiven, useSample } from '../data'
import { financeKeys } from '../queryKeys'

const ICONS: Partial<Record<LoanKind, typeof Landmark>> = {
  HOME: HomeIcon, VEHICLE: Car, EDUCATION: GraduationCap, CREDIT_CARD: CreditCard, GOLD_LOAN: Lock,
  FAMILY_FRIEND: Users, MONEYLENDER: Banknote,
}

export const STEP_WORDS: Record<PlanOrderItem['step'], string> = {
  pay_in_full: 'Pay in full', date_bound: 'Set aside by the due date', prepay: 'Prepay',
  split: 'Split between prepaying and investing', keep_emi: 'Keep the EMI, invest the rest',
}

/** Runs of equal amounts: [{amount, from, to}] over the plan's months. */
export function runs(months: { month: string; amount: number }[]): { amount: number; from: string; to: string }[] {
  const out: { amount: number; from: string; to: string }[] = []
  for (const m of months) {
    const last = out[out.length - 1]
    const amount = Math.round(m.amount)
    if (last && last.amount === amount) last.to = m.month
    else out.push({ amount, from: m.month, to: m.month })
  }
  return out
}

/** "₹10,000 in Dec 2026; ₹30,000 Jan 2027 to Mar 2027". */
export function describeRuns(r: { amount: number; from: string; to: string }[], openEnded = false): string {
  return r.map((x, i) => {
    const when = x.from === x.to && !(openEnded && i === r.length - 1)
      ? `in ${formatMonth(x.from)}`
      : openEnded && i === r.length - 1 ? `from ${formatMonth(x.from)}` : `${formatMonth(x.from)} to ${formatMonth(x.to)}`
    return `${formatINR(x.amount)} ${when}`
  }).join('; ')
}

/** Each month's extra payment to one loan, as a phrase. */
export function paymentsFor(plan: LoanPlanView['plan'], loanId: string): string {
  const months = plan.monthly.filter(m => (m.payments[loanId] ?? 0) > 0).map(m => ({ month: m.month, amount: m.payments[loanId] }))
  return months.length ? describeRuns(runs(months)) : 'Only the regular payment'
}

/**
 * Loans and borrowings (7.20): every loan with its real rate and the user's own wording, gold
 * loans with their pledge and LTV, the repayment plan (avalanche or snowball, extra a month), and
 * money lent to others. Kai never moves money; the plan is what to pay, for the user to do.
 */
export function Loans() {
  const sample = useSample()
  const { data: loans, isLoading, error, refetch } = useLoans()
  const [editing, setEditing] = useState<FinLoan | 'new' | null>(null)
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !loans) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
        <p className="text-sm text-red-700 dark:text-red-300">Could not load your loans.</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>Try again</Button>
      </div>
    )
  }
  const active = loans.filter(l => l.status === 'active')

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Loans and borrowings</h2>
        <span className="flex-1" />
        {!sample && <Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" /> Add loan</Button>}
      </div>
      {active.length === 0 ? (
        <EmptyState icon={Landmark} title="No loans recorded"
          description="Add a loan, a card balance or money borrowed from family to see your debt-free date."
          action={sample ? undefined : { label: 'Add a loan', onClick: () => setEditing('new') }} />
      ) : (
        <Plan loans={active} onEdit={setEditing} />
      )}
      <MoneyLent />
      {editing && <LoanForm loan={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function Plan({ loans, onEdit }: { loans: FinLoan[]; onEdit: (l: FinLoan) => void }) {
  const sample = useSample()
  const [method, setMethod] = useState<'avalanche' | 'snowball'>('avalanche')
  const [extraDraft, setExtraDraft] = useState(0)
  const [extra, setExtra] = useState(0)
  useEffect(() => {
    const t = setTimeout(() => setExtra(extraDraft), 350)
    return () => clearTimeout(t)
  }, [extraDraft])
  const { data: view, isFetching } = useLoanPlan(method, extra)
  const plan = view?.plan
  const outcome = (id: string) => plan?.loans.find(l => l.loanId === id)

  const investable = plan ? describeRuns(runs(plan.monthly.map(m => ({ month: m.month, amount: m.investable }))), true) : ''

  return (
    <>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {loans.map(l => <LoanCard key={l.id} loan={l} debtFreeBy={outcome(l.id)?.debtFreeBy ?? null} setAside={outcome(l.id)?.monthlySetAside ?? null}
          onEdit={sample ? undefined : () => onEdit(l)} />)}
      </div>

      <section className="flex flex-col gap-3 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900" aria-busy={isFetching}>
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
            Repayment plan{view ? <> · <Money amount={view.monthlySurplus + extra} /> a month to work with</> : ''}
          </h3>
          {view && <AsOf date={view.asOf} />}
          {view?.figuresNeedVerification && <NeedsVerification />}
        </div>
        <div className="flex flex-wrap gap-2" role="group" aria-label="Order">
          {(['avalanche', 'snowball'] as const).map(m => (
            <button key={m} type="button" aria-pressed={method === m} onClick={() => setMethod(m)}
              className={`rounded-lg px-3 py-1.5 text-sm ${method === m ? 'bg-brand-600 text-white' : 'border border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
              {m === 'avalanche' ? 'Highest rate first' : 'Smallest balance first'}
            </button>
          ))}
        </div>
        {!plan ? <SkeletonRows rows={3} /> : (
          <>
            <DataTable<PlanOrderItem> rows={plan.order} rowKey={r => r.loanId} caption="Order to repay"
              columns={[
                { header: 'Order', cell: r => plan.order.indexOf(r) + 1 },
                { header: 'Loan', cell: r => r.label },
                { header: 'Step', cell: r => STEP_WORDS[r.step] },
                { header: 'Extra each month', cell: r => paymentsFor(plan, r.loanId) },
              ]} />
            <div className="flex flex-col gap-1">
              <label htmlFor="plan-extra" className="text-sm text-gray-700 dark:text-gray-300">Extra each month: {formatINR(extraDraft)}</label>
              <input id="plan-extra" type="range" min={0} max={100000} step={1000} value={extraDraft} onChange={e => setExtraDraft(Number(e.target.value))} />
            </div>
            <dl className="grid gap-1 text-sm sm:grid-cols-2">
              <dt className="text-gray-500 dark:text-gray-400">Debt-free (except loans that keep their EMI)</dt>
              <dd className="text-right text-gray-900 dark:text-gray-100">{formatMonth(plan.debtFreeBy)}</dd>
              <dt className="text-gray-500 dark:text-gray-400">Free to invest</dt>
              <dd className="text-right text-gray-900 dark:text-gray-100">{investable || '—'}</dd>
              {plan.interestSaved > 0 && <>
                <dt className="text-gray-500 dark:text-gray-400">Card interest avoided by paying it first</dt>
                <dd className="text-right text-gray-900 dark:text-gray-100">about <Money amount={plan.interestSaved} /></dd>
              </>}
            </dl>
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {plan.dateBoundFirst
                ? 'Loans with a fixed due date come first: paying the card first would miss one of them.'
                : 'Cards and moneylenders are cleared first because every dated repayment can still be met.'}
            </p>
          </>
        )}
      </section>
    </>
  )
}

function LoanCard({ loan: l, debtFreeBy, setAside, onEdit }: { loan: FinLoan; debtFreeBy: string | null; setAside: number | null; onEdit?: () => void }) {
  const Icon = ICONS[l.kind] ?? Landmark
  const rate = l.effectiveAnnualRate
  const ltv = l.ltv
  const due = l.maturityOrPromisedDate
  return (
    <article className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-all duration-200 motion-safe:hover:-translate-y-0.5 hover:shadow-md dark:border-gray-800 dark:bg-gray-900" data-testid="loan-card">
      <div className="flex flex-wrap items-center gap-2">
        <Icon className="h-4 w-4 text-gray-500" aria-hidden />
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{l.label}</h3>
        {l.isPrivate && <span className="inline-flex items-center gap-1 rounded-full bg-gray-900 px-2 py-0.5 text-xs text-white dark:bg-gray-100 dark:text-gray-900" title={`Anyone you share with sees "${l.sharedLabel}"`}><Lock className="h-3 w-3" aria-hidden />Private</span>}
        {rate != null && (
          <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700 dark:bg-gray-800 dark:text-gray-300">
            {rate === 0 ? 'No interest' : `${rate}% a year`}
          </span>
        )}
        <span className="flex-1" />
        {onEdit && <Button size="sm" variant="ghost" onClick={onEdit} aria-label={`Edit ${l.label}`}><Pencil className="h-3.5 w-3.5" /></Button>}
      </div>
      <p className="text-xl font-semibold text-gray-900 dark:text-gray-100"><Money amount={l.outstanding} /></p>
      {l.rateInput && rate != null && l.rateInput.trim() !== `${rate}%` && <p className="text-xs text-gray-500 dark:text-gray-400">You said “{l.rateInput}”.</p>}
      {l.emiOrInterest != null && l.emiOrInterest > 0 && <Line label={l.repaymentMode === 'emi' ? 'EMI' : 'Paid a month'}><Money amount={l.emiOrInterest} /></Line>}
      {ltv && (
        <div className="flex flex-col gap-1.5">
          <Line label="Pledged">{l.pledges.map(p => `${p.grams ?? '—'} g${p.purityKarat ? ` of ${p.purityKarat}K` : ''}`).join(', ')} · worth <Money amount={ltv.detail.valuation} /></Line>
          <Line label="Loan to value">{ltv.pct}% of {ltv.capPct}% cap</Line>
          <div className="h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" role="img" aria-label={`Loan to value ${ltv.pct}% against a cap of ${ltv.capPct}%`}>
            <div className={`h-full rounded-full ${ltv.warn ? 'bg-red-500' : 'bg-green-600'}`} style={{ width: `${Math.min(100, (ltv.pct / ltv.capPct) * 100)}%` }} />
          </div>
          {ltv.warn && <StatusBadge status="red" label="Close to the cap" />}
          {ltv.needsVerification && <span><NeedsVerification /></span>}
          <Line label="Due at maturity"><Money amount={ltv.detail.maturityAmount} /> (interest <Money amount={ltv.detail.maturityInterest} />)</Line>
          <Line label="Interest so far"><Money amount={ltv.detail.accruedInterest} /></Line>
        </div>
      )}
      {due && <Line label={isInformal(l.kind) ? 'Promised back' : 'Due'}>{formatDay(due)}{daysUntil(due) >= 0 ? ` · ${daysUntil(due)} days` : ' · overdue'}</Line>}
      {setAside != null && setAside > 0 && <Line label="Set aside">up to <Money amount={setAside} /> a month</Line>}
      {debtFreeBy && <Line label="Debt-free">{formatMonth(debtFreeBy)}</Line>}
      {l.isPrivate && <p className="text-xs text-gray-500 dark:text-gray-400">Anyone you share with sees “{l.sharedLabel}”, not the name.</p>}
    </article>
  )
}

function Line({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-2 text-sm">
      <span className="text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}

/** Money lent to others: what is due back and when; overdue ones are flagged and not counted on. */
function MoneyLent() {
  const qc = useQueryClient()
  const sample = useSample()
  const { data } = useLoansGiven()
  const [adding, setAdding] = useState<FinLoanGiven | 'new' | null>(null)
  const repaid = useMutation({
    mutationFn: (id: string) => financeApi.updateLoanGiven(id, { status: 'repaid' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })
  const open = (data ?? []).filter(g => g.status !== 'repaid')
  return (
    <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Money lent to others</h3>
        {!sample && <Button size="sm" variant="ghost" onClick={() => setAdding('new')}><Plus className="h-4 w-4" /> Add</Button>}
      </div>
      {open.length === 0 ? <p className="text-sm text-gray-500 dark:text-gray-400">Nothing lent out.</p> : (
        <ul className="flex flex-col gap-2">
          {open.map(g => {
            const overdue = g.status === 'overdue' || (g.expectedBackOn != null && daysUntil(g.expectedBackOn) < 0)
            return (
              <li key={g.id} className="flex flex-wrap items-center gap-2 text-sm">
                <span className="text-gray-900 dark:text-gray-100">{g.borrowerName} · <Money amount={g.amount} /></span>
                {overdue && <StatusBadge status="red" label="Overdue" />}
                <span className="w-full text-xs text-gray-500 dark:text-gray-400">
                  {g.expectedBackOn ? `Expected back ${formatDay(g.expectedBackOn)}.` : 'No date agreed.'}{overdue ? ' Not counted on for goals while overdue.' : ''}
                </span>
                {!sample && <>
                  <Button size="sm" variant="ghost" loading={repaid.isPending && repaid.variables === g.id} onClick={() => repaid.mutate(g.id)}>Paid back</Button>
                  <Button size="sm" variant="ghost" onClick={() => setAdding(g)} aria-label={`Edit money lent to ${g.borrowerName}`}><Pencil className="h-3.5 w-3.5" /></Button>
                </>}
              </li>
            )
          })}
        </ul>
      )}
      {adding && <LentForm given={adding === 'new' ? null : adding} onClose={() => setAdding(null)} />}
    </section>
  )
}

function LentForm({ given, onClose }: { given: FinLoanGiven | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [form, setForm] = useState<FinLoanGivenInput>({
    borrowerName: given?.borrowerName ?? '', amount: given?.amount, expectedBackOn: given?.expectedBackOn ?? null, isPrivate: given?.isPrivate ?? true,
  })
  const done = () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() }
  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, expectedBackOn: form.expectedBackOn || null }
      return given ? financeApi.updateLoanGiven(given.id, body) : financeApi.addLoanGiven(body)
    },
    onSuccess: done,
  })
  const remove = useMutation({ mutationFn: () => financeApi.deleteLoanGiven(given!.id), onSuccess: done })
  return (
    <SlideOver open title={given ? 'Money lent' : 'Add money you lent'} onClose={onClose}
      footer={<>
        {given && <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}><Trash2 className="h-4 w-4" /> Remove</Button>}
        <span className="flex-1" />
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} disabled={!form.borrowerName?.trim() || !(form.amount && form.amount > 0)} onClick={() => save.mutate()}>Save</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <TextField label="Who" value={form.borrowerName} onChange={borrowerName => setForm(f => ({ ...f, borrowerName }))} />
        <AmountField label="Amount" value={form.amount} onChange={amount => setForm(f => ({ ...f, amount: amount ?? undefined }))} />
        <TextField label="Expected back by" type="date" value={form.expectedBackOn ?? ''} onChange={expectedBackOn => setForm(f => ({ ...f, expectedBackOn }))} />
        <CheckField label="Keep the name private when sharing" checked={!!form.isPrivate} onChange={isPrivate => setForm(f => ({ ...f, isPrivate }))} />
        {(save.error || remove.error) && <p className="text-sm text-red-600">{errorText(save.error ?? remove.error, 'Could not save.')}</p>}
      </div>
    </SlideOver>
  )
}

/** Add or edit a loan: kind, amount owed, payment and rate first; dates and collateral are optional. */
function LoanForm({ loan, onClose }: { loan: FinLoan | null; onClose: () => void }) {
  const qc = useQueryClient()
  const gold = loan?.pledges.find(p => p.assetKind === 'gold')
  const [more, setMore] = useState(!!loan && (!!loan.maturityOrPromisedDate || !!loan.lenderName || !!gold))
  const [form, setForm] = useState<FinLoanInput & { grams?: number | null; purityKarat?: number | null; goldValue?: number | null }>({
    kind: loan?.kind ?? 'PERSONAL',
    outstanding: loan?.outstanding,
    emiOrInterest: loan?.emiOrInterest ?? null,
    rateInput: loan?.rateInput ?? '',
    lenderName: loan?.lenderName ?? '',
    isPrivate: loan?.isPrivate,
    startDate: loan?.startDate ?? null,
    maturityOrPromisedDate: loan?.maturityOrPromisedDate ?? null,
    principalTaken: loan?.principalTaken ?? null,
    grams: gold?.grams ?? null,
    purityKarat: gold?.purityKarat ?? 22,
    goldValue: gold?.valuation ?? null,
  })
  const set = (patch: Partial<typeof form>) => setForm(f => ({ ...f, ...patch }))
  const done = () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() }
  const save = useMutation({
    mutationFn: () => {
      const { grams, purityKarat, goldValue, ...rest } = form
      const body: FinLoanInput = {
        ...rest,
        rateInput: rest.rateInput?.trim() || null,
        lenderName: rest.lenderName?.trim() || null,
        startDate: rest.startDate || null,
        maturityOrPromisedDate: rest.maturityOrPromisedDate || null,
      }
      if (form.kind === 'GOLD_LOAN' && (grams || goldValue)) body.pledges = [{ assetKind: 'gold', grams, purityKarat, valuation: goldValue }]
      return loan ? financeApi.updateLoan(loan.id, body) : financeApi.addLoan(body)
    },
    onSuccess: done,
  })
  const remove = useMutation({ mutationFn: () => financeApi.deleteLoan(loan!.id), onSuccess: done })

  return (
    <SlideOver open title={loan ? `Edit ${loan.label}` : 'Add a loan'} description="Enter the rate the way you know it; Kai works out the real yearly rate." onClose={onClose}
      footer={<>
        {loan && <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}><Trash2 className="h-4 w-4" /> Remove</Button>}
        <span className="flex-1" />
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} disabled={!(form.outstanding && form.outstanding > 0)} onClick={() => save.mutate()}>Save</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <SelectField<LoanKind> label="Kind" value={form.kind!} onChange={kind => set({ kind })}
          options={(Object.keys(LOAN_KINDS) as LoanKind[]).map(k => ({ value: k, label: LOAN_KINDS[k] }))} />
        <AmountField label="Still owed" value={form.outstanding} onChange={v => set({ outstanding: v ?? undefined })} />
        <AmountField label="Paid a month" value={form.emiOrInterest} onChange={emiOrInterest => set({ emiOrInterest })} />
        <TextField label="Interest" value={form.rateInput} maxLength={80} onChange={rateInput => set({ rateInput })}
          placeholder="14% a year, 3.5% a month, ₹2 per ₹100 a month, no interest" />
        {!more ? (
          <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => setMore(true)}>
            Add lender, dates and collateral (optional)
          </button>
        ) : (
          <>
            <TextField label={isInformal(form.kind!) ? 'Who lent it' : 'Lender'} value={form.lenderName} onChange={lenderName => set({ lenderName })} />
            {isInformal(form.kind!) && <p className="-mt-2 text-xs text-gray-500 dark:text-gray-400">Kept private: anyone you share with sees “Family / friend”.</p>}
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField label="Taken on" type="date" value={form.startDate ?? ''} onChange={startDate => set({ startDate })} />
              <TextField label={isInformal(form.kind!) ? 'Promised back by' : 'Ends or due on'} type="date" value={form.maturityOrPromisedDate ?? ''} onChange={v => set({ maturityOrPromisedDate: v })} />
            </div>
            <AmountField label="Amount first borrowed" value={form.principalTaken} onChange={principalTaken => set({ principalTaken })} />
            {form.kind === 'GOLD_LOAN' && (
              <div className="grid gap-3 sm:grid-cols-3">
                <TextField label="Grams pledged" type="number" value={form.grams == null ? '' : String(form.grams)} onChange={v => set({ grams: v ? Number(v) : null })} />
                <TextField label="Purity (karat)" type="number" value={form.purityKarat == null ? '' : String(form.purityKarat)} onChange={v => set({ purityKarat: v ? Number(v) : null })} />
                <AmountField label="Gold value now" value={form.goldValue} onChange={goldValue => set({ goldValue })} />
              </div>
            )}
          </>
        )}
        {(save.error || remove.error) && <p className="text-sm text-red-600">{errorText(save.error ?? remove.error, 'Could not save.')}</p>}
      </div>
    </SlideOver>
  )
}
