import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronRight, FlaskConical, Upload, X } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { LabTest, RangeInput, RangeView } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { LabChart, LabFlagPill, LabTargetCell } from '../components/Labs'
import { TargetLabel } from '../components/Readings'
import { errorText } from '../data'
import { changeWords } from '../labs'
import { vitaKeys } from '../queryKeys'
import { useDoctors } from '../share'

/**
 * Lab results (7.8): the reports newest first with how many tests the lab flagged, the trend of one
 * test across reports when one is picked, and the lab targets with who set each.
 */
export function Labs({ personId, readOnly, test, onOpenReport, onOpenTest, onUpload }: {
  personId: string
  readOnly: boolean
  /** A test code whose trend is open. */
  test: string | null | undefined
  onOpenReport: (id: string) => void
  onOpenTest: (code: string | null) => void
  onUpload: () => void
}) {
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.labs(personId), queryFn: () => vitaApi.labs(personId) })
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Lab results" sub="Reports as tests you can follow over time">
        {!readOnly && <Button size="sm" onClick={onUpload}><Upload className="h-3.5 w-3.5" aria-hidden />Upload a report</Button>}
      </ViewTitle>
      {test && <LabTrendPanel personId={personId} test={test} onClose={() => onOpenTest(null)} />}
      <Panel title="Reports">
        {isLoading && <SkeletonRows rows={3} />}
        {error && <LoadFailed text="Could not load the lab reports." onRetry={() => refetch()} />}
        {data && !data.length && (
          <p className="text-sm text-gray-500 dark:text-gray-400">No reports yet. Upload a PDF or a photo of a lab report; Vita shows what it read before anything is saved.</p>
        )}
        {data && data.length > 0 && (
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
            {data.map(r => (
              <li key={r.id}>
                <button type="button" onClick={() => onOpenReport(r.id)} aria-label={`Open the report of ${r.date ? formatDay(r.date) : 'an unknown date'}`}
                  className="flex w-full items-center gap-3 py-2.5 text-left hover:bg-gray-50 dark:hover:bg-gray-800/50">
                  <FlaskConical className="h-4 w-4 shrink-0 text-gray-400" aria-hidden />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{r.date ? formatDay(r.date) : 'Date not known'}</span>
                    <span className="text-xs text-gray-500 dark:text-gray-400">{[r.labName ?? 'Lab not named', `${r.rows} ${r.rows === 1 ? 'test' : 'tests'}`].join(' · ')}</span>
                  </span>
                  {r.labFlagged > 0
                    ? <Pill tone="warn">{r.labFlagged} flagged by the lab</Pill>
                    : <Pill>None flagged by the lab</Pill>}
                  <ChevronRight className="h-4 w-4 text-gray-400" aria-hidden />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <LabTargets personId={personId} readOnly={readOnly} onOpenTest={onOpenTest} />
    </div>
  )
}

/** One test across reports: the change since the first and the last, the chart and the table. */
export function LabTrendPanel({ personId, test, onClose }: { personId: string; test: string; onClose?: () => void }) {
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.labTrend(personId, test), queryFn: () => vitaApi.labTrend(personId, test) })
  const title = data?.test.name ?? 'Trend'
  return (
    <Panel title={<span className="flex w-full items-center gap-2">{title} over time{onClose && <button type="button" onClick={onClose} aria-label="Close the trend" className="ml-auto rounded p-1 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><X className="h-4 w-4" aria-hidden /></button>}</span>}>
      {isLoading && <SkeletonRows rows={3} />}
      {error && <LoadFailed text="Could not load this test’s trend." onRetry={() => refetch()} />}
      {data && (
        <>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <LabTargetCell target={data.target} />
            {data.changeSinceFirst && <span className="text-xs text-gray-600 dark:text-gray-300">Since the first report: <b className="font-mono">{changeWords(data.changeSinceFirst)} {data.test.unit}</b></span>}
            {data.changeSincePrevious && <span className="text-xs text-gray-600 dark:text-gray-300">Since the one before: <b className="font-mono">{changeWords(data.changeSincePrevious)} {data.test.unit}</b></span>}
          </div>
          {data.count === 0
            ? <p className="text-sm text-gray-500 dark:text-gray-400">No result for this test yet.</p>
            : <LabChart trend={data} />}
          {data.points.length > 0 && (
            <p className="text-xs text-gray-500 dark:text-gray-400">
              Latest lab flag: <LabFlagPill flag={data.points[data.points.length - 1].labFlag.flag} label={data.points[data.points.length - 1].labFlag.label} />
              <span className="ml-1">The lab flag is the lab’s printed range; the target is the range used for this person.</span>
            </p>
          )}
        </>
      )}
    </Panel>
  )
}

/** Targets for lab tests: HbA1c always, and every test someone set a range for. */
function LabTargets({ personId, readOnly, onOpenTest }: { personId: string; readOnly: boolean; onOpenTest: (code: string) => void }) {
  const qc = useQueryClient()
  const ranges = useQuery({ queryKey: vitaKeys.ranges(personId), queryFn: () => vitaApi.ranges(personId) })
  const tests = useQuery({ queryKey: vitaKeys.labTests, queryFn: vitaApi.labTests, staleTime: 3_600_000 })
  const [editing, setEditing] = useState<{ code: string; setBy: 'user' | 'doctor'; slot: RangeView | null } | null>(null)
  const [adding, setAdding] = useState(false)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeRange(personId, id), onSuccess: refresh })
  const rows = (ranges.data ?? []).filter(r => r.labTestCode)
  const testOf = (code: string) => tests.data?.find(t => t.code === code)
  return (
    <Panel title="Lab targets">
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">The doctor’s target comes first, then your own range, then Vita’s default. A test with none reads “Not set: ask your doctor”.</p>
      {ranges.isLoading && <SkeletonRows rows={2} />}
      {ranges.error && <LoadFailed text="Could not load the targets." onRetry={() => ranges.refetch()} />}
      {editing && <LabRangeForm personId={personId} test={testOf(editing.code)} code={editing.code} setBy={editing.setBy} slot={editing.slot} onDone={() => { setEditing(null); refresh() }} />}
      <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {rows.map(s => (
          <li key={s.labTestCode} className="flex flex-col gap-1 py-2">
            <div className="flex flex-wrap items-center gap-2">
              <button type="button" className="text-sm font-medium text-gray-800 underline-offset-2 hover:underline dark:text-gray-100" onClick={() => onOpenTest(s.labTestCode as string)}>
                {testOf(s.labTestCode as string)?.name ?? s.labTestCode}
              </button>
              {s.status === 'set'
                ? <TargetLabel source={s.source} label={s.label} needsVerification={s.needsVerification} range={s.range} />
                : <TargetLabel source={null} label={s.label} />}
            </div>
            {!readOnly && (
              <div className="flex flex-wrap gap-1.5">
                <Button size="sm" variant="secondary" onClick={() => setEditing({ code: s.labTestCode as string, setBy: 'doctor', slot: s })}>{s.doctorTarget ? 'Change doctor’s target' : 'Add doctor’s target'}</Button>
                <Button size="sm" variant="ghost" onClick={() => setEditing({ code: s.labTestCode as string, setBy: 'user', slot: s })}>{s.own ? 'Change own range' : 'Set own range'}</Button>
                {s.doctorTarget && <Button size="sm" variant="ghost" onClick={() => remove.mutate(s.doctorTarget!.id)}>Remove doctor’s target</Button>}
                {s.own && <Button size="sm" variant="ghost" onClick={() => remove.mutate(s.own!.id)}>Remove own range</Button>}
              </div>
            )}
          </li>
        ))}
      </ul>
      {!readOnly && !editing && (adding
        ? <AddLabTarget tests={tests.data ?? []} taken={rows.map(r => r.labTestCode as string)} onPick={code => { setAdding(false); setEditing({ code, setBy: 'doctor', slot: null }) }} onCancel={() => setAdding(false)} />
        : <div><Button size="sm" variant="ghost" onClick={() => setAdding(true)}>Add a target for another test</Button></div>)}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not remove the target.')}</p>}
    </Panel>
  )
}

function AddLabTarget({ tests, taken, onPick, onCancel }: { tests: LabTest[]; taken: string[]; onPick: (code: string) => void; onCancel: () => void }) {
  const [code, setCode] = useState('')
  const free = tests.filter(t => !taken.includes(t.code))
  return (
    <div className="flex flex-wrap items-end gap-2">
      <SelectField label="Test" value={code} onChange={setCode} className="max-w-xs"
        options={[{ value: '', label: 'Pick a test' }, ...free.map(t => ({ value: t.code, label: `${t.name} (${t.unit})` }))]} />
      <Button size="sm" disabled={!code} onClick={() => onPick(code)}>Next</Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
    </div>
  )
}

function LabRangeForm({ personId, test, code, setBy, slot, onDone }: {
  personId: string; test: LabTest | undefined; code: string; setBy: 'user' | 'doctor'; slot: RangeView | null; onDone: () => void
}) {
  const start = setBy === 'doctor' ? slot?.doctorTarget : slot?.own
  const doctors = useDoctors(setBy === 'doctor')
  const n = (v: number | null | undefined) => (v == null ? '' : String(v))
  const [v, setV] = useState({ low: n(start?.low), high: n(start?.high), doctorId: start?.doctorId ?? '', setOn: start?.setOn ?? '', note: start?.note ?? '' })
  const unit = test?.unit ?? ''
  const body = (): RangeInput => ({
    labTestCode: code, setBy, low: v.low || null, high: v.high || null,
    ...(setBy === 'doctor' ? { doctorId: v.doctorId || null, setOn: v.setOn || null } : {}),
    note: v.note.trim() || null,
  })
  const save = useMutation({ mutationFn: () => vitaApi.putRange(personId, body()), onSuccess: onDone })
  const can = (v.low || v.high) && (setBy === 'user' || v.doctorId)
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <p className="text-sm font-medium text-gray-800 dark:text-gray-100">{setBy === 'doctor' ? 'The doctor’s target' : 'Your own range'} · {test?.name ?? code}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label={`From${unit ? `, ${unit}` : ''} (optional)`} value={v.low} maxLength={10} onChange={low => setV({ ...v, low })} />
        <TextField label={`Up to${unit ? `, ${unit}` : ''}`} value={v.high} maxLength={10} onChange={high => setV({ ...v, high })} />
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
