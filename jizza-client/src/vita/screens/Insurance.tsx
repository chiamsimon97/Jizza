import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FilePen, FileText, HelpCircle, Send, ShieldPlus, XCircle } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatINR } from '@/lib/money'
import { formatDay } from '@/portfolio/components/AsOf'
import { AmountField, CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { ClaimInput, ClaimStatus, HealthClaim, HealthPolicy, PolicyDetailInput, PolicyInput, PolicyKind, PremiumFrequency } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText, useVitaPeople } from '../data'
import { personName } from '../labels'
import { vitaKeys } from '../queryKeys'
import { canEdit, useShareScope } from '../share'

export const POLICY_KINDS: Record<PolicyKind, string> = {
  health: 'Health insurance', super_topup: 'Super top-up', critical_illness: 'Critical illness',
  personal_accident: 'Personal accident', government_scheme: 'Government scheme',
}
const FREQUENCIES: Record<PremiumFrequency, string> = { monthly: 'Monthly', quarterly: 'Quarterly', yearly: 'Yearly', single: 'Single premium' }
const CLAIM_STATUS: Record<ClaimStatus, { label: string; icon: typeof Send; tone: 'ok' | 'warn' | 'bad' | 'info' | 'neutral' }> = {
  draft: { label: 'Draft', icon: FilePen, tone: 'neutral' },
  filed: { label: 'Filed', icon: Send, tone: 'info' },
  query: { label: 'Insurer asked a question', icon: HelpCircle, tone: 'warn' },
  settled: { label: 'Settled', icon: CheckCircle2, tone: 'ok' },
  rejected: { label: 'Rejected', icon: XCircle, tone: 'bad' },
}

const day = (d: string | null | undefined) => (d ? formatDay(d.slice(0, 10)) : '—')
const money = (n: number | null | undefined) => (n == null ? '—' : formatINR(Number(n)))

/**
 * Insurance (7.15): the policies covering this person, government and employer cover beside
 * private ones, with the masked policy number, sum insured, who is covered, renewal, the cashless
 * card, and "Used" and "Left this policy year"; then the person's claims with status and amounts.
 * Policies belong to whoever keeps the household's records; Kai's Protection view reads them.
 */
export function Insurance({ personId, personName: name, readOnly, onOpenDocument }: {
  personId: string; personName: string; readOnly: boolean; onOpenDocument: (id: string) => void
}) {
  const share = useShareScope()
  const manager = !share.shared
  const policies = useQuery({ queryKey: vitaKeys.policies(personId), queryFn: () => vitaApi.policies(personId) })
  const people = useVitaPeople()
  const [editing, setEditing] = useState<HealthPolicy | 'new' | null>(null)
  const [detailFor, setDetailFor] = useState<HealthPolicy | null>(null)
  const you = name === 'You'
  const names = (ids: string[]) => ids.map(id => {
    const p = people.data?.find(x => x.id === id)
    return p ? personName(p) : id === personId ? name : 'someone else'
  }).join(', ')

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Insurance" sub={`Health cover for ${you ? 'you' : name}, and ${you ? 'your' : 'their'} claims`}>
        {manager && !readOnly && editing == null && <Button size="sm" onClick={() => setEditing('new')}>Add a policy</Button>}
      </ViewTitle>

      {editing != null && (
        <PolicyForm key={editing === 'new' ? 'new' : editing.id} policy={editing === 'new' ? null : editing} personId={personId}
          people={(people.data ?? []).filter(p => p.status === 'active' && p.kind !== 'huf')} onDone={() => setEditing(null)} />
      )}
      {detailFor && <DetailForm key={detailFor.id} policy={detailFor} personId={personId} onDone={() => setDetailFor(null)} />}

      {policies.isLoading && <SkeletonRows rows={3} />}
      {policies.error && <LoadFailed text="Could not load the policies." onRetry={() => policies.refetch()} />}
      {policies.data && !policies.data.length && editing == null && (
        <Panel title={<><ShieldPlus className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />No cover recorded</>}>
          <p className="text-sm text-gray-600 dark:text-gray-300">
            Add each health policy, top-up, employer cover or government scheme such as PM-JAY, CGHS or ESIC once. A family floater covers several people with one policy.
          </p>
        </Panel>
      )}
      {policies.data && policies.data.length > 0 && (
        <div className="grid gap-3 lg:grid-cols-2">
          {policies.data.map(p => (
            <PolicyCard key={p.id} p={p} covers={names(p.coveredPersonIds)} manager={manager && !readOnly}
              onEdit={() => setEditing(p)} onDetail={() => setDetailFor(p)} onOpenDocument={onOpenDocument} />
          ))}
        </div>
      )}

      <Claims personId={personId} name={name} policies={policies.data ?? []} readOnly={readOnly || !canEdit(share, 'insurance')} manager={manager} />

      <p className="text-xs text-gray-500 dark:text-gray-400">
        Kai’s Protection view reads these policies. Vita keeps what you enter; it never names, compares or recommends an insurer or a scheme.
      </p>
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap justify-between gap-x-3 gap-y-0.5 py-1 text-sm">
      <dt className="text-gray-500 dark:text-gray-400">{label}</dt>
      <dd className="text-right text-gray-900 dark:text-gray-100">{children}</dd>
    </div>
  )
}

