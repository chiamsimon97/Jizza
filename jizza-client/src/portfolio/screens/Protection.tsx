import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, ShieldQuestion, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SlideOver } from '@/components/ui/SlideOver'
import type { FinEstate, FinPerson, FinPolicy, FinPolicyInput, PersonCover, PolicyKind, QuickStartStep } from '@/types/finance'
import { AsOf, formatDay } from '../components/AsOf'
import { Money } from '../components/Money'
import { SkeletonRows } from '../components/SkeletonRows'
import { StatusBadge } from '../components/StatusBadge'
import { AmountField, CheckField, SelectField, TextField } from '../components/Fields'
import { DataTable } from '../components/DataTable'
import { POLICY_KINDS } from '../labels'
import { errorText, useEstate, usePolicies, useProtection, useSample } from '../data'
import { financeKeys } from '../queryKeys'

/**
 * Protection (7.16): for each person, term cover against need (with the method), health cover
 * against the floor with employer cover shown apart, premiums as a share of income, every policy,
 * and whether a will is recorded. A gap points to an action, never to an insurer.
 */
export function Protection({ people, onOpen, onAnswer }: {
  people: FinPerson[]
  onOpen: (view: string) => void
  onAnswer: (step: QuickStartStep) => void
}) {
  const sample = useSample()
  const { data, isLoading, error, refetch } = useProtection()
  const { data: policies } = usePolicies()
  const [editing, setEditing] = useState<FinPolicy | 'new' | null>(null)

  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
        <p className="text-sm text-red-700 dark:text-red-300">Could not load your cover.</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>Try again</Button>
      </div>
    )
  }
  const p = data.protection
  const self = people.find(x => x.kind === 'self')

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Protection</h2>
        <AsOf date={data.asOf} />
        <span className="flex-1" />
        {!sample && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}><Plus className="h-4 w-4" /> Add policy</Button>}
      </div>

      {!data.known ? (
        <EmptyState icon={ShieldQuestion} title="Not enough data"
          description="Tell Kai what term and health cover you have, or that you have none, to see the gaps."
          action={sample ? undefined : { label: 'Answer this', onClick: () => onAnswer('insurance') }} />
      ) : (
        <>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {p.perPerson.filter(c => c.needsTerm || c.personalHealthCover + c.employerHealthCover + c.superTopUp > 0 || p.gaps.some(g => g.personIds.includes(c.personId)))
              .map(c => <PersonCard key={c.personId} cover={c} floor={p.healthFloor} gaps={p.gaps} isSelf={c.personId === self?.id} />)}
            <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Premiums</h3>
              <p className="text-sm text-gray-600 dark:text-gray-300">
                <Money amount={p.premiumsYearly} /> a year, {p.premiumsPctOfIncome}% of income.
              </p>
              {p.affordabilityFlag && <StatusBadge status="amber" label="Premiums are high for your income" />}
              <p className="text-xs text-gray-500 dark:text-gray-400">Premiums your employer pays are left out.</p>
            </section>
          </div>
          {p.gaps.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <StatusBadge status={p.gaps.length >= 2 ? 'red' : 'amber'} label={`${p.gaps.length} ${p.gaps.length === 1 ? 'gap' : 'gaps'}`} />
              <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('actions')}>See the action to close them</button>
            </div>
          )}
          <p className="text-xs text-gray-500 dark:text-gray-400">Kai never names or recommends an insurer.</p>
        </>
      )}

      <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Policies</h3>
        <DataTable<FinPolicy> rows={policies ?? []} rowKey={r => r.id} caption="Policies" empty="No policies recorded."
          columns={[
            { header: 'Kind', cell: r => <>{POLICY_KINDS[r.kind]}{r.employerProvided ? <span className="block text-xs text-gray-500">Through employer</span> : null}</> },
            { header: 'Whose', cell: r => r.kind === 'term' ? (data.names[r.personId ?? ''] === 'Me' ? 'You' : data.names[r.personId ?? ''] ?? '—')
              : r.coveredPersonIds.map(id => (data.names[id] === 'Me' ? 'You' : data.names[id])).filter(Boolean).join(', ') || '—' },
            { header: 'Cover', numeric: true, cell: r => <Money amount={r.sumAssured} short /> },
            { header: 'Premium', numeric: true, cell: r => (r.premium ? <><Money amount={r.premium} /> <span className="text-xs text-gray-500">{r.premiumFrequency ?? ''}</span></> : '—') },
            { header: 'Renews', cell: r => formatDay(r.renewalDate) },
            ...(sample ? [] : [{ header: '', cell: (r: FinPolicy) => <Button size="sm" variant="ghost" onClick={() => setEditing(r)} aria-label={`Edit the ${POLICY_KINDS[r.kind]} policy`}><Pencil className="h-3.5 w-3.5" /></Button> }]),
          ]} />
      </section>

      {self && <WillCard personId={self.id} />}

      {editing && <PolicyForm policy={editing === 'new' ? null : editing} people={people} onClose={() => setEditing(null)} />}
    </div>
  )
}

