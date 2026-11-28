import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ExternalLink, LineChart, Pencil, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { CheckField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { LabReportView, LabResult, LabResultChange } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { NeedsVerification } from '../components/FigureLabel'
import { ConvertedNote, LabFlagPill, LabTargetCell } from '../components/Labs'
import { errorText } from '../data'
import { groupResults, resultName } from '../labs'
import { vitaKeys } from '../queryKeys'
import { useDoctors } from '../share'

/**
 * One lab report (7.8): tests grouped (Diabetes, Kidney, Lipids, …), each with its value, the lab's
 * own flag and range, and beside it the target status. Each test opens its trend. A report can be
 * corrected row by row or deleted.
 */
export function LabReportPage({ personId, reportId, readOnly, onBack, onOpenTest }: {
  personId: string
  reportId: string | null | undefined
  readOnly: boolean
  onBack: () => void
  onOpenTest: (code: string) => void
}) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: vitaKeys.lab(personId, reportId ?? ''), queryFn: () => vitaApi.lab(reportId as string), enabled: !!reportId,
  })
  const doctors = useDoctors()
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const remove = useMutation({
    mutationFn: () => vitaApi.removeLab(reportId as string),
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.person(personId) }); onBack() },
  })
  const back = <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft className="h-3.5 w-3.5" aria-hidden />All lab results</Button>
  if (!reportId) return <Panel title="No report picked">{back}</Panel>
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) return <><div>{back}</div><LoadFailed text="Could not open this report." onRetry={() => refetch()} /></>
  const r = data.report
  const doctor = doctors.data?.find(d => d.id === r.doctorId)?.name
  const flagged = data.results.filter(x => ['low', 'high', 'critical'].includes(x.labFlag.flag)).length

  return (
    <div className="flex flex-col gap-4">
      <div>{back}</div>
      <ViewTitle title={`Lab report, ${data.date ? formatDay(data.date) : 'date not known'}`}
        sub={[r.labName ?? 'Lab not named', doctor ? `for ${doctor}` : null, `${data.results.length} tests`, flagged ? `${flagged} flagged by the lab` : 'none flagged by the lab'].filter(Boolean).join(' · ')}>
        {r.documentId && (
          <a href={vitaApi.documentFileUrl(r.documentId)} target="_blank" rel="noreferrer"
            className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-brand-700 underline dark:text-brand-300"><ExternalLink className="h-3 w-3" aria-hidden />The document</a>
        )}
        {!readOnly && !editing && <Button size="sm" variant="secondary" onClick={() => setEditing(true)}><Pencil className="h-3.5 w-3.5" aria-hidden />Correct</Button>}
        {!readOnly && !editing && <Button size="sm" variant="ghost" aria-label="Delete this report" onClick={() => setConfirmDelete(true)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>}
      </ViewTitle>
      {confirmDelete && (
        <div role="alertdialog" aria-label="Delete the report" className="flex flex-wrap items-center gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-sm dark:border-red-900 dark:bg-red-950/30">
          <span className="text-red-800 dark:text-red-200">Delete this report and its {data.results.length} results? The document stays in Documents.</span>
          <Button size="sm" variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}>Delete report</Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>Keep it</Button>
          {remove.error && <p role="alert" className="w-full text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the report.')}</p>}
        </div>
      )}
      {editing
        ? <ReportForm view={data} onDone={() => { setEditing(false); qc.invalidateQueries({ queryKey: vitaKeys.person(personId) }) }} />
        : groupResults(data.results).map(([group, rows]) => (
          <Panel key={group} title={group}>
            <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
              {rows.map(x => <ResultRow key={x.id} r={x} onTrend={x.testCode ? () => onOpenTest(x.testCode as string) : undefined} />)}
            </ul>
          </Panel>
        ))}
      {!editing && !data.results.length && <Panel title="No tests"><p className="text-sm text-gray-500 dark:text-gray-400">This report has no results left.</p></Panel>}
      <p className="text-xs text-gray-500 dark:text-gray-400">Two separate things: the lab flag is the lab’s printed range; your target is the range used for this person.</p>
    </div>
  )
}