function PolicyCard({ p, covers, manager, onEdit, onDetail, onOpenDocument }: {
  p: HealthPolicy; covers: string; manager: boolean; onEdit: () => void; onDetail: () => void; onOpenDocument: (id: string) => void
}) {
  const qc = useQueryClient()
  const remove = useMutation({ mutationFn: () => vitaApi.removePolicy(p.id), onSuccess: () => qc.invalidateQueries({ queryKey: vitaKeys.all }) })
  const d = p.detail
  return (
    <section aria-label={p.providerName ?? POLICY_KINDS[p.kind]} className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-gray-900">
      <header className="flex flex-wrap items-center gap-1.5">
        <h3 className="mr-auto text-sm font-semibold text-gray-900 dark:text-gray-100">{p.providerName ?? POLICY_KINDS[p.kind]}</h3>
        <Pill tone="neutral">{POLICY_KINDS[p.kind]}</Pill>
        {p.floater && <Pill tone="info">Floater</Pill>}
        {p.employerProvided && <Pill tone="neutral">From employer</Pill>}
        {p.shared && <Pill tone="neutral">Shared with you</Pill>}
      </header>
      <dl className="divide-y divide-gray-100 dark:divide-gray-800">
        <Row label="Policy number">{d.policyNumberMasked ?? 'Not added'}</Row>
        <Row label="Sum insured">{p.kind === 'government_scheme' && !p.sumAssured ? 'As the scheme sets' : money(p.sumAssured)}</Row>
        <Row label="Covers">{covers || '—'}</Row>
        {p.deductible ? <Row label="Deductible">{money(p.deductible)}</Row> : null}
        {p.premium ? <Row label="Premium">{money(p.premium)}{p.premiumFrequency ? `, ${FREQUENCIES[p.premiumFrequency].toLowerCase()}` : ''}</Row> : null}
        <Row label="Renews on">{day(p.renewalDate)}{p.renewalReminderOn ? <span className="block text-xs text-gray-500 dark:text-gray-400">Reminder on {day(p.renewalReminderOn)}</span> : null}</Row>
        {p.policyYear && <Row label="Policy year">{day(p.policyYear.from)} to {day(p.policyYear.to)}</Row>}
        {p.usedThisYear != null && <Row label="Used this policy year">{money(p.usedThisYear)}</Row>}
        {p.leftThisYear != null && <Row label="Left this policy year">{money(p.leftThisYear)}</Row>}
        {d.cardNumberMasked && <Row label="Card number">{d.cardNumberMasked}</Row>}
        {d.tpaName && <Row label="TPA">{d.tpaName}</Row>}
        {d.roomRentLimit != null && <Row label="Room rent limit">{money(d.roomRentLimit)} a day</Row>}
        {d.copayPct != null && <Row label="Co-pay">{Number(d.copayPct)}%</Row>}
        {p.claimCount != null && p.claimCount > 0 && <Row label="Claims">{p.claimCount}</Row>}
      </dl>
      {d.waitingPeriods.length > 0 && (
        <div className="text-sm">
          <p className="text-xs font-medium text-gray-500 dark:text-gray-400">Waiting periods</p>
          <ul className="list-disc pl-5 text-gray-800 dark:text-gray-200">{d.waitingPeriods.map((w, i) => <li key={i}>{w.text}</li>)}</ul>
        </div>
      )}
      {d.networkHospitalsNote && <p className="whitespace-pre-line text-sm text-gray-700 dark:text-gray-300"><span className="text-xs font-medium text-gray-500 dark:text-gray-400">Network hospitals: </span>{d.networkHospitalsNote}</p>}
      <div className="flex flex-wrap gap-2">
        {d.cashlessCardDocumentId && (
          <Button size="sm" variant="secondary" onClick={() => onOpenDocument(d.cashlessCardDocumentId!)}><FileText className="h-3.5 w-3.5" aria-hidden />Cashless card</Button>
        )}
        {manager && <Button size="sm" variant="secondary" onClick={onEdit}>Edit</Button>}
        {manager && <Button size="sm" variant="secondary" onClick={onDetail}>Card and policy details</Button>}
        {manager && (
          <Button size="sm" variant="ghost" loading={remove.isPending}
            onClick={() => { if (window.confirm(`Remove ${p.providerName ?? 'this policy'}? Its claims stay in each person’s record.`)) remove.mutate() }}>Remove</Button>
        )}
      </div>
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the policy.')}</p>}
    </section>
  )
}