function Bar({ value, of, label }: { value: number; of: number; label: string }) {
  const pct = of > 0 ? Math.min(100, (value / of) * 100) : 100
  return (
    <div className="h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" role="img" aria-label={label}>
      <div className={`h-full rounded-full ${pct >= 100 ? 'bg-green-500' : 'bg-red-500'}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

function PersonCard({ cover: c, floor, gaps, isSelf }: { cover: PersonCover; floor: number; gaps: { kind: string; personIds: string[] }[]; isSelf: boolean }) {
  const name = isSelf ? 'You' : c.name
  const healthGap = gaps.some(g => g.kind === 'health' && g.personIds.includes(c.personId))
  return (
    <section className="flex flex-col gap-3 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{name}</h3>
      {c.needsTerm && (
        <div className="flex flex-col gap-1.5">
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="text-gray-700 dark:text-gray-300">Term life cover</span>
            <StatusBadge status={c.termGap > 0 ? 'red' : 'green'} label={c.termGap > 0 ? 'Gap' : 'Covered'} />
          </div>
          <div className="flex justify-between text-xs text-gray-500 dark:text-gray-400">
            <span>Cover <Money amount={c.termCover} short /></span><span>Need <Money amount={c.termNeed} short /></span>
          </div>
          <Bar value={c.termCover} of={c.termNeed} label={`Term cover is ${Math.round(c.termNeed ? (c.termCover / c.termNeed) * 100 : 100)}% of need`} />
          <p className="text-xs text-gray-500 dark:text-gray-400">
            Need = {c.multiple ?? '—'} × yearly income, plus loans, less liquid money (counted once, against the highest earner).
            {c.termGap > 0 && <> Gap <strong className="text-gray-800 dark:text-gray-200"><Money amount={c.termGap} short /></strong>.</>}
          </p>
        </div>
      )}
      <div className="flex flex-col gap-1.5">
        <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
          <span className="text-gray-700 dark:text-gray-300">Health cover</span>
          <StatusBadge status={healthGap ? 'red' : 'green'} label={healthGap ? 'Gap' : 'Covered'} />
        </div>
        <Line label="Personal cover"><Money amount={c.personalHealthCover} short /></Line>
        {c.superTopUp > 0 && <Line label="Super top-up"><Money amount={c.superTopUp} short /></Line>}
        <Line label="Through employer"><Money amount={c.employerHealthCover} short /> · does not count</Line>
        <Line label="Floor"><Money amount={floor} short /> personal</Line>
      </div>
    </section>
  )
}

function Line({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-2 text-xs">
      <span className="text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-800 dark:text-gray-200">{children}</span>
    </div>
  )
}

function WillCard({ personId }: { personId: string }) {
  const qc = useQueryClient()
  const sample = useSample()
  const { data } = useEstate(personId)
  const save = useMutation({
    mutationFn: (body: Partial<FinEstate>) => financeApi.saveEstate(personId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })
  if (!data) return null
  return (
    <section className="flex flex-wrap items-end gap-3 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <div className="min-w-0 flex-1 basis-56">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Your will</h3>
        <p className="text-xs text-gray-500 dark:text-gray-400">Not scored, but Kai flags it until one is recorded.</p>
      </div>
      <SelectField label="Will" value={data.willStatus}
        options={[{ value: 'none', label: 'No will yet' }, { value: 'draft', label: 'Written, not registered' }, { value: 'registered', label: 'Registered' }]}
        onChange={willStatus => !sample && save.mutate({ willStatus })} />
      {save.error && <p className="w-full text-sm text-red-600">{errorText(save.error, 'Could not save.')}</p>}
    </section>
  )
}

function PolicyForm({ policy, people, onClose }: { policy: FinPolicy | null; people: FinPerson[]; onClose: () => void }) {
  const qc = useQueryClient()
  const active = people.filter(p => p.status === 'active')
  const self = active.find(p => p.kind === 'self')
  const [form, setForm] = useState<FinPolicyInput>({
    kind: policy?.kind ?? 'health',
    personId: policy?.personId ?? self?.id,
    providerName: policy?.providerName ?? '',
    sumAssured: policy?.sumAssured,
    premium: policy?.premium ?? null,
    premiumFrequency: policy?.premiumFrequency ?? 'yearly',
    renewalDate: policy?.renewalDate ?? null,
    employerProvided: policy?.employerProvided ?? false,
    coveredPersonIds: policy?.coveredPersonIds ?? active.map(p => p.id),
  })
  const set = (patch: Partial<FinPolicyInput>) => setForm(f => ({ ...f, ...patch }))
  const done = () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() }
  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, providerName: form.providerName || null, renewalDate: form.renewalDate || null }
      return policy ? financeApi.updatePolicy(policy.id, body) : financeApi.addPolicy(body)
    },
    onSuccess: done,
  })
  const remove = useMutation({ mutationFn: () => financeApi.deletePolicy(policy!.id), onSuccess: done })
  const health = form.kind !== 'term' && form.kind !== 'endowment' && form.kind !== 'ulip'

  return (
    <SlideOver open title={policy ? 'Edit policy' : 'Add a policy'} description="Cover amounts and dates only; Kai never names or recommends an insurer." onClose={onClose}
      footer={<>
        {policy && <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}><Trash2 className="h-4 w-4" /> Remove</Button>}
        <span className="flex-1" />
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} disabled={!(form.sumAssured && form.sumAssured > 0)} onClick={() => save.mutate()}>Save</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <SelectField<PolicyKind> label="Kind" value={form.kind!} onChange={kind => set({ kind })}
          options={(Object.keys(POLICY_KINDS) as PolicyKind[]).map(k => ({ value: k, label: POLICY_KINDS[k] }))} />
        <AmountField label="Cover (sum assured)" value={form.sumAssured} onChange={v => set({ sumAssured: v ?? undefined })} />
        {health ? (
          <fieldset className="flex flex-col gap-1">
            <legend className="text-sm font-medium text-gray-700 dark:text-gray-300">Who it covers</legend>
            <div className="flex flex-wrap gap-3">
              {active.map(p => (
                <CheckField key={p.id} label={p.kind === 'self' ? 'You' : p.displayName} checked={(form.coveredPersonIds ?? []).includes(p.id)}
                  onChange={on => set({ coveredPersonIds: on ? [...(form.coveredPersonIds ?? []), p.id] : (form.coveredPersonIds ?? []).filter(x => x !== p.id) })} />
              ))}
            </div>
          </fieldset>
        ) : (
          <SelectField label="Whose life" value={form.personId ?? ''} onChange={personId => set({ personId })}
            options={active.map(p => ({ value: p.id, label: p.kind === 'self' ? 'You' : p.displayName }))} />
        )}
        <TextField label="Insurer (optional)" value={form.providerName} onChange={providerName => set({ providerName })} />
        <div className="grid gap-3 sm:grid-cols-2">
          <AmountField label="Premium" value={form.premium} onChange={premium => set({ premium })} />
          <SelectField label="Paid" value={form.premiumFrequency ?? 'yearly'} onChange={premiumFrequency => set({ premiumFrequency })}
            options={[{ value: 'monthly', label: 'Monthly' }, { value: 'quarterly', label: 'Quarterly' }, { value: 'yearly', label: 'Yearly' }, { value: 'single', label: 'Once' }]} />
        </div>
        <TextField label="Renews on" type="date" value={form.renewalDate ?? ''} onChange={renewalDate => set({ renewalDate })} />
        {health && <CheckField label="Through my employer (ends with the job)" checked={!!form.employerProvided} onChange={employerProvided => set({ employerProvided })} />}
        {(save.error || remove.error) && <p className="text-sm text-red-600">{errorText(save.error ?? remove.error, 'Could not save.')}</p>}
      </div>
    </SlideOver>
  )
}