function ResultRow({ r, onTrend }: { r: LabResult; onTrend?: () => void }) {
  const unsure = r.confidence != null && r.confidence < 0.8
  return (
    <li className="grid gap-2 py-2.5 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,1.6fr)_auto] sm:items-start">
      <div className="flex min-w-0 flex-col">
        <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{resultName(r)}</span>
        {r.test && r.testNameAsPrinted && r.testNameAsPrinted !== r.test.name && <span className="text-xs text-gray-500 dark:text-gray-400">Printed “{r.testNameAsPrinted}”</span>}
        {unsure && <span className="text-xs text-amber-800 dark:text-amber-200">Vita was unsure of this row. Check it against the document.</span>}
      </div>
      <div className="flex flex-col">
        <span className="font-mono text-sm font-semibold text-gray-900 dark:text-gray-100">{r.shown.value ?? '—'} <span className="font-sans text-xs font-normal text-gray-500 dark:text-gray-400">{r.shown.unit}</span></span>
        <ConvertedNote shown={r.shown} />
        {r.secondary?.map(s => (
          <span key={s.what} className="inline-flex flex-wrap items-center gap-1 text-xs text-gray-500 dark:text-gray-400">{s.what}: <span className="font-mono">{s.shown} {s.unit}</span>{s.needsVerification && <NeedsVerification />}</span>
        ))}
      </div>
      <div className="flex flex-col items-start gap-0.5">
        <span className="text-xs text-gray-500 dark:text-gray-400 sm:hidden">Lab flag</span>
        <LabFlagPill flag={r.labFlag.flag} label={r.labFlag.label} />
        {r.flagAsPrinted && <span className="text-xs text-gray-500 dark:text-gray-400">Printed “{r.flagAsPrinted}”</span>}
      </div>
      <div className="flex flex-col items-start gap-0.5">
        <span className="text-xs text-gray-500 dark:text-gray-400 sm:hidden">Your target</span>
        <LabTargetCell target={r.target} />
      </div>
      <div>{onTrend && <Button size="sm" variant="ghost" aria-label={`Trend of ${resultName(r)}`} onClick={onTrend}><LineChart className="h-3.5 w-3.5" aria-hidden />Trend</Button>}</div>
    </li>
  )
}

/** Correcting a report: the header and each row as printed; a row can be removed. */
function ReportForm({ view, onDone }: { view: LabReportView; onDone: () => void }) {
  const r = view.report
  const [head, setHead] = useState({ labName: r.labName ?? '', collectedOn: r.collectedOn ?? '' })
  const [rows, setRows] = useState(() => view.results.map(x => ({
    id: x.id, name: x.testNameAsPrinted ?? '', value: x.valueAsPrinted ?? (x.valueNum == null ? x.valueText ?? '' : String(x.valueNum)),
    unit: x.unit ?? '', range: x.refText ?? '', flag: x.flagAsPrinted ?? '', remove: false, start: x,
  })))
  const set = (i: number, patch: Partial<(typeof rows)[number]>) => setRows(rows.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  const changes = (): LabResultChange[] => rows.flatMap(x => {
    if (x.remove) return [{ id: x.id, remove: true }]
    const s = x.start
    const c: LabResultChange = { id: x.id }
    if (x.name !== (s.testNameAsPrinted ?? '')) c.testNameAsPrinted = x.name.trim() || null
    if (x.value !== (s.valueAsPrinted ?? (s.valueNum == null ? s.valueText ?? '' : String(s.valueNum)))) c.valueAsPrinted = x.value.trim() || null
    if (x.unit !== (s.unit ?? '')) c.unit = x.unit.trim() || null
    if (x.range !== (s.refText ?? '')) c.rangeAsPrinted = x.range.trim() || null
    if (x.flag !== (s.flagAsPrinted ?? '')) c.flagAsPrinted = x.flag.trim() || null
    return Object.keys(c).length > 1 ? [c] : []
  })
  const save = useMutation({
    mutationFn: () => vitaApi.updateLab(r.id, { labName: head.labName.trim() || null, collectedOn: head.collectedOn || null, version: r.version, results: changes() }),
    onSuccess: onDone,
  })
  const removing = rows.filter(x => x.remove).length
  return (
    <Panel title="Correct the report">
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Type each value as the lab printed it. Vita works out the unit, the lab flag and the target again when you save.</p>
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Lab" value={head.labName} maxLength={200} onChange={labName => setHead({ ...head, labName })} />
          <TextField label="Collected on" type="date" value={head.collectedOn} onChange={collectedOn => setHead({ ...head, collectedOn })} />
        </div>
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {rows.map((x, i) => (
            <li key={x.id} className={`flex flex-col gap-2 py-2 ${x.remove ? 'opacity-50' : ''}`}>
              <div className="grid grid-cols-2 gap-2 lg:grid-cols-[2fr_1fr_1fr_1.4fr_1fr]">
                <TextField label="Test as printed" value={x.name} maxLength={200} onChange={name => set(i, { name })} />
                <TextField label="Value" value={x.value} maxLength={60} onChange={value => set(i, { value })} />
                <TextField label="Unit" value={x.unit} maxLength={40} onChange={unit => set(i, { unit })} />
                <TextField label="Lab range" value={x.range} maxLength={200} onChange={range => set(i, { range })} />
                <TextField label="Flag printed" value={x.flag} maxLength={20} onChange={flag => set(i, { flag })} />
              </div>
              <CheckField label="Remove this row" checked={x.remove} onChange={remove => set(i, { remove })} />
            </li>
          ))}
        </ul>
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" type="submit" loading={save.isPending}>Save corrections</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
          {removing > 0 && <span className="text-xs text-gray-600 dark:text-gray-300">{removing} {removing === 1 ? 'row' : 'rows'} will be removed.</span>}
        </div>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the corrections.')}</p>}
      </form>
    </Panel>
  )
}
