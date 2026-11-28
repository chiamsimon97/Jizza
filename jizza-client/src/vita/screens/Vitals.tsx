import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRightLeft, Pencil, Plus, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { RangeInput, RangeView, Reading, ReadingKind, TrendSide, TrendView, VitaPerson } from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { FigureLabel } from '../components/FigureLabel'
import { ReadingEntry } from '../components/ReadingEntry'
import { ChartKey, StatusBadge, TargetLabel, TrendChart, bandsFromText } from '../components/Readings'
import { errorText, formatWhen, useUnits, useVitaConfig, useVitaPeople, type Units } from '../data'
import { describeFigure } from '../labels'
import { CONTEXTS, PERIODS, READING_KINDS, kindLabel, readingContext, showReading, showValue, sourceWords } from '../readings'
import { vitaKeys } from '../queryKeys'
import { splitPeople } from '../people'

/** The red-flag rules that apply to each kind (spec 8.2). */
const RED_FLAG_KEYS: Partial<Record<ReadingKind, string[]>> = {
  bp: ['clinical.redflags.bp'],
  glucose: ['clinical.redflags.low_sugar', 'clinical.redflags.high_sugar'],
  spo2: ['clinical.redflags.spo2'],
  temperature: ['clinical.redflags.temperature'],
}

/**
 * Vitals (7.7): kind chips, a chart with the target as a band and who set it, 7/30/90 days,
 * averages (morning against evening for BP), the 7-day BP diary, the readings with their source,
 * and the add form. A reading can be corrected, deleted or moved to another person.
 */
