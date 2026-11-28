import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, Pause, Pencil, Play, Plus, RefreshCw, Square, X } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SlideOver } from '@/components/ui/SlideOver'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type {
  AdherenceResult, Condition, Doctor, DoseUnit, Medicine, MedicineChange, MedicineForm as Form, MedicineInput, MedicineSystem, ScheduleInput, WithFood,
} from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { DoseStatusPill } from '../components/DoseSlotCard'
import { errorText } from '../data'
import { DOSE_UNITS, FORMS, GROUPS, SYSTEMS, WITH_FOOD, clock, groupOf, localDay, scheduleWords } from '../medicines'
import { vitaKeys } from '../queryKeys'
import { useDoctors } from '../share'

export const MEDICINES_PROMISE = 'Vita keeps this list and reminds. It never changes a dose or suggests starting or stopping a medicine.'

/** "86% taken" or "No doses due" from the server's result; the figures are the server's. */
export function adherenceWords(r: AdherenceResult | undefined): string {
  if (!r || !r.due) return 'No doses due yet'
  return r.percent == null ? `${r.taken} of ${r.due} taken` : `${r.percent}% taken (${r.taken} of ${r.due})`
}

/**
 * Medicines (7.6): grouped Active, As needed, Paused, Stopped and Supplies. Each row shows its
 * schedule, condition, stock and 30-day adherence; a medicine opens to its changes and the last
 * two weeks of doses, with Change dose or times, Pause, Stop and Refill.
 */
export function Medicines({ personId, readOnly, adding, onAddDone }: { personId: string; readOnly: boolean; adding: boolean; onAddDone: () => void }) {
  const from = localDay(-30)
  const to = localDay(-1)
  const meds = useQuery({ queryKey: vitaKeys.medicines(personId), queryFn: () => vitaApi.medicines(personId) })
  const conditions = useQuery({ queryKey: vitaKeys.conditions(personId), queryFn: () => vitaApi.conditions(personId) })
  const doctors = useDoctors()
  const adherence = useQuery({ queryKey: vitaKeys.adherence(personId, from, to), queryFn: () => vitaApi.adherence(personId, from, to) })
  const [panel, setPanel] = useState<Medicine | 'new' | null>(null)
  const open = panel ?? (adding ? 'new' : null)
  const close = () => { setPanel(null); onAddDone() }
  const list = meds.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Medicines" sub={MEDICINES_PROMISE}>
        {!readOnly && <Button size="sm" onClick={() => setPanel('new')}><Plus className="h-3.5 w-3.5" aria-hidden />Add medicine</Button>}
      </ViewTitle>
      {meds.isLoading && <SkeletonRows rows={3} />}
      {meds.error && <LoadFailed text="Could not load the medicines." onRetry={() => meds.refetch()} />}
      {meds.data && !list.length && (
        <Panel title="No medicines yet">
          <p className="text-sm text-gray-500 dark:text-gray-400">Add each medicine with its times. Vita makes a card for each time of day on Home, with “All taken”, and reminds on the phone.</p>
        </Panel>
      )}
      {GROUPS.map(g => {
        const rows = list.filter(m => groupOf(m) === g.id)
        if (!rows.length) return null
        return (
          <Panel key={g.id} title={`${g.title} (${rows.length})`}>
            <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
              {rows.map(m => (
                <MedicineRow key={m.id} personId={personId} m={m} readOnly={readOnly} conditions={conditions.data ?? []} doctors={doctors.data ?? []}
                  adherence={adherence.data?.medicines.find(a => a.medicineId === m.id)?.result} onEdit={() => setPanel(m)} />
              ))}
            </ul>
          </Panel>
        )
      })}
      <SlideOver open={!!open} title={open === 'new' ? 'Add a medicine' : 'Edit medicine details'} wide onClose={close}
        description={open === 'new' ? 'Name, strength and times. The rest is optional.' : 'To change the dose or the times, use Change dose or times.'}>
        {open && <MedicineFormPanel personId={personId} start={open === 'new' ? null : open} conditions={conditions.data ?? []} doctors={doctors.data ?? []} onDone={close} />}
      </SlideOver>
    </div>
  )
}

