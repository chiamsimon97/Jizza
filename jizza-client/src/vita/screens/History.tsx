import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BedDouble, ExternalLink, Pencil, Plus, Syringe, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { HealthEvent, HealthEventInput, HealthEventKind, Vaccination, VaccinationInput } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { vitaKeys } from '../queryKeys'

const today = () => new Date().toISOString().slice(0, 10)

/** Vaccinations (7.11): doses with the certificate, newest first, and the next dose the card or the doctor gave. */
export function Vaccinations({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.vaccinations(personId), queryFn: () => vitaApi.vaccinations(personId) })
  const docs = useQuery({ queryKey: vitaKeys.documents(personId), queryFn: () => vitaApi.documents(personId), enabled: !readOnly })
  const [editing, setEditing] = useState<Vaccination | 'new' | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeVaccination(personId, id), onSuccess: refresh })
  const certificates = (docs.data ?? []).filter(d => d.kind === 'certificate' || d.kind === 'other').map(d => ({ id: d.id, name: d.title || 'Certificate' }))
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Vaccinations" sub="Doses and their certificates" />
      <Panel title={<><Syringe className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Doses</>}
        action={!readOnly && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}><Plus className="h-3.5 w-3.5" aria-hidden />Add a dose</Button>}>
        {editing && <VaccinationForm personId={personId} start={editing === 'new' ? null : editing} certificates={certificates} onDone={() => { setEditing(null); refresh() }} />}
        {isLoading && <SkeletonRows rows={3} />}
        {error && <LoadFailed text="Could not load the vaccinations." onRetry={() => refetch()} />}
        {data && !data.length && !editing && <p className="text-sm text-gray-500 dark:text-gray-400">No vaccinations recorded. Add a dose from the card, with the date and the place.</p>}
        {data && data.length > 0 && (
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
            {data.map(v => (
              <li key={v.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2">
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                    {v.vaccine}{v.doseNumber ? <span className="font-normal text-gray-600 dark:text-gray-300">dose {v.doseNumber}</span> : null}
                    {v.nextDueOn && v.nextDueOn <= today() && <Pill tone="warn">Next dose due</Pill>}
                  </p>
                  <p className="text-xs text-gray-600 dark:text-gray-300">
                    {[v.givenOn ? `Given ${formatDay(v.givenOn)}` : 'Not given yet', v.place, v.batch ? `Batch ${v.batch}` : null, v.nextDueOn ? `next by ${formatDay(v.nextDueOn)}` : null].filter(Boolean).join(' · ')}
                  </p>
                  {v.notes && <p className="text-xs text-gray-500 dark:text-gray-400">{v.notes}</p>}
                </div>
                {v.documentId && (
                  <a href={vitaApi.documentFileUrl(v.documentId)} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-brand-700 underline dark:text-brand-300">
                    <ExternalLink className="h-3 w-3" aria-hidden />Certificate
                  </a>
                )}
                {!readOnly && (
                  <span className="flex gap-1">
                    <Button size="sm" variant="ghost" aria-label={`Change ${v.vaccine}`} onClick={() => setEditing(v)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                    <Button size="sm" variant="ghost" aria-label={`Delete ${v.vaccine}`} onClick={() => remove.mutate(v.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
        {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the dose.')}</p>}
      </Panel>
    </div>
  )
}

function VaccinationForm({ personId, start, certificates, onDone }: { personId: string; start: Vaccination | null; certificates: { id: string; name: string }[]; onDone: () => void }) {
  const [v, setV] = useState({
    vaccine: start?.vaccine ?? '', doseNumber: start?.doseNumber ? String(start.doseNumber) : '', givenOn: start?.givenOn ?? '', place: start?.place ?? '',
    batch: start?.batch ?? '', documentId: start?.documentId ?? '', nextDueOn: start?.nextDueOn ?? '', notes: start?.notes ?? '',
  })
  const body: VaccinationInput = {
    vaccine: v.vaccine.trim(), doseNumber: v.doseNumber ? Number(v.doseNumber) : null, givenOn: v.givenOn || null, place: v.place.trim() || null,
    batch: v.batch.trim() || null, documentId: v.documentId || null, nextDueOn: v.nextDueOn || null, notes: v.notes.trim() || null,
  }
  const save = useMutation({ mutationFn: () => (start ? vitaApi.updateVaccination(personId, start.id, body) : vitaApi.addVaccination(personId, body)), onSuccess: onDone })
  const dose = Number(v.doseNumber)
  const badDose = !!v.doseNumber && (!Number.isInteger(dose) || dose < 1 || dose > 20)
  const missingDate = !v.givenOn && !v.nextDueOn
  return (
    <form aria-label={start ? 'Change the dose' : 'Add a dose'} className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="Vaccine" value={v.vaccine} maxLength={120} placeholder="Influenza" onChange={vaccine => setV({ ...v, vaccine })} />
        <TextField label="Dose number (optional)" value={v.doseNumber} maxLength={2} onChange={doseNumber => setV({ ...v, doseNumber })} hint={badDose ? 'A number from 1 to 20.' : undefined} />
        <TextField label="Given on" type="date" value={v.givenOn} onChange={givenOn => setV({ ...v, givenOn })} />
        <TextField label="Place (optional)" value={v.place} maxLength={200} onChange={place => setV({ ...v, place })} />
        <TextField label="Batch (optional)" value={v.batch} maxLength={60} onChange={batch => setV({ ...v, batch })} />
        <TextField label="Next dose by (optional)" type="date" value={v.nextDueOn} onChange={nextDueOn => setV({ ...v, nextDueOn })} />
        <SelectField label="Certificate (optional)" value={v.documentId} onChange={documentId => setV({ ...v, documentId })}
          hint={!certificates.length ? 'Upload the certificate in Documents to link it here.' : undefined}
          options={[{ value: '', label: 'None' }, ...certificates.map(c => ({ value: c.id, label: c.name }))]} />
        <TextField label="Notes (optional)" value={v.notes} maxLength={1000} onChange={notes => setV({ ...v, notes })} />
      </div>
      {missingDate && <p className="text-xs text-gray-600 dark:text-gray-300">Give the date it was given, or the date of the next dose.</p>}
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!v.vaccine.trim() || missingDate || badDose} loading={save.isPending}>Save dose</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the dose.')}</p>}
    </form>
  )
}

export const EVENT_KINDS: Record<HealthEventKind, string> = {
  surgery: 'Surgery', procedure: 'Procedure', admission: 'Hospital stay', injury: 'Injury', fall: 'Fall',
}

function nights(from: string, to: string | null): string | null {
  if (!to) return null
  const n = Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000)
  return n > 0 ? `${n} ${n === 1 ? 'night' : 'nights'}` : null
}

/** Surgeries and stays (7.11): operations, procedures, hospital stays, injuries and falls, newest first. */
export function SurgeriesAndStays({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.events(personId), queryFn: () => vitaApi.events(personId) })
  const doctors = useQuery({ queryKey: vitaKeys.doctors, queryFn: vitaApi.doctors })
  const [editing, setEditing] = useState<HealthEvent | 'new' | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeEvent(personId, id), onSuccess: refresh })
  const doctorName = (id: string | null) => doctors.data?.find(d => d.id === id)?.name
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Surgeries and stays" sub="Operations, procedures, hospital stays, injuries and falls" />
      <Panel title={<><BedDouble className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />History</>}
        action={!readOnly && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}><Plus className="h-3.5 w-3.5" aria-hidden />Add an entry</Button>}>
        {editing && <EventForm personId={personId} start={editing === 'new' ? null : editing} doctors={(doctors.data ?? []).map(d => ({ id: d.id, name: d.name }))} onDone={() => { setEditing(null); refresh() }} />}
        {isLoading && <SkeletonRows rows={3} />}
        {error && <LoadFailed text="Could not load the history." onRetry={() => refetch()} />}
        {data && !data.length && !editing && <p className="text-sm text-gray-500 dark:text-gray-400">Nothing recorded. Add an operation or a hospital stay with its dates and the hospital.</p>}
        {data && data.length > 0 && (
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
            {data.map(e => (
              <li key={e.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2">
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                    {e.title || EVENT_KINDS[e.kind]}<Pill>{EVENT_KINDS[e.kind]}</Pill>
                  </p>
                  <p className="text-xs text-gray-600 dark:text-gray-300">
                    {[e.toDate && e.toDate !== e.fromDate ? `${formatDay(e.fromDate)} to ${formatDay(e.toDate)}` : formatDay(e.fromDate), nights(e.fromDate, e.toDate), e.hospital, doctorName(e.doctorId)].filter(Boolean).join(' · ')}
                  </p>
                  {e.notes && <p className="whitespace-pre-line text-xs text-gray-500 dark:text-gray-400">{e.notes}</p>}
                  {e.documentIds && e.documentIds.length > 0 && (
                    <p className="flex flex-wrap gap-2 text-xs">
                      {e.documentIds.map((id, i) => <a key={id} href={vitaApi.documentFileUrl(id)} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-brand-700 underline dark:text-brand-300"><ExternalLink className="h-3 w-3" aria-hidden />Document {i + 1}</a>)}
                    </p>
                  )}
                </div>
                {!readOnly && (
                  <span className="flex gap-1">
                    <Button size="sm" variant="ghost" aria-label={`Change ${e.title || EVENT_KINDS[e.kind]}`} onClick={() => setEditing(e)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                    <Button size="sm" variant="ghost" aria-label={`Delete ${e.title || EVENT_KINDS[e.kind]}`} onClick={() => remove.mutate(e.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
        {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete it.')}</p>}
      </Panel>
    </div>
  )
}

function EventForm({ personId, start, doctors, onDone }: { personId: string; start: HealthEvent | null; doctors: { id: string; name: string }[]; onDone: () => void }) {
  const [v, setV] = useState({
    kind: (start?.kind ?? 'surgery') as HealthEventKind, title: start?.title ?? '', fromDate: start?.fromDate ?? '', toDate: start?.toDate ?? '',
    hospital: start?.hospital ?? '', doctorId: start?.doctorId ?? '', notes: start?.notes ?? '',
  })
  const body: HealthEventInput = {
    kind: v.kind, title: v.title.trim() || null, fromDate: v.fromDate, toDate: v.toDate || null, hospital: v.hospital.trim() || null,
    doctorId: v.doctorId || null, notes: v.notes.trim() || null,
  }
  const save = useMutation({ mutationFn: () => (start ? vitaApi.updateEvent(personId, start.id, body) : vitaApi.addEvent(personId, body)), onSuccess: onDone })
  const backwards = !!v.fromDate && !!v.toDate && v.toDate < v.fromDate
  return (
    <form aria-label={start ? 'Change the entry' : 'Add a surgery or stay'} className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <SelectField label="What" value={v.kind} onChange={kind => setV({ ...v, kind })} options={(Object.keys(EVENT_KINDS) as HealthEventKind[]).map(k => ({ value: k, label: EVENT_KINDS[k] }))} />
        <TextField label="Title (optional)" value={v.title} maxLength={200} placeholder="Knee replacement" onChange={title => setV({ ...v, title })} />
        <TextField label="Hospital (optional)" value={v.hospital} maxLength={200} onChange={hospital => setV({ ...v, hospital })} />
        <TextField label="From" type="date" value={v.fromDate} onChange={fromDate => setV({ ...v, fromDate })} />
        <TextField label="To (optional)" type="date" value={v.toDate} onChange={toDate => setV({ ...v, toDate })} hint={backwards ? 'The end cannot be before the start.' : undefined} />
        <SelectField label="Doctor (optional)" value={v.doctorId} onChange={doctorId => setV({ ...v, doctorId })} options={[{ value: '', label: 'Not said' }, ...doctors.map(d => ({ value: d.id, label: d.name }))]} />
      </div>
      <TextField label="Notes (optional)" value={v.notes} maxLength={4000} onChange={notes => setV({ ...v, notes })} />
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!v.fromDate || backwards} loading={save.isPending}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save it.')}</p>}
    </form>
  )
}