function PolicyForm({ policy, personId, people, onDone }: {
  policy: HealthPolicy | null; personId: string; people: { id: string; displayName: string; kind: string }[]; onDone: () => void
}) {
  const qc = useQueryClient()
  const [v, setV] = useState<PolicyInput>(() => policy
    ? { kind: policy.kind, providerName: policy.providerName, sumAssured: policy.sumAssured, deductible: policy.deductible, premium: policy.premium,
      premiumFrequency: policy.premiumFrequency, renewalDate: policy.renewalDate, policyEndDate: policy.policyEndDate,
      employerProvided: policy.employerProvided, personId: policy.personId, coveredPersonIds: policy.coveredPersonIds }
    : { kind: 'health', personId, coveredPersonIds: [personId], premiumFrequency: 'yearly', employerProvided: false })
  const set = (patch: PolicyInput) => setV({ ...v, ...patch })
  const covered = v.coveredPersonIds ?? []
  const toggle = (id: string) => {
    const next = covered.includes(id) ? covered.filter(x => x !== id) : [...covered, id]
    // The holder is always one of the people covered.
    set({ coveredPersonIds: next, personId: v.personId && next.includes(v.personId) ? v.personId : next[0] })
  }
  const scheme = v.kind === 'government_scheme'
  const holderOk = !!v.personId && covered.includes(v.personId)
  const save = useMutation({
    mutationFn: () => {
      const body: PolicyInput = { ...v, providerName: v.providerName?.trim() || null, renewalDate: v.renewalDate || null, policyEndDate: v.policyEndDate || null }
      return policy ? vitaApi.updatePolicy(policy.id, body) : vitaApi.addPolicy(body)
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.all }); onDone() },
  })
  const ok = !!v.kind && covered.length > 0 && holderOk && (scheme || (v.sumAssured ?? 0) > 0)

  return (
    <Panel title={policy ? 'Edit policy' : 'Add a policy'}>
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Kind" value={v.kind ?? 'health'} onChange={kind => set({ kind })}
            options={(Object.keys(POLICY_KINDS) as PolicyKind[]).map(k => ({ value: k, label: POLICY_KINDS[k] }))} />
          <TextField label={scheme ? 'Scheme' : 'Insurer'} value={v.providerName ?? ''} maxLength={120} placeholder={scheme ? 'PM-JAY' : 'As on the policy'} onChange={providerName => set({ providerName })} />
          <AmountField label={scheme ? 'Sum insured (optional)' : 'Sum insured'} value={v.sumAssured} onChange={sumAssured => set({ sumAssured })} />
          <AmountField label="Deductible (optional)" value={v.deductible} onChange={deductible => set({ deductible })} hint="For a super top-up" />
          <AmountField label="Premium (optional)" value={v.premium} onChange={premium => set({ premium })} />
          <SelectField label="Paid" value={v.premiumFrequency ?? 'yearly'} onChange={premiumFrequency => set({ premiumFrequency })}
            options={(Object.keys(FREQUENCIES) as PremiumFrequency[]).map(k => ({ value: k, label: FREQUENCIES[k] }))} />
          <TextField label="Renews on" type="date" value={v.renewalDate ?? ''} onChange={renewalDate => set({ renewalDate })} hint="Sets the policy year and a reminder" />
          <TextField label="Ends on (optional)" type="date" value={v.policyEndDate ?? ''} onChange={policyEndDate => set({ policyEndDate })} />
        </div>
        <CheckField label="Provided by an employer" checked={!!v.employerProvided} onChange={employerProvided => set({ employerProvided })} />
        <fieldset className="flex flex-col gap-1.5">
          <legend className="mb-1 text-xs font-medium text-gray-600 dark:text-gray-300">Who it covers</legend>
          <div className="flex flex-wrap gap-3">
            {people.map(p => (
              <label key={p.id} className="inline-flex items-center gap-1.5 text-sm text-gray-800 dark:text-gray-200">
                <input type="checkbox" checked={covered.includes(p.id)} onChange={() => toggle(p.id)} />{personName(p as never)}
              </label>
            ))}
          </div>
          {covered.length > 1 && (
            <SelectField label="Policy holder" value={v.personId ?? ''} onChange={personId => set({ personId })}
              options={people.filter(p => covered.includes(p.id)).map(p => ({ value: p.id, label: personName(p as never) }))} />
          )}
        </fieldset>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the policy.')}</p>}
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={!ok}>{policy ? 'Save' : 'Add policy'}</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </Panel>
  )
}

