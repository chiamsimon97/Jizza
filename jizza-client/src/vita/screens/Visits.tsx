import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, Pencil, Phone, Plus, Stethoscope, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { Doctor, DoctorInput, DoctorKind, UsualRange, Visit, VisitInput, VisitStatus } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText, formatWhen, toLocalInput } from '../data'
import { kindLabel } from '../readings'
import { vitaKeys } from '../queryKeys'
import { Checks } from './Checks'

export const DOCTOR_KINDS: Record<DoctorKind, string> = { doctor: 'Doctor', hospital: 'Hospital', clinic: 'Clinic', lab: 'Lab', pharmacy: 'Pharmacy' }
const VISIT_STATUS: Record<VisitStatus, string> = { planned: 'Planned', done: 'Done', cancelled: 'Cancelled' }

/** A target's bounds in words, from the numbers the server holds: "below 130/80" style is the server's; this is "up to 130 / up to 80". */
export function targetWords(r: Pick<UsualRange, 'low' | 'high' | 'low2' | 'high2'>): string {
  const one = (lo: number | null, hi: number | null) => lo != null && hi != null ? `${lo}–${hi}` : hi != null ? `up to ${hi}` : lo != null ? `from ${lo}` : ''
  const a = one(r.low, r.high)
  const b = one(r.low2, r.high2)
  return b ? `${a || '—'} / ${b}` : a
}

/**
 * Doctors and visits (7.10). Doctors belong to the whole household: each with phone, the next visit
 * and the targets they set for each person. Visits are this person's: upcoming first, then past with
 * notes and follow-up dates.
 */
export function Visits({ personId, personName, readOnly, adding, onAddDone }: {
  personId: string
  personName: string
  readOnly: boolean
  /** The Add menu asked for a visit. */
  adding: boolean
  onAddDone: () => void
}) {
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Doctors and visits" sub="Doctors for the whole household; visits for the person picked above" />
      <VisitList personId={personId} personName={personName} readOnly={readOnly} adding={adding} onAddDone={onAddDone} />
      <Checks personId={personId} readOnly={readOnly} />
      <Doctors readOnly={readOnly} />
    </div>
  )
}