function MedicineRow({ personId, m, readOnly, conditions, doctors, adherence, onEdit }: {
  personId: string; m: Medicine; readOnly: boolean; conditions: Condition[]; doctors: Doctor[]; adherence?: AdherenceResult; onEdit: () => void
}) {
  const qc = useQueryClient()
  const [expanded, setExpanded] = useState(false)
  const [action, setAction] = useState<'change' | 'stop' | 'refill' | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const pause = useMutation({ mutationFn: () => vitaApi.pauseMedicine(personId, m.id), onSuccess: refresh })
  const resume = useMutation({ mutationFn: () => vitaApi.resumeMedicine(personId, m.id), onSuccess: refresh })
  const condition = conditions.find(c => c.id === m.conditionId)
  const names = [m.brandName, m.genericName].filter(Boolean)
  const live = m.status !== 'stopped'
  const error = pause.error ?? resume.error

  return (
    <li className="flex flex-col gap-2 py-2.5">
      <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
        <button type="button" aria-expanded={expanded} onClick={() => setExpanded(!expanded)} className="mt-0.5 text-gray-400" aria-label={expanded ? `Close ${m.name}` : `Open ${m.name}`}>
          {expanded ? <ChevronDown className="h-4 w-4" aria-hidden /> : <ChevronRight className="h-4 w-4" aria-hidden />}
        </button>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
            {m.name}{m.strength ? ` ${m.strength}` : ''}
            {m.status === 'paused' && <Pill tone="warn"><Pause className="h-3 w-3" aria-hidden />Paused</Pill>}
            {m.status === 'stopped' && <Pill><Square className="h-3 w-3" aria-hidden />Stopped{m.endDate ? ` ${formatDay(m.endDate)}` : ''}</Pill>}
            {m.refillDue && <Pill tone="warn"><RefreshCw className="h-3 w-3" aria-hidden />Refill due</Pill>}
          </p>
          {names.length > 0 && <p className="text-xs text-gray-500 dark:text-gray-400">{names.join(' · ')}</p>}
          <p className="text-xs text-gray-700 dark:text-gray-300">{m.asNeeded ? 'As needed' : m.form === 'supply' ? 'Supply, no doses' : scheduleWords(m.schedule)}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {[condition ? `For ${condition.name}` : null,
              m.trackStock && m.stockUnits != null ? `${Number(m.stockUnits)} left` : null,
              m.schedule && live ? `Last 30 days: ${adherenceWords(adherence)}` : null].filter(Boolean).join(' · ')}
          </p>
        </div>
        {!readOnly && live && (
          <span className="flex flex-wrap gap-1">
            {m.schedule && <Button size="sm" variant="secondary" onClick={() => setAction(action === 'change' ? null : 'change')}>Change dose or times</Button>}
            {m.trackStock && <Button size="sm" variant="ghost" onClick={() => setAction(action === 'refill' ? null : 'refill')}>Refill</Button>}
            {m.status === 'active' && <Button size="sm" variant="ghost" loading={pause.isPending} onClick={() => pause.mutate()}><Pause className="h-3.5 w-3.5" aria-hidden />Pause</Button>}
            {m.status === 'paused' && <Button size="sm" variant="ghost" loading={resume.isPending} onClick={() => resume.mutate()}><Play className="h-3.5 w-3.5" aria-hidden />Resume</Button>}
            <Button size="sm" variant="ghost" onClick={() => setAction(action === 'stop' ? null : 'stop')}><Square className="h-3.5 w-3.5" aria-hidden />Stop</Button>
            <Button size="sm" variant="ghost" aria-label={`Edit ${m.name}`} onClick={onEdit}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
          </span>
        )}
      </div>
      {action === 'change' && <ChangeForm personId={personId} m={m} doctors={doctors} onDone={() => { setAction(null); refresh() }} />}
      {action === 'stop' && <StopForm personId={personId} m={m} onDone={() => { setAction(null); refresh() }} />}
      {action === 'refill' && <RefillForm personId={personId} m={m} onDone={() => { setAction(null); refresh() }} />}
      {error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(error, 'Could not save that.')}</p>}
      {expanded && <MedicineDetail personId={personId} m={m} doctors={doctors} />}
    </li>
  )
}

const EVENT_WORDS: Record<string, string> = {
  started: 'Started', dose_changed: 'Dose or times changed', paused: 'Paused', resumed: 'Resumed', stopped: 'Stopped', refilled: 'Refilled',
}