export function Vitals({ personId, readOnly, adding, onAddDone, onOpenCard, initialKind }: {
  personId: string
  readOnly: boolean
  /** The Add menu asked for this kind of reading. */
  adding: ReadingKind | null
  onAddDone: () => void
  onOpenCard: () => void
  initialKind?: ReadingKind
}) {
  const [kind, setKind] = useState<ReadingKind>(adding ?? initialKind ?? 'bp')
  const [days, setDays] = useState<number>(30)
  const [open, setOpen] = useState(false)
  const [context, setContext] = useState<string>('')
  const shown = adding ?? (open ? kind : null)
  const close = () => { setOpen(false); onAddDone() }
  const units = useUnits()
  const ctx = kind === 'glucose' && context ? context : null
  const trend = useQuery({ queryKey: vitaKeys.trend(personId, kind, days, ctx), queryFn: () => vitaApi.trend(personId, kind, days, ctx) })
  const pickKind = (k: ReadingKind) => { setKind(k); setContext('') }
  useEffect(() => { if (adding) pickKind(adding) }, [adding])

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Vitals" sub="Blood pressure, sugar, weight, heart rate, oxygen and temperature">
        {!readOnly && !shown && <Button size="sm" onClick={() => setOpen(true)}><Plus className="h-3.5 w-3.5" aria-hidden />Add reading</Button>}
      </ViewTitle>
      {shown && <ReadingEntry key={shown} personId={personId} kind={shown} onDone={() => { close(); if (adding) setKind(adding) }} onOpenCard={onOpenCard} />}

      <div role="group" aria-label="Kind of reading" className="flex flex-wrap gap-1.5">
        {READING_KINDS.map(k => <Chip key={k.kind} on={kind === k.kind} onClick={() => pickKind(k.kind)}>{k.short}</Chip>)}
      </div>

      <Panel title={kindLabel(kind)} action={
        <div role="group" aria-label="Period" className="flex gap-1">
          {PERIODS.map(p => (
            <button key={p} type="button" aria-pressed={days === p} onClick={() => setDays(p)}
              className={`rounded-md px-2 py-0.5 text-xs ${days === p ? 'bg-gray-900 font-medium text-white dark:bg-gray-100 dark:text-gray-900' : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
              {p} days
            </button>
          ))}
        </div>
      }>
        {kind === 'glucose' && (
          <SelectField label="Show" value={context} onChange={setContext} className="max-w-xs"
            options={[{ value: '', label: 'All sugar readings' }, { value: 'before_meal', label: 'Before meals and fasting' }, { value: 'after_meal', label: 'After meals' }, { value: 'bedtime', label: 'Bedtime' }, { value: 'random', label: CONTEXTS.random }]} />
        )}
        {trend.isLoading && <SkeletonRows rows={3} />}
        {trend.error && <LoadFailed text="Could not load the chart." onRetry={() => trend.refetch()} />}
        {trend.data && <TrendPanel data={trend.data} units={units} />}
        <RedFlagRules kind={kind} />
      </Panel>

      <ReadingList personId={personId} kind={kind} days={days} readOnly={readOnly} onOpenCard={onOpenCard} />
      <Targets personId={personId} kind={kind} readOnly={readOnly} />
    </div>
  )
}

function TrendPanel({ data, units }: { data: TrendView; units: Units }) {
  const t = data.trend
  const points = t.days.flatMap(d => [...d.morning, ...d.evening])
  const r = data.range
  return (
    <div className="flex flex-col gap-3">
      {r.status === 'set'
        ? <TargetLabel source={r.source} label={r.label} needsVerification={r.needsVerification} range={r.range} />
        : <p className="text-sm font-medium text-gray-700 dark:text-gray-200">Target not set: ask your doctor</p>}
      {!t.count && <p className="text-sm text-gray-500 dark:text-gray-400">No {kindLabel(data.kind).toLowerCase()} readings in the last {data.days} days.</p>}
      {t.count > 0 && (
        <>
          <TrendChart kind={data.kind} points={points} bands={r.status === 'set' ? bandsFromText(r.range) : null} units={units}
            label={`${kindLabel(data.kind)}, ${t.count} readings in the last ${data.days} days${r.status === 'set' ? `, target ${r.range}` : ''}`} />
          <ChartKey kind={data.kind} />
          <div className="grid gap-2 sm:grid-cols-3">
            <Average title={`Average, ${data.days} days`} side={t.all} kind={data.kind} units={units} />
            {data.kind === 'bp' && <Average title="Mornings" side={t.morning} kind={data.kind} units={units} />}
            {data.kind === 'bp' && <Average title="Evenings" side={t.evening} kind={data.kind} units={units} />}
          </div>
          {data.kind === 'bp' && data.days === 7 && <Diary data={data} units={units} />}
          {data.kind === 'bp' && data.days !== 7 && <p className="text-xs text-gray-500 dark:text-gray-400">Pick 7 days for the morning and evening diary a doctor may ask for.</p>}
        </>
      )}
    </div>
  )
}

function Average({ title, side, kind, units }: { title: string; side: TrendSide; kind: ReadingKind; units: Units }) {
  return (
    <div className="rounded-xl bg-gray-50 p-3 dark:bg-gray-800/60">
      <p className="text-xs text-gray-500 dark:text-gray-400">{title}</p>
      <p className="font-mono text-lg font-semibold text-gray-900 dark:text-gray-100">
        {side.value ? showValue(kind, side.value.shown, side.value2?.shown ?? null, units) : '—'}
      </p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{side.count} {side.count === 1 ? 'reading' : 'readings'}{side.outside ? `, ${side.outside} outside range` : ''}</p>
    </div>
  )
}

/** The 7-day BP diary: morning and evening for each day. */
function Diary({ data, units }: { data: TrendView; units: Units }) {
  const cell = (pts: TrendView['trend']['days'][number]['morning']) => pts.length
    ? pts.map(p => <span key={p.id} className="mr-2 inline-flex items-center gap-1"><span className="font-mono">{showValue('bp', p.value, p.value2, units).replace(' mmHg', '')}</span>{p.status && p.status !== 'in_range' && <StatusBadge status={p.status} />}</span>)
    : <span className="text-gray-400">—</span>
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="mb-1 text-left text-xs font-medium text-gray-600 dark:text-gray-300">7-day diary (mmHg)</caption>
        <thead><tr className="text-xs text-gray-500 dark:text-gray-400"><th className="py-1 font-medium">Day</th><th className="py-1 font-medium">Morning</th><th className="py-1 font-medium">Evening</th></tr></thead>
        <tbody>
          {data.trend.days.map(d => (
            <tr key={d.date} className="border-t border-gray-100 dark:border-gray-800">
              <td className="py-1.5 text-gray-700 dark:text-gray-300">{formatDay(d.date)}</td>
              <td className="py-1.5">{cell(d.morning)}</td>
              <td className="py-1.5">{cell(d.evening)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RedFlagRules({ kind }: { kind: ReadingKind }) {
  const config = useVitaConfig()
  const keys = RED_FLAG_KEYS[kind]
  if (!keys || !config.data) return null
  const items = keys.map(k => config.data.find(i => i.key === k)).filter(Boolean)
  if (!items.length) return null
  return (
    <ul className="flex flex-col gap-1 border-t border-gray-100 pt-2 dark:border-gray-800">
      {items.map(i => i && (
        <li key={i.key} className="flex flex-wrap items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
          <span><b className="font-medium">Red flag:</b> {describeFigure(i.key, i.value)}</span>
          <FigureLabel state={i.state} label={i.label} />
        </li>
      ))}
    </ul>
  )
}

/** The readings with source, each with correct, delete and move. */
function ReadingList({ personId, kind, days, readOnly, onOpenCard }: { personId: string; kind: ReadingKind; days: number; readOnly: boolean; onOpenCard: () => void }) {
  const qc = useQueryClient()
  const units = useUnits()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.measurements(personId, kind), queryFn: () => vitaApi.readings(personId, kind) })
  const [editing, setEditing] = useState<Reading | null>(null)
  const [moving, setMoving] = useState<Reading | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeMeasurement(personId, id), onSuccess: refresh })
  const since = Date.now() - days * 86_400_000
  const rows = (data ?? []).filter(r => new Date(r.takenAt).getTime() >= since)

  return (
    <Panel title="Readings">
      {isLoading && <SkeletonRows rows={3} />}
      {error && <LoadFailed text="Could not load the readings." onRetry={() => refetch()} />}
      {editing && <ReadingEntry personId={personId} kind={editing.kind} editing={editing} onDone={() => { setEditing(null); refresh() }} onOpenCard={onOpenCard} />}
      {moving && <MoveReading personId={personId} reading={moving} onDone={() => { setMoving(null); qc.invalidateQueries({ queryKey: vitaKeys.all }) }} />}
      {data && !rows.length && <p className="text-sm text-gray-500 dark:text-gray-400">None in the last {days} days.</p>}
      {rows.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {rows.map(r => (
            <li key={r.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
              <span className="font-mono text-sm font-semibold text-gray-900 dark:text-gray-100">{showReading(r, units)}</span>
              {r.evaluation && <StatusBadge status={r.evaluation.status} outside={r.evaluation.outside} />}
              {r.pairId && <span className="text-xs text-gray-500 dark:text-gray-400">paired</span>}
              <span className="text-xs text-gray-500 dark:text-gray-400">{[formatWhen(r.takenAt), readingContext(r), sourceWords(r.source)].filter(Boolean).join(' · ')}</span>
              {r.note && <span className="w-full text-xs text-gray-600 dark:text-gray-300">{r.note}</span>}
              {!readOnly && (
                <span className="ml-auto flex gap-1">
                  <Button size="sm" variant="ghost" aria-label="Correct this reading" onClick={() => setEditing(r)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                  <Button size="sm" variant="ghost" aria-label="Move to another person" onClick={() => setMoving(r)}><ArrowRightLeft className="h-3.5 w-3.5" aria-hidden /></Button>
                  <Button size="sm" variant="ghost" aria-label="Delete this reading" onClick={() => remove.mutate(r.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the reading.')}</p>}
    </Panel>
  )
}

function MoveReading({ personId, reading, onDone }: { personId: string; reading: Reading; onDone: () => void }) {
  const units = useUnits()
  const people = useVitaPeople()
  const others = splitPeople(people.data ?? []).open.filter(p => p.id !== personId)
  const [to, setTo] = useState('')
  const move = useMutation({ mutationFn: () => vitaApi.moveMeasurement(personId, reading.id, to), onSuccess: onDone })
  return (
    <form className="flex flex-col gap-2 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); move.mutate() }}>
      <p className="text-sm text-gray-800 dark:text-gray-100">Move <b className="font-mono">{showReading(reading, units)}</b> to the person it belongs to.</p>
      {!others.length && <p className="text-sm text-gray-500 dark:text-gray-400">There is no one else in this household yet.</p>}
      {others.length > 0 && (
        <SelectField label="Move to" value={to} onChange={setTo} className="max-w-xs"
          options={[{ value: '', label: 'Pick a person' }, ...others.map((p: VitaPerson) => ({ value: p.id, label: p.displayName }))]} />
      )}
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!to} loading={move.isPending}>Move reading</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {move.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(move.error, 'Could not move the reading.')}</p>}
    </form>
  )
}

/** The person's targets for this kind: the doctor's target, their own range, or Vita's default. */
function Targets({ personId, kind, readOnly }: { personId: string; kind: ReadingKind; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.ranges(personId), queryFn: () => vitaApi.ranges(personId) })
  const [editing, setEditing] = useState<{ slot: ReadingSlot; setBy: 'user' | 'doctor' } | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeRange(personId, id), onSuccess: refresh })
  const slots = (data ?? []).filter((s): s is ReadingSlot => s.kind === kind)
  if (kind === 'weight' && !isLoading) {
    return <Panel title="Target"><p className="text-sm text-gray-500 dark:text-gray-400">No target: weight has no range in Vita.</p></Panel>
  }
  return (
    <Panel title="Targets">
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Vita uses the doctor’s target first, then your own range, then its default. It never sets a target by itself.</p>
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the targets." onRetry={() => refetch()} />}
      {editing && <RangeForm personId={personId} slot={editing.slot} setBy={editing.setBy} onDone={() => { setEditing(null); refresh() }} />}
      <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {slots.map(s => (
          <li key={`${s.kind}-${s.context}-${s.setting}`} className="flex flex-col gap-1 py-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-gray-800 dark:text-gray-100">{slotName(s)}</span>
              {s.status === 'set'
                ? <TargetLabel source={s.source} label={s.label} needsVerification={s.needsVerification} range={s.range} />
                : <span className="text-xs font-medium text-gray-600 dark:text-gray-300">Not set: ask your doctor</span>}
            </div>
            {!readOnly && (
              <div className="flex flex-wrap gap-1.5">
                <Button size="sm" variant="secondary" onClick={() => setEditing({ slot: s, setBy: 'doctor' })}>{s.doctorTarget ? 'Change doctor’s target' : 'Add doctor’s target'}</Button>
                <Button size="sm" variant="ghost" onClick={() => setEditing({ slot: s, setBy: 'user' })}>{s.own ? 'Change own range' : 'Set own range'}</Button>
                {s.doctorTarget && <Button size="sm" variant="ghost" onClick={() => remove.mutate(s.doctorTarget!.id)}>Remove doctor’s target</Button>}
                {s.own && <Button size="sm" variant="ghost" onClick={() => remove.mutate(s.own!.id)}>Remove own range</Button>}
              </div>
            )}
          </li>
        ))}
      </ul>
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the target.')}</p>}
    </Panel>
  )
}

/** A target row of a reading kind (lab tests' rows carry labTestCode and no kind). */
type ReadingSlot = RangeView & { kind: ReadingKind }

function slotName(s: ReadingSlot): string {
  if (s.kind === 'bp') return s.setting === 'clinic' ? 'At the clinic' : 'At home'
  if (s.kind === 'glucose') return s.context === 'after_meal' ? 'After meals' : 'Before meals and fasting'
  return kindLabel(s.kind)
}

function RangeForm({ personId, slot, setBy, onDone }: { personId: string; slot: ReadingSlot; setBy: 'user' | 'doctor'; onDone: () => void }) {
  const start = setBy === 'doctor' ? slot.doctorTarget : slot.own
  const doctors = useQuery({ queryKey: vitaKeys.doctors, queryFn: vitaApi.doctors, enabled: setBy === 'doctor' })
  const n = (v: number | null | undefined) => (v == null ? '' : String(v))
  const [v, setV] = useState({
    low: n(start?.low), high: n(start?.high), low2: n(start?.low2), high2: n(start?.high2),
    doctorId: start?.doctorId ?? '', setOn: start?.setOn ?? '', note: start?.note ?? '',
  })
  const bp = slot.kind === 'bp'
  /** Targets are kept in the canonical unit, whatever Settings shows readings in. */
  const unit = ({ bp: 'mmHg', glucose: 'mg/dL', heart_rate: 'bpm', spo2: '%', temperature: '°C', weight: 'kg' } as const)[slot.kind]
  const body = (): RangeInput => ({
    kind: slot.kind, context: slot.context, setting: slot.setting, setBy,
    low: v.low || null, high: v.high || null,
    ...(bp ? { low2: v.low2 || null, high2: v.high2 || null } : {}),
    ...(setBy === 'doctor' ? { doctorId: v.doctorId || null, setOn: v.setOn || null } : {}),
    note: v.note.trim() || null,
  })
  const save = useMutation({ mutationFn: () => vitaApi.putRange(personId, body()), onSuccess: onDone })
  const can = (v.low || v.high || v.low2 || v.high2) && (setBy === 'user' || v.doctorId)
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <p className="text-sm font-medium text-gray-800 dark:text-gray-100">{setBy === 'doctor' ? 'The doctor’s target' : 'Your own range'} · {slotName(slot)}</p>
      <div className={`grid gap-3 ${bp ? 'sm:grid-cols-4' : 'sm:grid-cols-2'}`}>
        <TextField label={bp ? 'Systolic from (optional)' : `From, ${unit} (optional)`} value={v.low} maxLength={6} onChange={low => setV({ ...v, low })} />
        <TextField label={bp ? 'Systolic up to (mmHg)' : `Up to, ${unit}`} value={v.high} maxLength={6} onChange={high => setV({ ...v, high })} />
        {bp && <TextField label="Diastolic from (optional)" value={v.low2} maxLength={6} onChange={low2 => setV({ ...v, low2 })} />}
        {bp && <TextField label="Diastolic up to (mmHg)" value={v.high2} maxLength={6} onChange={high2 => setV({ ...v, high2 })} />}
      </div>
      {setBy === 'doctor' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <SelectField label="Doctor" value={v.doctorId} onChange={doctorId => setV({ ...v, doctorId })}
            hint={doctors.data && !doctors.data.length ? 'Add the doctor under Doctors and visits first.' : undefined}
            options={[{ value: '', label: 'Pick the doctor' }, ...(doctors.data ?? []).map(d => ({ value: d.id, label: d.name }))]} />
          <TextField label="Set on" type="date" value={v.setOn} onChange={setOn => setV({ ...v, setOn })} />
        </div>
      )}
      <TextField label="Note (optional)" value={v.note} maxLength={300} onChange={note => setV({ ...v, note })} />
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!can} loading={save.isPending}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the target.')}</p>}
    </form>
  )
}
