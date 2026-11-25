import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { formatINR } from '@/lib/money'
import type { DeductionKey, FinPerson, TaxInput, TaxRegimeResult } from '@/types/finance'
import { AmountField, CheckField } from '../components/Fields'
import { Money } from '../components/Money'
import { NeedsVerification } from '../components/Badges'
import { LoadFailed, Panel, Pill, Row } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText } from '../data'
import { financeKeys } from '../queryKeys'

export const DEDUCTIONS: { key: DeductionKey; label: string; hint: string }[] = [
  { key: 'section80C', label: '80C', hint: 'EPF, PPF, ELSS, life premiums, principal on a home loan, school fees.' },
  { key: 'section80CCD1B', label: '80CCD(1B)', hint: 'Your own extra NPS contribution.' },
  { key: 'section80DSelf', label: '80D, you and family', hint: 'Health insurance premiums.' },
  { key: 'section80DParents', label: '80D, parents', hint: 'Health insurance premiums for your parents.' },
  { key: 'homeLoanInterest', label: 'Home loan interest', hint: 'On a house you live in (section 24b).' },
]

/** The financial year a date falls in: "2026-27". */
export function financialYear(d = new Date()) {
  const start = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`
}

function Working({ result }: { result: TaxRegimeResult }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-brand-600 dark:text-brand-300">How Kai worked it out</summary>
      <div className="mt-2 flex flex-col gap-1">
        {result.steps.map((s, i) => <Row key={i} label={s.label}><Money amount={s.amount} signed={s.amount < 0} /></Row>)}
      </div>
    </details>
  )
}

/**
 * Tax (7.24): the new and old regimes side by side for one person and year, from their gross pay
 * and the deductions they enter. Every slab and limit comes from Settings; while any of them is
 * unconfirmed the screen says so at the top. Gains and advance-tax dates arrive with holdings.
 */
export function Tax({ people, person, onOpen }: { people: FinPerson[]; person: string; onOpen: (view: string) => void }) {
  const qc = useQueryClient()
  const self = people.find(p => p.kind === 'self')
  const earners = people.filter(p => p.status === 'active' && p.type !== 'minor')
  const [personId, setPersonId] = useState(person !== 'all' && earners.some(p => p.id === person) ? person : self?.id ?? '')
  useEffect(() => { if (person !== 'all' && earners.some(p => p.id === person)) setPersonId(person) }, [person]) // eslint-disable-line react-hooks/exhaustive-deps
  const year = financialYear()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: financeKeys.tax(personId, year), queryFn: () => financeApi.tax(personId), enabled: !!personId,
  })
  const [form, setForm] = useState<TaxInput | null>(null)
  useEffect(() => {
    if (data) {
      setForm({
        deductions: { ...data.profile.deductions }, rentPaidMonthly: data.profile.rentPaidMonthly ?? null,
        metro: data.profile.metro ?? null,
      })
    }
  }, [data])
  const save = useMutation({
    mutationFn: () => financeApi.saveTax(personId, form!),
    onSuccess: v => qc.setQueryData(financeKeys.tax(personId, year), v),
  })

  if (isLoading || !form) return <SkeletonRows rows={5} />
  if (error || !data) return <LoadFailed text={errorText(error, 'Could not work out your tax.')} onRetry={() => refetch()} />
  const c = data.comparison
  const name = personId === self?.id ? 'You' : data.personName
  const setDeduction = (k: DeductionKey, v: number | null) =>
    setForm(f => ({ ...f!, deductions: { ...f!.deductions, [k]: v ?? undefined } }))

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Tax · {name} · FY {data.year.replace('-', '–')}</h2>
        <span className="flex-1" />
        {earners.length > 1 && (
          <select aria-label="Whose tax" className="rounded-lg border border-gray-200 bg-white px-2 py-1 text-sm dark:border-gray-700 dark:bg-gray-900"
            value={personId} onChange={e => setPersonId(e.target.value)}>
            {earners.map(p => <option key={p.id} value={p.id}>{p.kind === 'self' ? 'Me' : p.displayName}</option>)}
          </select>
        )}
      </div>

      {c.needsVerification && (
        <div role="alert" className="flex items-start gap-2 rounded-xl bg-yellow-50 p-3 text-sm text-yellow-900 dark:bg-yellow-900/20 dark:text-yellow-100">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span>
            <b>These tax figures have not been verified.</b> Slabs, deductions and rates come from Settings. Check them with your CA before acting.{' '}
            <button type="button" className="font-medium underline" onClick={() => onOpen('settings')}>Open Settings</button>
          </span>
        </div>
      )}

      {data.grossAnnual == null ? (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          Kai needs gross pay to compare the regimes. Add it under <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('cash-flow')}>Cash flow</button>, or in Quick start’s income question.
        </p>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title={<>New regime {c.cheaper === 'new' && c.gap != null && <Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />Cheaper by {formatINR(c.gap)}</Pill>}{c.needsVerification && <NeedsVerification />}</>}>
            {c.newRegime && <>
              <Row label="Income"><Money amount={data.grossAnnual} /></Row>
              <Row label="Taxable income"><Money amount={c.newRegime.taxableIncome} /></Row>
              <Row label="Tax + cess" strong><Money amount={c.newRegime.total} /></Row>
              <Working result={c.newRegime} />
            </>}
          </Panel>
          <Panel title={<>Old regime {c.cheaper === 'old' && c.gap != null && <Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />Cheaper by {formatINR(c.gap)}</Pill>}{c.needsVerification && <NeedsVerification />}</>}>
            {c.oldRegime ? <>
              <Row label="Income"><Money amount={data.grossAnnual} /></Row>
              <Row label="Taxable income"><Money amount={c.oldRegime.taxableIncome} /></Row>
              <Row label="Tax + cess" strong><Money amount={c.oldRegime.total} /></Row>
              <Working result={c.oldRegime} />
            </> : <p className="text-sm text-gray-500 dark:text-gray-400">Not enough data: enter your deductions below to compare.</p>}
          </Panel>
        </div>
      )}
      {c.notes.map(n => <p key={n} className="text-xs text-gray-500 dark:text-gray-400">{n}</p>)}

      <Panel title="Deductions for the old regime">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {DEDUCTIONS.map(d => (
            <AmountField key={d.key} label={d.label} hint={d.hint} value={form.deductions?.[d.key] ?? null} onChange={v => setDeduction(d.key, v)} />
          ))}
          <AmountField label="Rent paid a month" hint="For the HRA exemption; Kai needs basic pay and HRA from your income too."
            value={form.rentPaidMonthly ?? null} onChange={v => setForm(f => ({ ...f!, rentPaidMonthly: v }))} />
        </div>
        <div className="flex flex-wrap gap-4">
          <CheckField label="I live in Delhi, Mumbai, Kolkata or Chennai" checked={!!form.metro} onChange={v => setForm(f => ({ ...f!, metro: v }))} />
          <CheckField label="My parents are 60 or older" checked={!!form.deductions?.parentsSenior}
            onChange={v => setForm(f => ({ ...f!, deductions: { ...f!.deductions, parentsSenior: v } }))} />
        </div>
        <div className="flex items-center gap-2">
          <Button loading={save.isPending} onClick={() => save.mutate()}>Compare again</Button>
          {save.error && <span role="alert" className="text-sm text-red-600">{errorText(save.error, 'Could not save these figures.')}</span>}
        </div>
      </Panel>

      <Panel title="Gains, dates and paperwork">
        <p className="text-sm text-gray-500 dark:text-gray-400">{data.gainsNote}</p>
      </Panel>
    </div>
  )
}