function VisitList({ personId, personName, readOnly, adding, onAddDone }: { personId: string; personName: string; readOnly: boolean; adding: boolean; onAddDone: () => void }) {
  const qc = useQueryClient()
  const visits = useQuery({ queryKey: vitaKeys.visits(personId), queryFn: () => vitaApi.visits(personId) })
  const doctors = useQuery({ queryKey: vitaKeys.doctors, queryFn: vitaApi.doctors })
  const [editing, setEditing] = useState<Visit | VisitInput | null>(null)
  const show = editing ?? (adding ? { status: 'planned' as const } : null)
  const refresh = () => { qc.invalidateQueries({ queryKey: vitaKeys.person(personId) }); qc.invalidateQueries({ queryKey: vitaKeys.doctors }) }
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeVisit(personId, id), onSuccess: refresh })
  const now = Date.now()
  const all = visits.data ?? []
  const upcoming = all.filter(v => v.status === 'planned' && new Date(v.scheduledAt).getTime() >= now).sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))
  const past = all.filter(v => !upcoming.includes(v)).sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt))
  const doctorName = (id: string | null) => doctors.data?.find(d => d.id === id)?.name

  const row = (v: Visit) => (
    <li key={v.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2">
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
          {formatWhen(v.scheduledAt)}
          {v.status !== 'planned' && <Pill tone={v.status === 'done' ? 'ok' : 'neutral'}>{VISIT_STATUS[v.status]}</Pill>}
        </p>
        <p className="text-xs text-gray-600 dark:text-gray-300">{[doctorName(v.doctorId), v.reason].filter(Boolean).join(' · ') || 'No doctor or reason given'}</p>
        {v.notes && <p className="whitespace-pre-line text-xs text-gray-600 dark:text-gray-300">{v.notes}</p>}
        {v.followUpOn && <p className="text-xs text-gray-600 dark:text-gray-300">Follow-up on {formatDay(v.followUpOn)}</p>}
      </div>
      {!readOnly && (
        <span className="flex gap-1">
          {v.status === 'planned' && <Button size="sm" variant="secondary" disabled title="Comes with the documents update">Prepare</Button>}
          <Button size="sm" variant="ghost" aria-label="Change this visit" onClick={() => setEditing(v)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
          <Button size="sm" variant="ghost" aria-label="Delete this visit" onClick={() => remove.mutate(v.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
        </span>
      )}
    </li>
  )

  return (
    <Panel title={<><CalendarClock className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Visits · {personName}</>}
      action={!readOnly && !show && <Button size="sm" variant="secondary" onClick={() => setEditing({ status: 'planned' })}><Plus className="h-3.5 w-3.5" aria-hidden />Add visit</Button>}>
      {show && <VisitForm personId={personId} start={show} doctors={doctors.data ?? []} onDone={() => { setEditing(null); onAddDone(); refresh() }} />}
      {visits.isLoading && <SkeletonRows rows={2} />}
      {visits.error && <LoadFailed text="Could not load the visits." onRetry={() => visits.refetch()} />}
      {visits.data && !all.length && !show && <p className="text-sm text-gray-500 dark:text-gray-400">No visits yet. Add the next appointment and Vita shows it on Home.</p>}
      {upcoming.length > 0 && (
        <section aria-label="Upcoming">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">Upcoming</h4>
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">{upcoming.map(row)}</ul>
        </section>
      )}
      {past.length > 0 && (
        <section aria-label="Past">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">Past</h4>
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">{past.map(row)}</ul>
        </section>
      )}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the visit.')}</p>}
    </Panel>
  )
}

function VisitForm({ personId, start, doctors, onDone }: { personId: string; start: Visit | VisitInput; doctors: Doctor[]; onDone: () => void }) {
  const id = 'id' in start ? start.id : null
  const [v, setV] = useState({
    doctorId: start.doctorId ?? '', scheduledAt: toLocalInput(start.scheduledAt), status: start.status ?? 'planned',
    reason: start.reason ?? '', notes: start.notes ?? '', followUpOn: start.followUpOn ?? '',
  })
  const body: VisitInput = {
    doctorId: v.doctorId || null, scheduledAt: v.scheduledAt, status: v.status as VisitStatus,
    reason: v.reason.trim() || null, notes: v.notes.trim() || null, followUpOn: v.followUpOn || null,
  }
  const save = useMutation({ mutationFn: () => (id ? vitaApi.updateVisit(personId, id, body) : vitaApi.addVisit(personId, body)), onSuccess: onDone })
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={id ? 'Change the visit' : 'Add a visit'}
      onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="When" type="datetime-local" value={v.scheduledAt} onChange={scheduledAt => setV({ ...v, scheduledAt })} />
        <SelectField label="Doctor" value={v.doctorId} onChange={doctorId => setV({ ...v, doctorId })}
          hint={!doctors.length ? 'Add the doctor below first, or leave it empty.' : undefined}
          options={[{ value: '', label: 'Not said' }, ...doctors.map(d => ({ value: d.id, label: d.name }))]} />
        <SelectField label="Status" value={v.status} onChange={status => setV({ ...v, status })}
          options={(Object.keys(VISIT_STATUS) as VisitStatus[]).map(s => ({ value: s, label: VISIT_STATUS[s] }))} />
      </div>
      <TextField label="Reason (optional)" value={v.reason} maxLength={300} placeholder="Three-month review" onChange={reason => setV({ ...v, reason })} />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label="Notes (optional)" value={v.notes} maxLength={4000} onChange={notes => setV({ ...v, notes })} />
        <TextField label="Follow-up on (optional)" type="date" value={v.followUpOn} onChange={followUpOn => setV({ ...v, followUpOn })} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!v.scheduledAt} loading={save.isPending}>Save visit</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the visit.')}</p>}
    </form>
  )
}

function Doctors({ readOnly }: { readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.doctors, queryFn: vitaApi.doctors })
  const [editing, setEditing] = useState<Doctor | DoctorInput | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.all })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeDoctor(id), onSuccess: refresh })
  return (
    <Panel title={<><Stethoscope className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Doctors</>}
      action={!readOnly && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing({ kind: 'doctor' })}><Plus className="h-3.5 w-3.5" aria-hidden />Add doctor</Button>}>
      {editing && <DoctorForm start={editing} onDone={() => { setEditing(null); refresh() }} />}
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the doctors." onRetry={() => refetch()} />}
      {data && !data.length && !editing && <p className="text-sm text-gray-500 dark:text-gray-400">No doctors yet. Add one with their phone, and the targets they set show here.</p>}
      <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {(data ?? []).map(d => (
          <li key={d.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2">
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                {d.name}{d.kind !== 'doctor' && <Pill>{DOCTOR_KINDS[d.kind] ?? d.kind}</Pill>}
              </p>
              <p className="text-xs text-gray-600 dark:text-gray-300">{[d.speciality, d.clinic].filter(Boolean).join(' · ')}</p>
              {d.phone && <a href={`tel:${d.phone}`} className="inline-flex w-fit items-center gap-1 font-mono text-xs text-brand-700 underline dark:text-brand-300"><Phone className="h-3 w-3" aria-hidden />{d.phone}</a>}
              <p className="text-xs text-gray-600 dark:text-gray-300">
                Next visit: {d.nextVisit ? <b className="font-medium">{formatWhen(d.nextVisit.scheduledAt)}{d.nextVisit.personName ? ` · ${d.nextVisit.personName}` : ''}</b> : 'none planned'}
              </p>
              {d.targets && d.targets.length > 0 && (
                <ul className="mt-1 flex flex-col gap-0.5">
                  {d.targets.map(t => (
                    <li key={t.id} className="text-xs text-gray-700 dark:text-gray-200">
                      Target for {t.personName ?? 'a person'}: {kindLabel(t.kind)}{t.setting ? ` (${t.setting === 'clinic' ? 'clinic' : 'home'})` : ''}{t.context ? ` (${t.context === 'after_meal' ? 'after meals' : 'before meals'})` : ''} <b className="font-mono font-medium">{targetWords(t)}</b>{t.setOn ? `, set on ${formatDay(t.setOn)}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            {!readOnly && (
              <span className="flex gap-1">
                <Button size="sm" variant="ghost" aria-label={`Change ${d.name}`} onClick={() => setEditing(d)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                <Button size="sm" variant="ghost" aria-label={`Delete ${d.name}`} onClick={() => remove.mutate(d.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
              </span>
            )}
          </li>
        ))}
      </ul>
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the doctor.')}</p>}
    </Panel>
  )
}

function DoctorForm({ start, onDone }: { start: Doctor | DoctorInput; onDone: () => void }) {
  const id = 'id' in start ? start.id : null
  const [v, setV] = useState({
    kind: start.kind ?? 'doctor', name: start.name ?? '', speciality: start.speciality ?? '', clinic: start.clinic ?? '',
    phone: start.phone ?? '', address: start.address ?? '', notes: start.notes ?? '',
  })
  const body: DoctorInput = {
    kind: v.kind as DoctorKind, name: v.name.trim(), speciality: v.speciality.trim() || null, clinic: v.clinic.trim() || null,
    phone: v.phone.trim() || null, address: v.address.trim() || null, notes: v.notes.trim() || null,
  }
  const save = useMutation({ mutationFn: () => (id ? vitaApi.updateDoctor(id, body) : vitaApi.addDoctor(body)), onSuccess: onDone })
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={id ? 'Change the doctor' : 'Add a doctor'}
      onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="Name" value={v.name} maxLength={120} placeholder="Dr S Iyer" onChange={name => setV({ ...v, name })} />
        <SelectField label="Kind" value={v.kind} onChange={kind => setV({ ...v, kind })}
          options={(Object.keys(DOCTOR_KINDS) as DoctorKind[]).map(k => ({ value: k, label: DOCTOR_KINDS[k] }))} />
        <TextField label="Phone (optional)" value={v.phone} maxLength={20} onChange={phone => setV({ ...v, phone })} />
        <TextField label="Speciality (optional)" value={v.speciality} maxLength={80} placeholder="Diabetologist" onChange={speciality => setV({ ...v, speciality })} />
        <TextField label="Clinic or hospital (optional)" value={v.clinic} maxLength={120} onChange={clinic => setV({ ...v, clinic })} />
        <TextField label="Address (optional)" value={v.address} maxLength={300} onChange={address => setV({ ...v, address })} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!v.name.trim()} loading={save.isPending}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the doctor.')}</p>}
    </form>
  )
}
