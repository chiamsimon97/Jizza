import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, ClipboardCheck, IdCard, Pill as PillIcon, Plus, Scale, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Meter, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { Sparkline } from '@/portfolio/components/Sparkline'
import type { Measurement } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText, formatWhen, showWeight, useUnits } from '../data'
import { BLOOD_GROUPS } from '../labels'
import { vitaKeys } from '../queryKeys'
import { StatusChip } from './Conditions'

/**
 * Home (7.2) for one person. Red flags and today's medicine slots arrive with the medicines and
 * readings update; until then Home says so, keeps Quick start in reach, and shows weight.
 */
export function Home({ personId, personName, readOnly, onOpen, addWeight, onAddWeightDone }: {
  personId: string
  personName: string
  readOnly: boolean
  onOpen: (view: string) => void
  /** The Add menu asked for a weight. */
  addWeight: boolean
  onAddWeightDone: () => void
}) {
  const qs = useQuery({ queryKey: vitaKeys.quickStart(personId), queryFn: () => vitaApi.quickStart(personId) })
  const conditions = useQuery({ queryKey: vitaKeys.conditions(personId), queryFn: () => vitaApi.conditions(personId) })
  const card = useQuery({ queryKey: vitaKeys.card(personId), queryFn: () => vitaApi.emergencyCard(personId) })
  const progress = qs.data?.progress
  const started = progress && (progress.completed.length + progress.skipped.length) > 0

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title={`Today for ${personName === 'You' ? 'you' : personName}`} sub="Medicines, readings and what is coming up" />

      {qs.isLoading && <SkeletonRows rows={2} />}
      {progress && !progress.done && !readOnly && (
        <Panel title={<><ClipboardCheck className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />{started ? 'Carry on with Quick start' : 'Get started'}</>}>
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {started
              ? `${progress.completed.length + progress.skipped.length} of ${progress.total} steps done. Pick up where you left off.`
              : `Answer eight short questions and Vita sets up ${personName === 'You' ? 'your' : `${personName}’s`} emergency card and record. You can skip any step and come back later.`}
          </p>
          {started && <Meter pct={((progress.completed.length + progress.skipped.length) / progress.total) * 100} label="Quick start progress" className="bg-[rgb(var(--agent-health))]" />}
          <div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={() => onOpen('quick-start')}>{started ? 'Continue Quick start' : 'Start Quick start'}</Button>
          </div>
        </Panel>
      )}

      <Panel title={<><PillIcon className="h-4 w-4 text-gray-400" aria-hidden />Today’s medicines</>}>
        <p className="text-sm text-gray-500 dark:text-gray-400">Medicines come with the next update: one card per time slot with “All taken”, and a reminder on the phone.</p>
      </Panel>

      <div className="grid gap-4 md:grid-cols-2">
        <Weight personId={personId} readOnly={readOnly} adding={addWeight} onAddDone={onAddWeightDone} />
        <Panel title={<><CalendarClock className="h-4 w-4 text-gray-400" aria-hidden />Coming up</>}>
          <p className="text-sm text-gray-500 dark:text-gray-400">Visits, refills, renewals and checks that are due show here once doctors, medicines and reports are added.</p>
        </Panel>
      </div>

      <Panel title="Conditions" action={<Button size="sm" variant="secondary" onClick={() => onOpen('conditions')}>Open</Button>}>
        {conditions.isLoading && <SkeletonRows rows={1} />}
        {conditions.error && <LoadFailed text="Could not load the conditions." onRetry={() => conditions.refetch()} />}
        {conditions.data && !conditions.data.length && <p className="text-sm text-gray-500 dark:text-gray-400">None recorded yet.</p>}
        {conditions.data && conditions.data.length > 0 && (
          <ul className="flex flex-col gap-1.5">
            {conditions.data.map(c => <li key={c.id} className="flex flex-wrap items-center gap-2 text-sm text-gray-800 dark:text-gray-200">{c.name}<StatusChip status={c.status} /></li>)}
          </ul>
        )}
      </Panel>

      <Panel title={<><IdCard className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />Emergency card</>}
        action={<Button size="sm" variant="secondary" onClick={() => onOpen('emergency')}>Open card</Button>}>
        {card.isLoading && <SkeletonRows rows={1} />}
        {card.error && <LoadFailed text="Could not load the emergency card." onRetry={() => card.refetch()} />}
        {card.data && (
          <p className="text-sm text-gray-700 dark:text-gray-300">
            Blood group <b className="font-mono">{card.data.bloodGroup ? BLOOD_GROUPS[card.data.bloodGroup] : 'not set'}</b>
            {' · '}{card.data.allergies.length ? `${card.data.allergies.length} ${card.data.allergies.length === 1 ? 'allergy' : 'allergies'}` : card.data.allergiesNoneKnown ? 'no known allergies' : 'allergies not recorded'}
            {' · '}{card.data.emergencyContacts.length ? `${card.data.emergencyContacts.length} ${card.data.emergencyContacts.length === 1 ? 'contact' : 'contacts'}` : 'no contacts yet'}
          </p>
        )}
      </Panel>
    </div>
  )
}