/** Its changes and a two-week strip of doses. */
function MedicineDetail({ personId, m, doctors }: { personId: string; m: Medicine; doctors: Doctor[] }) {
  const events = useQuery({ queryKey: vitaKeys.medicineEvents(personId, m.id), queryFn: () => vitaApi.medicineEvents(personId, m.id) })
  const from = localDay(-13)
  const to = localDay(0)
  const doses = useQuery({ queryKey: vitaKeys.doses(personId, from, to), queryFn: () => vitaApi.doses(personId, from, to), enabled: !!m.schedule })
  const mine = (doses.data?.slots ?? []).flatMap(s => s.doses.filter(d => d.medicineId === m.id).map(d => ({ ...d, date: s.localDate, time: s.localTime })))
  const days = [...new Set(mine.map(d => d.date))].sort()
  return (
    <div className="ml-7 flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50">
      {m.schedule && (
        <section aria-label="Doses, last two weeks">
          <h4 className="mb-1 text-xs font-semibold text-gray-600 dark:text-gray-300">Doses, last two weeks</h4>
          {doses.isLoading && <SkeletonRows rows={1} />}
          {doses.data && !mine.length && <p className="text-xs text-gray-500 dark:text-gray-400">No doses in these two weeks.</p>}
          <ol className="flex flex-wrap gap-1.5">
            {days.map(day => (
              <li key={day} className="flex flex-col items-center gap-0.5 rounded-lg bg-white px-1.5 py-1 text-[11px] dark:bg-gray-900">
                <span className="text-gray-500 dark:text-gray-400">{formatDay(day).replace(/ \d{4}$/, '')}</span>
                {mine.filter(d => d.date === day).map(d => <span key={d.id} title={`${clock(d.time)}: ${d.status}`}><DoseStatusPill status={d.status} late={d.late} /></span>)}
              </li>
            ))}
          </ol>
        </section>
      )}
      <section aria-label="Changes">
        <h4 className="mb-1 text-xs font-semibold text-gray-600 dark:text-gray-300">Changes</h4>
        {events.isLoading && <SkeletonRows rows={1} />}
        {events.error && <LoadFailed text="Could not load the changes." onRetry={() => events.refetch()} />}
        <ul className="flex flex-col gap-1">
          {(events.data ?? []).map(e => (
            <li key={e.id} className="text-xs text-gray-700 dark:text-gray-200">
              <b className="font-medium">{formatDay(e.onDate)}</b> · {EVENT_WORDS[e.kind] ?? e.kind}
              {e.fromText || e.toText ? `: ${[e.fromText, e.toText].filter(Boolean).join(' → ')}` : ''}
              {e.doctorId ? ` · ${doctors.find(d => d.id === e.doctorId)?.name ?? 'a doctor'}` : ''}
              {e.reason ? ` · ${e.reason}` : ''}
            </li>
          ))}
        </ul>
      </section>
      {m.notes && <p className="text-xs text-gray-600 dark:text-gray-300">{m.notes}</p>}
    </div>
  )
}

/** Times as a list of HH:mm boxes, one to twelve. */
export function TimesField({ times, onChange }: { times: string[]; onChange: (t: string[]) => void }) {
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-xs font-medium text-gray-700 dark:text-gray-300">Times</legend>
      <div className="flex flex-wrap items-center gap-2">
        {times.map((t, i) => (
          <span key={i} className="inline-flex items-center gap-1">
            <input type="time" aria-label={`Time ${i + 1}`} value={t} onChange={e => onChange(times.map((x, j) => (j === i ? e.target.value : x)))}
              className="rounded-lg border border-gray-300 bg-white px-2 py-1 text-sm dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100" />
            {times.length > 1 && <button type="button" aria-label={`Remove time ${i + 1}`} onClick={() => onChange(times.filter((_, j) => j !== i))} className="text-gray-400 hover:text-gray-600"><X className="h-3.5 w-3.5" aria-hidden /></button>}
          </span>
        ))}
        {times.length < 12 && <Button size="sm" variant="ghost" type="button" onClick={() => onChange([...times, '20:00'])}><Plus className="h-3.5 w-3.5" aria-hidden />Add a time</Button>}
      </div>
    </fieldset>
  )
}