/** Card and policy details. Numbers are typed in full and only ever shown masked again. */
function DetailForm({ policy, personId, onDone }: { policy: HealthPolicy; personId: string; onDone: () => void }) {
  const qc = useQueryClient()
  const d = policy.detail
  const docs = useQuery({ queryKey: vitaKeys.documents(personId), queryFn: () => vitaApi.documents(personId) })
  const [v, setV] = useState({
    policyNumber: '', cardNumber: '', tpaName: d.tpaName ?? '', cashlessCardDocumentId: d.cashlessCardDocumentId ?? '',
    roomRentLimit: d.roomRentLimit, copayPct: d.copayPct, waiting: d.waitingPeriods.map(w => w.text).join('\n'), note: d.networkHospitalsNote ?? '',
  })
  const save = useMutation({
    mutationFn: () => {
      const body: PolicyDetailInput = {
        tpaName: v.tpaName.trim() || null, cashlessCardDocumentId: v.cashlessCardDocumentId || null, roomRentLimit: v.roomRentLimit,
        copayPct: v.copayPct, networkHospitalsNote: v.note.trim() || null,
        waitingPeriods: v.waiting.split('\n').map(t => t.trim()).filter(Boolean).slice(0, 20).map(text => ({ text })),
      }
      if (v.policyNumber.trim()) body.policyNumber = v.policyNumber.trim()
      if (v.cardNumber.trim()) body.cardNumber = v.cardNumber.trim()
      return vitaApi.putPolicyDetail(policy.id, body)
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.all }); onDone() },
  })
  const number = /^[A-Za-z0-9/\- ]{4,40}$/
  const bad = (s: string) => !!s.trim() && !number.test(s.trim())

  return (
    <Panel title={`Card and policy details: ${policy.providerName ?? POLICY_KINDS[policy.kind]}`}>
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Policy number" value={v.policyNumber} maxLength={40} placeholder={d.policyNumberMasked ? `Saved: ${d.policyNumberMasked}` : 'As printed'}
            hint={bad(v.policyNumber) ? 'Letters, digits, spaces, / and - only' : 'Shown only as the last four afterwards'} onChange={policyNumber => setV({ ...v, policyNumber })} />
          <TextField label="Card number" value={v.cardNumber} maxLength={40} placeholder={d.cardNumberMasked ? `Saved: ${d.cardNumberMasked}` : 'As printed'}
            hint={bad(v.cardNumber) ? 'Letters, digits, spaces, / and - only' : undefined} onChange={cardNumber => setV({ ...v, cardNumber })} />
          <TextField label="TPA (optional)" value={v.tpaName} maxLength={120} onChange={tpaName => setV({ ...v, tpaName })} />
          <SelectField label="Cashless card" value={v.cashlessCardDocumentId} onChange={cashlessCardDocumentId => setV({ ...v, cashlessCardDocumentId })}
            options={[{ value: '', label: docs.data?.length ? 'None' : 'Upload it in Documents first' }, ...(docs.data ?? []).map(x => ({ value: x.id, label: x.title ?? `Document of ${day(x.documentDate)}` }))]} />
          <AmountField label="Room rent limit a day (optional)" value={v.roomRentLimit} onChange={roomRentLimit => setV({ ...v, roomRentLimit })} />
          <TextField label="Co-pay % (optional)" type="number" value={v.copayPct == null ? '' : String(v.copayPct)}
            onChange={x => setV({ ...v, copayPct: x === '' ? null : Math.min(100, Math.max(0, Number(x))) })} />
        </div>
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          Waiting periods, one a line (as the policy states them)
          <textarea className={`${fieldClass} min-h-[4rem]`} value={v.waiting} onChange={e => setV({ ...v, waiting: e.target.value })} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
          Network hospitals (optional)
          <textarea className={`${fieldClass} min-h-[3rem]`} value={v.note} maxLength={2000} onChange={e => setV({ ...v, note: e.target.value })} />
        </label>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the details.')}</p>}
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={bad(v.policyNumber) || bad(v.cardNumber)}>Save details</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
        </div>
      </form>
    </Panel>
  )
}