function Weight({ personId, readOnly, adding, onAddDone }: { personId: string; readOnly: boolean; adding: boolean; onAddDone: () => void }) {
  const qc = useQueryClient()
  const units = useUnits()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.measurements(personId, 'weight'), queryFn: () => vitaApi.measurements(personId, 'weight') })
  const [open, setOpen] = useState(false)
  const show = open || adding
  const close = () => { setOpen(false); onAddDone() }
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeMeasurement(personId, id), onSuccess: refresh })
  const latest = data?.[0]
  const series = [...(data ?? [])].reverse().slice(-12)

  return (
    <Panel title={<><Scale className="h-4 w-4 text-gray-400" aria-hidden />Latest readings</>}
      action={!readOnly && !show && <Button size="sm" variant="secondary" onClick={() => setOpen(true)}><Plus className="h-3.5 w-3.5" aria-hidden />Add weight</Button>}>
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the readings." onRetry={() => refetch()} />}
      {data && !latest && !show && <p className="text-sm text-gray-500 dark:text-gray-400">No readings yet. Add a weight now; blood pressure, sugar and pulse come with the next update.</p>}
      {latest && (
        <div className="flex flex-wrap items-end justify-between gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/60">
          <div>
            <p className="text-xs text-gray-500 dark:text-gray-400">Weight</p>
            <p className="font-mono text-2xl font-semibold text-gray-900 dark:text-gray-100">{showWeight(latest.value, units.weight)}</p>
            <p className="text-xs text-gray-500 dark:text-gray-400">{formatWhen(latest.takenAt)}</p>
            <p className="text-xs text-gray-500 dark:text-gray-400">No target: weight has no range in Vita</p>
          </div>
          <Sparkline values={series.map(m => m.value)} label={`Weight, last ${series.length} readings`} dates={series.map(m => formatWhen(m.takenAt))}
            format={v => showWeight(v, units.weight)} />
        </div>
      )}
      {show && <WeightForm personId={personId} unit={units.weight} onDone={() => { close(); refresh() }} />}
      {data && data.length > 1 && <WeightTable rows={data.slice(0, 7)} unit={units.weight} readOnly={readOnly} onRemove={id => remove.mutate(id)} />}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the reading.')}</p>}
    </Panel>
  )
}

/** The chart's table view: every chart has one. */
function WeightTable({ rows, unit, readOnly, onRemove }: { rows: Measurement[]; unit: 'kg' | 'lb'; readOnly: boolean; onRemove: (id: string) => void }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-xs font-medium text-gray-600 dark:text-gray-300">Show as a table</summary>
      <table className="mt-2 w-full text-left">
        <caption className="sr-only">Weight readings</caption>
        <thead><tr className="text-xs text-gray-500 dark:text-gray-400"><th className="py-1 font-medium">When</th><th className="py-1 text-right font-medium">Weight</th><th /></tr></thead>
        <tbody>
          {rows.map(m => (
            <tr key={m.id} className="border-t border-gray-100 dark:border-gray-800">
              <td className="py-1.5 text-gray-700 dark:text-gray-300">{formatWhen(m.takenAt)}</td>
              <td className="py-1.5 text-right font-mono">{showWeight(m.value, unit)}</td>
              <td className="py-1.5 text-right">{!readOnly && <Button size="sm" variant="ghost" aria-label="Remove this reading" onClick={() => onRemove(m.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}

function WeightForm({ personId, unit, onDone }: { personId: string; unit: 'kg' | 'lb'; onDone: () => void }) {
  const [value, setValue] = useState('')
  const [when, setWhen] = useState('')
  const save = useMutation({
    mutationFn: () => vitaApi.addMeasurement(personId, { kind: 'weight', value: value.trim(), unit, ...(when ? { takenAt: when } : {}) }),
    onSuccess: onDone,
  })
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label={`Weight (${unit})`} value={value} maxLength={6} placeholder={unit === 'kg' ? '64.5' : '142'} onChange={setValue} />
        <TextField label="When (leave empty for now)" type="datetime-local" value={when} onChange={setWhen} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={!value.trim()}>Save weight</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the weight.')}</p>}
    </form>
  )
}