const WEEKDAYS = [['MO', 'Mon'], ['TU', 'Tue'], ['WE', 'Wed'], ['TH', 'Thu'], ['FR', 'Fri'], ['SA', 'Sat'], ['SU', 'Sun']] as const

export function DaysField({ rule, onChange }: { rule: string; onChange: (r: string) => void }) {
  const picked = rule.match(/BYDAY=([A-Z,]+)/)?.[1].split(',') ?? []
  const daily = !picked.length
  const set = (days: string[]) => onChange(days.length ? `FREQ=WEEKLY;BYDAY=${WEEKDAYS.map(([d]) => d).filter(d => days.includes(d)).join(',')}` : 'FREQ=DAILY')
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-xs font-medium text-gray-700 dark:text-gray-300">Days</legend>
      <div className="flex flex-wrap gap-1.5">
        <Chip on={daily} onClick={() => onChange('FREQ=DAILY')}>Every day</Chip>
        {WEEKDAYS.map(([d, label]) => <Chip key={d} on={picked.includes(d)} onClick={() => set(picked.includes(d) ? picked.filter(x => x !== d) : [...picked, d])}>{label}</Chip>)}
      </div>
    </fieldset>
  )
}

function MedicineFormPanel({ personId, start, conditions, doctors, onDone }: {
  personId: string; start: Medicine | null; conditions: Condition[]; doctors: Doctor[]; onDone: () => void
}) {
  const s = start?.schedule
  const [v, setV] = useState({
    name: start?.name ?? '', strength: start?.strength ?? '', form: (start?.form ?? 'tablet') as Form, asNeeded: start?.asNeeded ?? false,
    times: s?.times ?? ['08:00'], recurrence: s?.recurrence ?? 'FREQ=DAILY', doseUnits: String(s?.doseUnits ?? 1), doseUnit: (s?.doseUnit ?? 'tablet') as DoseUnit,
    withFood: (s?.withFood ?? 'any') as WithFood,
    brandName: start?.brandName ?? '', genericName: start?.genericName ?? '', system: (start?.system ?? 'allopathy') as MedicineSystem,
    conditionId: start?.conditionId ?? '', prescribedBy: start?.prescribedBy ?? '', startDate: start?.startDate ?? '', endDate: start?.endDate ?? '',
    trackStock: start?.trackStock ?? false, stockUnits: start?.stockUnits == null ? '' : String(start.stockUnits),
    unitsPerPack: start?.unitsPerPack == null ? '' : String(start.unitsPerPack), refillLeadDays: start?.refillLeadDays == null ? '' : String(start.refillLeadDays),
    notes: start?.notes ?? '',
  })
  const set = (patch: Partial<typeof v>) => setV(x => ({ ...x, ...patch }))
  const noDoses = v.asNeeded || v.form === 'supply'
  const num = (t: string) => (t.trim() === '' ? null : Number(t))
  const body = (): MedicineInput => ({
    name: v.name.trim(), strength: v.strength.trim() || null, form: v.form, asNeeded: v.asNeeded,
    brandName: v.brandName.trim() || null, genericName: v.genericName.trim() || null, system: v.system,
    conditionId: v.conditionId || null, prescribedBy: v.prescribedBy || null,
    ...(v.startDate || start ? { startDate: v.startDate || null } : {}), endDate: v.endDate || null,
    trackStock: v.trackStock, stockUnits: num(v.stockUnits), unitsPerPack: num(v.unitsPerPack), refillLeadDays: num(v.refillLeadDays),
    notes: v.notes.trim() || null,
    ...(start ? {} : { schedule: noDoses ? null : { times: v.times, recurrence: v.recurrence, doseUnits: Number(v.doseUnits), doseUnit: v.doseUnit, withFood: v.withFood } }),
  })
  const save = useMutation({ mutationFn: () => (start ? vitaApi.updateMedicine(personId, start.id, body()) : vitaApi.addMedicine(personId, body())), onSuccess: onDone })

  return (
    <form className="flex flex-col gap-4" aria-label={start ? 'Edit medicine details' : 'Add a medicine'} onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField className="sm:col-span-2" label="Name" value={v.name} maxLength={120} placeholder="Metformin" onChange={name => set({ name })} />
        <TextField label="Strength (optional)" value={v.strength} maxLength={40} placeholder="500 mg" onChange={strength => set({ strength })} />
        <SelectField label="Form" value={v.form} onChange={form => set({ form })} options={(Object.keys(FORMS) as Form[]).map(f => ({ value: f, label: FORMS[f] }))} />
      </div>
      {v.form !== 'supply' && <CheckField label="Taken only when needed (no fixed times)" checked={v.asNeeded} onChange={asNeeded => set({ asNeeded })} />}
      {!noDoses && !start && (
        <div className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50">
          <TimesField times={v.times} onChange={times => set({ times })} />
          <DaysField rule={v.recurrence} onChange={recurrence => set({ recurrence })} />
          <div className="grid gap-3 sm:grid-cols-3">
            <TextField label="How much each time" value={v.doseUnits} maxLength={6} onChange={doseUnits => set({ doseUnits })} />
            <SelectField label="Unit" value={v.doseUnit} onChange={doseUnit => set({ doseUnit })} options={(Object.keys(DOSE_UNITS) as DoseUnit[]).map(u => ({ value: u, label: DOSE_UNITS[u][1] }))} />
            <SelectField label="Food" value={v.withFood} onChange={withFood => set({ withFood })} options={(Object.keys(WITH_FOOD) as WithFood[]).map(f => ({ value: f, label: WITH_FOOD[f] }))} />
          </div>
        </div>
      )}
      <details className="rounded-xl border border-gray-200 p-3 dark:border-gray-800" open={!!start}>
        <summary className="cursor-pointer text-sm font-medium text-gray-700 dark:text-gray-200">More details (optional)</summary>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <TextField label="Brand name" value={v.brandName} maxLength={120} onChange={brandName => set({ brandName })} />
          <TextField label="Generic name" value={v.genericName} maxLength={120} onChange={genericName => set({ genericName })} />
          <SelectField label="System of medicine" value={v.system} onChange={system => set({ system })} options={(Object.keys(SYSTEMS) as MedicineSystem[]).map(x => ({ value: x, label: SYSTEMS[x] }))} />
          <SelectField label="For condition" value={v.conditionId} onChange={conditionId => set({ conditionId })}
            options={[{ value: '', label: 'Not said' }, ...conditions.map(c => ({ value: c.id, label: c.name }))]} />
          <SelectField label="Prescribed by" value={v.prescribedBy} onChange={prescribedBy => set({ prescribedBy })}
            options={[{ value: '', label: 'Not said' }, ...doctors.map(d => ({ value: d.id, label: d.name }))]} />
          <TextField label="Started on" type="date" value={v.startDate} onChange={startDate => set({ startDate })} />
          <TextField label="Ends on (for a course)" type="date" value={v.endDate} onChange={endDate => set({ endDate })} />
          <div className="flex flex-col gap-2 sm:col-span-2">
            <CheckField label="Track stock and remind before it runs out" checked={v.trackStock} onChange={trackStock => set({ trackStock })} />
            {v.trackStock && (
              <div className="grid gap-3 sm:grid-cols-3">
                <TextField label="Units in hand" value={v.stockUnits} maxLength={8} onChange={stockUnits => set({ stockUnits })} />
                <TextField label="Units in a pack" value={v.unitsPerPack} maxLength={6} onChange={unitsPerPack => set({ unitsPerPack })} />
                <TextField label="Remind this many days before" value={v.refillLeadDays} maxLength={3} onChange={refillLeadDays => set({ refillLeadDays })} />
              </div>
            )}
          </div>
          <TextField className="sm:col-span-2" label="Notes" value={v.notes} maxLength={1000} onChange={notes => set({ notes })} />
        </div>
      </details>
      <div className="flex gap-2">
        <Button type="submit" size="sm" loading={save.isPending} disabled={!v.name.trim() || (!noDoses && !start && !v.times.length)}>{start ? 'Save details' : 'Add medicine'}</Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the medicine.')}</p>}
    </form>
  )
}