function ClaimStatusPill({ status }: { status: ClaimStatus }) {
  const s = CLAIM_STATUS[status] ?? CLAIM_STATUS.draft
  const Icon = s.icon
  return <Pill tone={s.tone}><Icon className="h-3 w-3" aria-hidden />{s.label}</Pill>
}

/** The person's claims: stay, policy, claimed, paid, out of pocket and status. */
function Claims({ personId, name, policies, readOnly, manager }: { personId: string; name: string; policies: HealthPolicy[]; readOnly: boolean; manager: boolean }) {
  const qc = useQueryClient()
  const claims = useQuery({ queryKey: vitaKeys.claims(personId), queryFn: () => vitaApi.claims(personId) })
  const [editing, setEditing] = useState<HealthClaim | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (c: HealthClaim) => vitaApi.removeClaim(personId, c.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: vitaKeys.all }),
  })
  const policyName = (c: HealthClaim) => c.policy?.providerName ?? (c.policy ? POLICY_KINDS[c.policy.kind] : policies.find(p => p.id === c.policyId)?.providerName ?? 'Policy')

  return (
    <Panel title="Claims" action={!readOnly && policies.length > 0 && editing == null && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}>Add a claim</Button>}>
      {editing != null && (
        <ClaimForm key={editing === 'new' ? 'new' : editing.id} personId={personId} claim={editing === 'new' ? null : editing} policies={policies} onDone={() => setEditing(null)} />
      )}
      {claims.isLoading && <SkeletonRows rows={2} />}
      {claims.error && <LoadFailed text="Could not load the claims." onRetry={() => claims.refetch()} />}
      {claims.data && !claims.data.length && (
        <p className="text-sm text-gray-500 dark:text-gray-400">
          {policies.length ? `No claims for ${name === 'You' ? 'you' : name} yet. Add one after a hospital stay to track what was paid and what you paid yourself.` : 'Add a policy first, then its claims.'}
        </p>
      )}
      {claims.data && claims.data.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {claims.data.map(c => (
            <li key={c.id} className="flex flex-col gap-1 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{day(c.admittedOn)}{c.dischargedOn ? ` to ${day(c.dischargedOn)}` : ''}</span>
                <ClaimStatusPill status={c.status} />
                <span className="text-xs text-gray-500 dark:text-gray-400">{policyName(c)}</span>
              </div>
              <dl className="grid grid-cols-3 gap-2 text-sm">
                <div><dt className="text-xs text-gray-500 dark:text-gray-400">Claimed</dt><dd className="tabular-nums">{money(c.amountClaimed)}</dd></div>
                <div><dt className="text-xs text-gray-500 dark:text-gray-400">Paid</dt><dd className="tabular-nums">{money(c.amountPaid)}</dd></div>
                <div><dt className="text-xs text-gray-500 dark:text-gray-400">Out of pocket</dt><dd className="tabular-nums">{money(c.outOfPocket)}</dd></div>
              </dl>
              {c.policyYearTo && <p className="text-xs text-gray-500 dark:text-gray-400">Counts in the policy year ending {day(c.policyYearTo)}.</p>}
              {c.notes && <p className="whitespace-pre-line text-sm text-gray-700 dark:text-gray-300">{c.notes}</p>}
              {!readOnly && (
                <div className="flex gap-2">
                  <Button size="sm" variant="ghost" onClick={() => setEditing(c)}>Edit</Button>
                  {manager && (
                    <Button size="sm" variant="ghost" loading={remove.isPending && remove.variables?.id === c.id}
                      onClick={() => { if (window.confirm('Remove this claim?')) remove.mutate(c) }}>Remove</Button>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the claim.')}</p>}
    </Panel>
  )
}

function ClaimForm({ personId, claim, policies, onDone }: { personId: string; claim: HealthClaim | null; policies: HealthPolicy[]; onDone: () => void }) {
  const qc = useQueryClient()
  const stays = useQuery({ queryKey: vitaKeys.events(personId), queryFn: () => vitaApi.events(personId) })
  const [v, setV] = useState<ClaimInput>(() => claim
    ? { policyId: claim.policyId, eventId: claim.eventId, admittedOn: claim.admittedOn, dischargedOn: claim.dischargedOn, amountClaimed: claim.amountClaimed, amountPaid: claim.amountPaid, status: claim.status, notes: claim.notes }
    : { policyId: policies[0]?.id, admittedOn: '', status: 'draft' })
  const set = (patch: ClaimInput) => setV({ ...v, ...patch })
  const pickStay = (id: string) => {
    const s = stays.data?.find(x => x.id === id)
    set({ eventId: id || null, ...(s ? { admittedOn: s.fromDate, dischargedOn: s.toDate } : {}) })
  }
  const order = !!v.admittedOn && !!v.dischargedOn && v.dischargedOn < v.admittedOn
  const settledNoPaid = v.status === 'settled' && v.amountPaid == null
  const save = useMutation({
    mutationFn: () => {
      const body: ClaimInput = { ...v, dischargedOn: v.dischargedOn || null, notes: v.notes?.trim() || null }
      return claim ? vitaApi.updateClaim(personId, claim.id, body) : vitaApi.addClaim(personId, body)
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.all }); onDone() },
  })

  return (
    <form className="flex flex-col gap-3 rounded-xl border border-gray-200 p-3 dark:border-gray-700" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-2">
        <SelectField label="Policy" value={v.policyId ?? ''} onChange={policyId => set({ policyId })}
          options={policies.map(p => ({ value: p.id, label: `${p.providerName ?? POLICY_KINDS[p.kind]}${p.detail.policyNumberMasked ? ` (${p.detail.policyNumberMasked})` : ''}` }))} />
        <SelectField label="Hospital stay (optional)" value={v.eventId ?? ''} onChange={pickStay}
          options={[{ value: '', label: 'Not linked' }, ...(stays.data ?? []).map(s => ({ value: s.id, label: `${s.title ?? 'Stay'}, ${day(s.fromDate)}` }))]} />
        <TextField label="Admitted on" type="date" value={v.admittedOn ?? ''} onChange={admittedOn => set({ admittedOn })} />
        <TextField label="Discharged on (optional)" type="date" value={v.dischargedOn ?? ''} onChange={dischargedOn => set({ dischargedOn })}
          hint={order ? 'Discharge cannot be before admission' : undefined} />
        <AmountField label="Amount claimed" value={v.amountClaimed} onChange={amountClaimed => set({ amountClaimed })} />
        <AmountField label={v.status === 'settled' ? 'Amount paid' : 'Amount paid (once settled)'} value={v.amountPaid} onChange={amountPaid => set({ amountPaid })}
          hint={settledNoPaid ? 'A settled claim needs the amount paid' : undefined} />
        <SelectField label="Status" value={v.status ?? 'draft'} onChange={status => set({ status })}
          options={(Object.keys(CLAIM_STATUS) as ClaimStatus[]).map(k => ({ value: k, label: CLAIM_STATUS[k].label }))} />
      </div>
      <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
        Notes (optional)
        <textarea className={`${fieldClass} min-h-[3rem]`} value={v.notes ?? ''} maxLength={2000} onChange={e => set({ notes: e.target.value })} />
      </label>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the claim.')}</p>}
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={!v.policyId || !v.admittedOn || order || settledNoPaid}>{claim ? 'Save' : 'Add claim'}</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  )
}
