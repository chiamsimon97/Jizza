import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, ClipboardCheck, FlaskConical, Pencil, Phone, Pill as PillIcon, Stethoscope } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { ConditionMeasure, ConditionSummary, TrendView } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { FigureLabel } from '../components/FigureLabel'
import { ChartKey, StatusBadge, TargetLabel, TrendChart, bandsFromText } from '../components/Readings'
import { formatWhen, useUnits, type Units } from '../data'
import { TEMPLATES, configLabel, describeFigure } from '../labels'
import { CONTEXTS, kindLabel, showReading, showValue } from '../readings'
import { vitaKeys } from '../queryKeys'
import { ConditionForm, StatusChip } from './Conditions'
import { adherenceWords } from './Medicines'
import { scheduleWords } from '../medicines'

/**
 * A condition page (7.5, spec 5.6): header with since-when and doctor, key-measure tiles each with
 * who set the target, a trend chart (the 7-day BP band, or sugar over 30 days), checks done and due
 * (or "not set: ask your doctor"), the doctor's standing advice and the visits with that doctor.
 */
export function ConditionPage({ personId, conditionId, readOnly, onBack, onOpen }: {
  personId: string
  conditionId: string | null | undefined
  readOnly: boolean
  onBack: () => void
  onOpen: (view: string) => void
}) {
  const units = useUnits()
  const [editing, setEditing] = useState(false)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: vitaKeys.summary(personId, conditionId ?? ''),
    queryFn: () => vitaApi.conditionSummary(personId, conditionId as string),
    enabled: !!conditionId,
  })
  const back = <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft className="h-3.5 w-3.5" aria-hidden />All conditions</Button>
  if (!conditionId) return <Panel title="No condition picked">{back}</Panel>
  if (isLoading) return <SkeletonRows rows={4} />
  if (error || !data) return <><div>{back}</div><LoadFailed text="Could not open this condition." onRetry={() => refetch()} /></>
  const c = data.condition

  return (
    <div className="flex flex-col gap-4">
      <div>{back}</div>
      <ViewTitle title={c.name} sub={[c.template && c.template !== 'generic' ? TEMPLATES[c.template] : null, c.since ? `Since ${formatDay(c.since)}` : 'Since: not recorded'].filter(Boolean).join(' · ')}>
        <StatusChip status={c.status} />
        {!readOnly && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing(true)}><Pencil className="h-3.5 w-3.5" aria-hidden />Edit</Button>}
      </ViewTitle>
      {editing && <ConditionForm personId={personId} start={c} onDone={() => { setEditing(false); refetch() }} />}

      <Panel title={<><Stethoscope className="h-4 w-4 text-gray-400" aria-hidden />Doctor</>}>
        {data.doctor
          ? <p className="flex flex-wrap items-center gap-2 text-sm text-gray-800 dark:text-gray-100">{data.doctor.name}{data.doctor.speciality && <span className="text-gray-500 dark:text-gray-400">· {data.doctor.speciality}</span>}
            {data.doctor.phone && <a href={`tel:${data.doctor.phone}`} className="inline-flex items-center gap-1 font-mono text-xs text-brand-700 underline dark:text-brand-300"><Phone className="h-3 w-3" aria-hidden />{data.doctor.phone}</a>}</p>
          : <p className="text-sm text-gray-500 dark:text-gray-400">No doctor linked. Edit the condition to pick one from Doctors and visits.</p>}
        {c.advice && <p className="whitespace-pre-line rounded-lg bg-gray-50 p-2 text-sm text-gray-800 dark:bg-gray-800/60 dark:text-gray-100"><b className="font-medium">Standing advice:</b> {c.advice}</p>}
      </Panel>

      {data.measures.length > 0 && (
        <div className="grid gap-3 md:grid-cols-2">
          {data.measures.map(m => <MeasureTile key={`${m.kind}-${m.context ?? ''}`} m={m} units={units} />)}
        </div>
      )}
      <MainChart data={data} units={units} onOpen={onOpen} />

      {data.lowReadings && data.lowReadings.length > 0 && (
        <Panel title="Low sugar readings">
          <ul className="flex flex-col gap-1">
            {data.lowReadings.map(r => (
              <li key={r.id} className="flex flex-wrap items-center gap-2 text-sm">
                <span className="font-mono font-semibold">{showReading(r, units)}</span>
                {r.evaluation && <StatusBadge status={r.evaluation.status} />}
                <span className="text-xs text-gray-500 dark:text-gray-400">{formatWhen(r.takenAt)}</span>
              </li>
            ))}
          </ul>
        </Panel>
      )}

      <Panel title={<><PillIcon className="h-4 w-4 text-gray-400" aria-hidden />Medicines for it</>}>
        {!data.medicines.length && <p className="text-sm text-gray-500 dark:text-gray-400">No medicines linked to this condition. Pick the condition when adding a medicine.</p>}
        {data.medicines.length > 0 && (
          <ul className="flex flex-col gap-1.5">
            {data.medicines.map(m => (
              <li key={m.id} className="flex flex-col text-sm">
                <span className="font-medium text-gray-900 dark:text-gray-100">{m.name}{m.strength ? ` ${m.strength}` : ''}{m.status === 'paused' ? ' (paused)' : ''}</span>
                <span className="text-xs text-gray-600 dark:text-gray-300">{[scheduleWords(m.schedule), m.schedule ? `last 7 days: ${adherenceWords(m.adherence7Days)}` : null].filter(Boolean).join(' · ')}</span>
              </li>
            ))}
          </ul>
        )}
        <div><Button size="sm" variant="ghost" onClick={() => onOpen('medicines')}>All medicines</Button></div>
      </Panel>

      {(data.checks.length > 0 || data.labs) && (
        <Panel title={<><ClipboardCheck className="h-4 w-4 text-gray-400" aria-hidden />Checks</>}>
          {data.checks.length > 0 && (
            <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
              {data.checks.map(ch => {
                const key = `clinical.intervals.${data.template}.${ch.check}`
                return (
                  <li key={ch.check} className="flex flex-col gap-0.5 py-2">
                    <span className="text-sm font-medium text-gray-800 dark:text-gray-100">{configLabel(key)}</span>
                    <span className="text-xs text-gray-600 dark:text-gray-300">{ch.state === 'not_set' ? '' : describeFigure(key, ch.interval ?? null)}</span>
                    <FigureLabel state={ch.state} label={ch.label} />
                    <span className="text-xs text-gray-500 dark:text-gray-400">Last done: not recorded yet</span>
                  </li>
                )
              })}
            </ul>
          )}
          {data.labs && <p className="flex items-center gap-1.5 text-sm text-gray-500 dark:text-gray-400"><FlaskConical className="h-4 w-4" aria-hidden />{data.labs}</p>}
        </Panel>
      )}
    </div>
  )
}