/** Change dose or times (7.6): new strength or times, from which date, which doctor; the old schedule stays in the history. */
function ChangeForm({ personId, m, doctors, onDone }: { personId: string; m: Medicine; doctors: Doctor[]; onDone: () => void }) {
  const s = m.schedule!
  const [v, setV] = useState({
    strength: m.strength ?? '', times: s.times, recurrence: s.recurrence, doseUnits: String(s.doseUnits), doseUnit: s.doseUnit, withFood: s.withFood,
    fromDate: localDay(0), doctorId: m.prescribedBy ?? '', reason: '',
  })
  const set = (patch: Partial<typeof v>) => setV(x => ({ ...x, ...patch }))
  const body = (): MedicineChange => {
    const schedule: ScheduleInput = { times: v.times, recurrence: v.recurrence, doseUnits: Number(v.doseUnits), doseUnit: v.doseUnit, withFood: v.withFood }
    return { fromDate: v.fromDate || null, strength: v.strength.trim() || null, schedule, doctorId: v.doctorId || null, reason: v.reason.trim() || null }
  }
  const save = useMutation({ mutationFn: () => vitaApi.changeMedicine(personId, m.id, body()), onSuccess: onDone })
  return (
    <form className="ml-7 flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={`Change ${m.name}`} onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <p className="text-xs text-gray-600 dark:text-gray-300">Now: {m.strength ? `${m.strength}, ` : ''}{scheduleWords(s)}. The old schedule stays in the history.</p>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="New strength" value={v.strength} maxLength={40} onChange={strength => set({ strength })} />
        <TextField label="How much each time" value={v.doseUnits} maxLength={6} onChange={doseUnits => set({ doseUnits })} />
        <SelectField label="Food" value={v.withFood} onChange={withFood => set({ withFood })} options={(Object.keys(WITH_FOOD) as WithFood[]).map(f => ({ value: f, label: WITH_FOOD[f] }))} />
      </div>
      <TimesField times={v.times} onChange={times => set({ times })} />
      <DaysField rule={v.recurrence} onChange={recurrence => set({ recurrence })} />
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="From" type="date" value={v.fromDate} onChange={fromDate => set({ fromDate })} />
        <SelectField label="Which doctor" value={v.doctorId} onChange={doctorId => set({ doctorId })}
          options={[{ value: '', label: 'Not said' }, ...doctors.map(d => ({ value: d.id, label: d.name }))]} />
        <TextField label="Reason (optional)" value={v.reason} maxLength={500} onChange={reason => set({ reason })} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending}>Save change</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the change.')}</p>}
    </form>
  )
}

function StopForm({ personId, m, onDone }: { personId: string; m: Medicine; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const stop = useMutation({ mutationFn: () => vitaApi.stopMedicine(personId, m.id, reason.trim() || null), onSuccess: onDone })
  return (
    <form className="ml-7 flex flex-col gap-2 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={`Stop ${m.name}`} onSubmit={e => { e.preventDefault(); stop.mutate() }}>
      <p className="text-sm text-gray-800 dark:text-gray-100">Stop {m.name} from today? Its reminders end and it moves to Stopped. Stop a medicine only when the doctor says so.</p>
      <TextField label="Reason (optional)" value={reason} maxLength={500} placeholder="Doctor stopped it at the visit" onChange={setReason} />
      <div className="flex gap-2">
        <Button size="sm" variant="danger" type="submit" loading={stop.isPending}>Stop it</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {stop.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(stop.error, 'Could not stop the medicine.')}</p>}
    </form>
  )
}

function RefillForm({ personId, m, onDone }: { personId: string; m: Medicine; onDone: () => void }) {
  const [units, setUnits] = useState(m.unitsPerPack == null ? '' : String(m.unitsPerPack))
  const refill = useMutation({ mutationFn: () => vitaApi.refillMedicine(personId, m.id, units.trim() ? Number(units) : null), onSuccess: onDone })
  return (
    <form className="ml-7 flex flex-wrap items-end gap-2 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={`Refill ${m.name}`} onSubmit={e => { e.preventDefault(); refill.mutate() }}>
      <TextField label="Units added" value={units} maxLength={8} onChange={setUnits} />
      <Button size="sm" type="submit" loading={refill.isPending} disabled={!units.trim() && m.unitsPerPack == null}>Save refill</Button>
      <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      {refill.error && <p role="alert" className="w-full text-sm text-red-600 dark:text-red-400">{errorText(refill.error, 'Could not save the refill.')}</p>}
    </form>
  )
}