function measureTitle(m: ConditionMeasure) {
  return m.context ? `${kindLabel(m.kind)}, ${CONTEXTS[m.context].toLowerCase()}` : kindLabel(m.kind)
}

function MeasureTile({ m, units }: { m: ConditionMeasure; units: Units }) {
  const periods = ([['last7', m.last7], ['last30', m.last30], ['last90', m.last90]] as const).filter(([, t]) => t) as [string, TrendView][]
  const range = periods[0]?.[1].range
  return (
    <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{measureTitle(m)}</h3>
      <div className="flex flex-wrap gap-4">
        {periods.map(([k, t]) => (
          <div key={k}>
            <p className="text-xs text-gray-500 dark:text-gray-400">Average, {t.days} days</p>
            <p className="font-mono text-xl font-semibold text-gray-900 dark:text-gray-100">
              {t.trend.all.value ? showValue(t.kind, t.trend.all.value.shown, t.trend.all.value2?.shown ?? null, units) : '—'}
            </p>
            <p className="text-xs text-gray-500 dark:text-gray-400">{t.trend.count} readings{t.trend.all.outside ? `, ${t.trend.all.outside} outside range` : ''}</p>
          </div>
        ))}
      </div>
      {m.kind === 'bp' && m.last7 && (m.last7.trend.morning.value || m.last7.trend.evening.value) && (
        <p className="text-xs text-gray-600 dark:text-gray-300">
          Mornings {m.last7.trend.morning.value ? showValue('bp', m.last7.trend.morning.value.shown, m.last7.trend.morning.value2?.shown ?? null, units) : '—'}
          {' · '}Evenings {m.last7.trend.evening.value ? showValue('bp', m.last7.trend.evening.value.shown, m.last7.trend.evening.value2?.shown ?? null, units) : '—'}
        </p>
      )}
      {range && (range.status === 'set'
        ? <TargetLabel source={range.source} label={range.label} needsVerification={range.needsVerification} range={range.range} />
        : <p className="text-xs font-medium text-gray-600 dark:text-gray-300">Target not set: ask your doctor</p>)}
    </section>
  )
}

/** The 7-day BP band for high blood pressure; sugar before meals over 30 days for diabetes. */
function MainChart({ data, units, onOpen }: { data: ConditionSummary; units: Units; onOpen: (view: string) => void }) {
  const m = data.measures[0]
  const t = m?.last7 ?? m?.last30
  if (!t) return null
  const points = t.trend.days.flatMap(d => [...d.morning, ...d.evening])
  return (
    <Panel title={`${measureTitle(m)}, last ${t.days} days`} action={<Button size="sm" variant="secondary" onClick={() => onOpen('vitals')}>Open Vitals</Button>}>
      {!points.length && <p className="text-sm text-gray-500 dark:text-gray-400">No readings yet. Add one from Vitals or the Add menu.</p>}
      {points.length > 0 && (
        <>
          <TrendChart kind={t.kind} points={points} bands={t.range.status === 'set' ? bandsFromText(t.range.range) : null} units={units}
            label={`${measureTitle(m)}, ${t.trend.count} readings in ${t.days} days`} />
          <ChartKey kind={t.kind} />
        </>
      )}
    </Panel>
  )
}
