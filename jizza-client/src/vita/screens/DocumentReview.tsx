import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, ExternalLink, Lock, TriangleAlert } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { ApiError } from '@/lib/api'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { DocumentDraft, DocumentKind, DoseUnit, DraftMedicine, DraftTest, MedicineForm, VitaDocument, WithFood } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { DOSE_UNITS, FORMS, WITH_FOOD } from '../medicines'
import { vitaKeys } from '../queryKeys'
import { DOCUMENT_KINDS, DocumentState, MoveDocument } from './Documents'
import { DaysField, TimesField } from './Medicines'

/** Below this the server marks a cell for checking; the review outlines it. */
const CHECK_BELOW = 0.8
const LAB_FLAGS = { high: 'High', low: 'Low', normal: 'Normal', unknown: 'Not printed' } as const
const outline = 'ring-2 ring-amber-500 ring-offset-1 dark:ring-offset-gray-900 rounded-lg'

export function rowsToSave(d: DocumentDraft): number {
  return d.tests.filter(t => !t.skip).length + d.medicines.filter(m => !m.skip).length
}

/**
 * The extraction review (7.8): the document on the left, what Vita read on the right as editable
 * rows, cells it is unsure of outlined, and "Confirm N rows" at the bottom. Nothing is saved before
 * that press. A patient that does not match, a name Vita could not read and a locked PDF each have
 * their own state.
 */
export function DocumentReview({ personId, documentId, readOnly, onBack, onOpen }: {
  personId: string
  documentId: string | null | undefined
  readOnly: boolean
  onBack: () => void
  onOpen: (view: string) => void
}) {
  const qc = useQueryClient()
  const doc = useQuery({ queryKey: ['vita', personId, 'documents', documentId ?? '', 'doc'], queryFn: () => vitaApi.document(documentId as string), enabled: !!documentId })
  const d = doc.data
  const view = useQuery({
    queryKey: vitaKeys.draft(personId, documentId ?? ''), queryFn: () => vitaApi.draft(documentId as string),
    enabled: !!documentId && !!d && (d.hasDraft || d.confirmed),
  })
  const [draft, setDraft] = useState<DocumentDraft | null>(null)
  const [patientConfirmed, setPatientConfirmed] = useState(false)
  const [moving, setMoving] = useState(false)
  const [password, setPassword] = useState('')
  const [saved, setSaved] = useState<number | null>(null)
  useEffect(() => { if (view.data?.draft) setDraft(structuredClone(view.data.draft)) }, [view.data])
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })

  const read = useMutation({
    mutationFn: (pw?: string) => vitaApi.extractDocument(documentId as string, pw),
    onSuccess: out => { setPassword(''); if (out.draft) setDraft(structuredClone(out.draft)); refresh() },
  })
  const confirm = useMutation({
    mutationFn: () => vitaApi.confirmDraft(documentId as string, draft as DocumentDraft, { patientConfirmed, doctorId: d?.doctorId ?? null }),
    onSuccess: out => { setSaved(out.saved.medicines.length); refresh() },
  })
  const discard = useMutation({ mutationFn: () => vitaApi.discardDraft(documentId as string), onSuccess: () => { refresh(); onBack() } })

  const back = <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft className="h-3.5 w-3.5" aria-hidden />All documents</Button>
  if (!documentId) return <Panel title="No document picked">{back}</Panel>
  if (doc.isLoading) return <SkeletonRows rows={4} />
  if (doc.error || !d) return <><div>{back}</div><LoadFailed text="Could not open this document." onRetry={() => doc.refetch()} /></>
  const title = d.title || DOCUMENT_KINDS[d.kind]

  if (saved != null) {
    return (
      <div className="flex flex-col gap-4">
        <div>{back}</div>
        <Panel title={<><CheckCircle2 className="h-4 w-4 text-green-600 dark:text-green-400" aria-hidden />Saved to the record</>}>
          <p className="text-sm text-gray-700 dark:text-gray-200">
            {saved ? `${saved} ${saved === 1 ? 'medicine was' : 'medicines were'} added with the times you checked. Reminders start from today.` : 'The document’s details were saved.'}
            {draft && draft.tests.some(t => !t.skip) ? ' The test results are kept on the document; trends per test come with the lab results update.' : ''}
          </p>
          <div className="flex gap-2">
            {saved > 0 && <Button size="sm" onClick={() => onOpen('medicines')}>Open Medicines</Button>}
            <Button size="sm" variant="secondary" onClick={onBack}>All documents</Button>
          </div>
        </Panel>
      </div>
    )
  }

  const mismatch = draft?.patientCheck.status === 'mismatch'
  const locked = !d.hasDraft && !d.confirmed && (d.needsPassword || read.data?.needsPassword)
  const edit = !readOnly && !d.confirmed
  const count = draft ? rowsToSave(draft) : 0
  const unnamed = !!draft?.medicines.some(m => !m.skip && !(m.name ?? '').trim())
  const confirmError = confirm.error instanceof ApiError && confirm.error.status === 409 ? confirm.error.message : confirm.error ? errorText(confirm.error, 'Could not save.') : null

  return (
    <div className="flex flex-col gap-4">
      <div>{back}</div>
      <ViewTitle title={d.confirmed ? `What was saved from ${title}` : `Review: ${title}`} sub={d.confirmed ? 'Read-only: saved rows are changed in Medicines and Lab results' : 'Check each row against the document. Nothing is saved until you confirm.'}>
        <DocumentState d={d} />
      </ViewTitle>
      <div className="grid gap-4 lg:grid-cols-2 lg:items-start">
        <Preview doc={d} />
        <div className="flex min-w-0 flex-col gap-4">
          {locked && (
            <Panel title={<><Lock className="h-4 w-4" aria-hidden />This PDF is locked</>}>
              <p className="text-sm text-gray-600 dark:text-gray-300">Labs often lock reports with a password such as the date of birth. Vita keeps the password encrypted so it can read the next report too.</p>
              <form className="flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); read.mutate(password) }}>
                <TextField label="Password" type="password" value={password} maxLength={64} onChange={setPassword} />
                <Button size="sm" type="submit" disabled={!password} loading={read.isPending}>Unlock and read</Button>
              </form>
              {read.data?.needsPassword && !read.isPending && <p role="alert" className="text-sm text-red-600 dark:text-red-400">That password did not open it.</p>}
            </Panel>
          )}
          {!locked && !d.hasDraft && !d.confirmed && !draft && (
            <Panel title={d.extractionStatus === 'failed' ? 'Vita could not read this' : 'Not read yet'}>
              {d.extractionError && <p className="text-sm text-red-700 dark:text-red-300">{d.extractionError}</p>}
              {!readOnly && <div><Button size="sm" loading={read.isPending} onClick={() => read.mutate(undefined)}>{d.extractionStatus === 'failed' ? 'Read again' : 'Read it'}</Button></div>}
            </Panel>
          )}
          {read.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(read.error, 'Could not read the document.')}</p>}
          {view.isLoading && <SkeletonRows rows={4} />}
          {draft && (
            <>
              {mismatch && (
                <section role="alert" aria-label="Patient does not match" className="flex flex-col gap-2 rounded-2xl border-2 border-amber-500 bg-amber-50 p-3 text-sm dark:bg-amber-950/30">
                  <p className="flex items-center gap-1.5 font-semibold text-amber-900 dark:text-amber-100"><TriangleAlert className="h-4 w-4" aria-hidden />The patient on this document may be someone else</p>
                  {draft.patient && <p className="text-amber-900 dark:text-amber-100">On the document: {[draft.patient.name, draft.patient.age, draft.patient.sex].filter(Boolean).join(' · ')}</p>}
                  {draft.patientCheck.reasons?.map(r => <p key={r} className="text-amber-900 dark:text-amber-100">{r}</p>)}
                  {edit && <CheckField label="I checked: it belongs to this person" checked={patientConfirmed} onChange={setPatientConfirmed} />}
                  {edit && !moving && <div><Button size="sm" variant="secondary" onClick={() => setMoving(true)}>Move it to the right person</Button></div>}
                  {moving && <MoveDocument personId={personId} doc={d} onDone={() => { setMoving(false); qc.invalidateQueries({ queryKey: vitaKeys.all }); onBack() }} />}
                </section>
              )}
              {draft.patientCheck.status === 'unknown' && <p className="text-xs text-gray-500 dark:text-gray-400">The document gives no age or sex to check against the record.</p>}
              <DraftHeader draft={draft} edit={edit} onChange={setDraft} />
              {draft.tests.length > 0 && <TestRows draft={draft} edit={edit} onChange={setDraft} />}
              {draft.medicines.length > 0 && <MedicineRows draft={draft} edit={edit} onChange={setDraft} />}
              {draft.advice && draft.advice.length > 0 && (
                <Panel title="The doctor’s advice, as written">
                  <ul className="list-disc pl-5 text-sm text-gray-700 dark:text-gray-200">{draft.advice.map(a => <li key={a}>{a}</li>)}</ul>
                  {draft.reviewAfter && <p className="text-sm text-gray-700 dark:text-gray-200">Review after: {draft.reviewAfter}</p>}
                </Panel>
              )}
              {!draft.tests.length && !draft.medicines.length && <p className="text-sm text-gray-500 dark:text-gray-400">Vita found no test results or medicines on this document. Confirm to keep its date and kind.</p>}
              {edit && (
                <div className="sticky bottom-0 z-10 flex flex-wrap items-center gap-2 border-t border-gray-200 bg-gray-50 py-3 dark:border-gray-800 dark:bg-gray-950">
                  <Button loading={confirm.isPending} disabled={(mismatch && !patientConfirmed) || unnamed} onClick={() => confirm.mutate()}>
                    {count ? `Confirm ${count} ${count === 1 ? 'row' : 'rows'}` : 'Confirm'}
                  </Button>
                  <Button variant="ghost" loading={discard.isPending} onClick={() => discard.mutate()}>Discard what Vita read</Button>
                  {mismatch && !patientConfirmed && <span className="text-xs text-amber-800 dark:text-amber-200">Check the patient first.</span>}
                  {unnamed && <span className="text-xs text-amber-800 dark:text-amber-200">Type each medicine’s name, or leave it out.</span>}
                  {confirmError && <p role="alert" className="w-full text-sm text-red-600 dark:text-red-400">{confirmError}</p>}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function Preview({ doc }: { doc: VitaDocument }) {
  const url = vitaApi.documentFileUrl(doc.id)
  const image = doc.contentType?.startsWith('image/')
  return (
    <section aria-label="The document" className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-2 dark:border-gray-800 dark:bg-gray-900 lg:sticky lg:top-16">
      {image
        ? <img src={url} alt={`${DOCUMENT_KINDS[doc.kind]} as uploaded`} className="max-h-[40vh] w-full rounded-lg object-contain lg:max-h-[75vh]" />
        : <object data={url} type="application/pdf" aria-label="The uploaded PDF" className="h-[50vh] w-full rounded-lg lg:h-[75vh]">
            <p className="p-4 text-sm text-gray-500 dark:text-gray-400">The browser cannot show the PDF here. Open it in a new tab to compare.</p>
          </object>}
      <a href={url} target="_blank" rel="noreferrer" className="inline-flex w-fit items-center gap-1 px-1 text-xs text-brand-700 underline dark:text-brand-300"><ExternalLink className="h-3 w-3" aria-hidden />Open in a new tab</a>
    </section>
  )
}

function DraftHeader({ draft, edit, onChange }: { draft: DocumentDraft; edit: boolean; onChange: (d: DocumentDraft) => void }) {
  return (
    <Panel title="About the document">
      <div className="grid gap-3 sm:grid-cols-2">
        {edit
          ? <SelectField label="Kind" value={draft.kind} onChange={kind => onChange({ ...draft, kind })} options={(Object.keys(DOCUMENT_KINDS) as DocumentKind[]).map(k => ({ value: k, label: DOCUMENT_KINDS[k] }))} />
          : <p className="text-sm">{DOCUMENT_KINDS[draft.kind]}</p>}
        {edit
          ? <TextField label="Date on it" type="date" value={draft.documentDate ?? ''} hint={draft.documentDateAsPrinted ? `Printed: ${draft.documentDateAsPrinted}` : undefined} onChange={documentDate => onChange({ ...draft, documentDate: documentDate || null })} />
          : <p className="text-sm">{draft.documentDate ? formatDay(draft.documentDate) : 'Date not known'}</p>}
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">
        {[draft.labName, draft.doctorName, draft.readFrom === 'text' ? 'Read from the PDF’s text' : draft.readFrom === 'scan' ? 'Read from a scan' : 'Read from a photo', draft.pages ? `${draft.pages} ${draft.pages === 1 ? 'page' : 'pages'}` : null].filter(Boolean).join(' · ')}
      </p>
      {draft.notes?.map(n => <p key={n} className="text-xs text-gray-600 dark:text-gray-300">{n}</p>)}
    </Panel>
  )
}

function unsure(confidence: number | null | undefined, needsCheck: boolean) {
  return needsCheck || (confidence != null && confidence < CHECK_BELOW)
}

function TestRows({ draft, edit, onChange }: { draft: DocumentDraft; edit: boolean; onChange: (d: DocumentDraft) => void }) {
  const set = (i: number, patch: Partial<DraftTest>) => onChange({ ...draft, tests: draft.tests.map((t, j) => (j === i ? { ...t, ...patch } : t)) })
  return (
    <Panel title={`Test results (${draft.tests.length})`}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">The lab’s own flag is shown as printed. Whether a result is within your target is shown separately once it is saved. Outlined cells are ones Vita is unsure of.</p>
      <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {draft.tests.map((t, i) => {
          const check = unsure(t.confidence, t.needsCheck)
          return (
            <li key={i} className={`flex flex-col gap-2 py-2 ${t.skip ? 'opacity-50' : ''}`}>
              {t.section && (i === 0 || draft.tests[i - 1].section !== t.section) && <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t.section}</p>}
              {edit ? (
                <div className="grid grid-cols-2 gap-2">
                  <div className={check && !t.name ? outline : ''}><TextField label="Test" value={t.name ?? ''} maxLength={120} onChange={name => set(i, { name })} /></div>
                  <div className={check ? outline : ''}><TextField label="Value" value={t.valueAsPrinted ?? (t.value == null ? '' : String(t.value))} maxLength={40} onChange={v => set(i, { valueAsPrinted: v, value: Number.isFinite(Number(v)) && v.trim() !== '' ? Number(v) : null })} /></div>
                  <TextField label="Unit" value={t.unit ?? ''} maxLength={30} onChange={unit => set(i, { unit })} />
                  <SelectField label="Lab flag" value={t.flag ?? 'unknown'} onChange={flag => set(i, { flag })} options={(Object.keys(LAB_FLAGS) as (keyof typeof LAB_FLAGS)[]).map(f => ({ value: f, label: LAB_FLAGS[f] }))} />
                </div>
              ) : (
                <p className="flex flex-wrap items-center gap-2 text-sm"><b className="font-medium">{t.name}</b><span className="font-mono">{t.valueAsPrinted ?? t.value}</span>{t.unit}{t.flag && t.flag !== 'unknown' && <Pill tone={t.flag === 'normal' ? 'neutral' : 'warn'}>Lab flag: {LAB_FLAGS[t.flag]}</Pill>}</p>
              )}
              <p className="flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
                {t.rangeAsPrinted && <span>Lab range: {t.rangeAsPrinted}</span>}
                {t.flagAsPrinted && <span>Flag printed: {t.flagAsPrinted}</span>}
                {check && <Pill tone="warn"><TriangleAlert className="h-3 w-3" aria-hidden />Check this row</Pill>}
                {t.notes?.map(n => <span key={n}>{n}</span>)}
                {edit && <span className="ml-auto"><CheckField label="Leave out" checked={!!t.skip} onChange={skip => set(i, { skip })} /></span>}
              </p>
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}

function MedicineRows({ draft, edit, onChange }: { draft: DocumentDraft; edit: boolean; onChange: (d: DocumentDraft) => void }) {
  const set = (i: number, patch: Partial<DraftMedicine>) => onChange({ ...draft, medicines: draft.medicines.map((m, j) => (j === i ? { ...m, ...patch } : m)) })
  return (
    <Panel title={`Medicines (${draft.medicines.length})`}>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">Each medicine you keep is added to Medicines with the times below, from the date on the prescription. Vita never completes a name it could not read.</p>
      <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
        {draft.medicines.map((m, i) => {
          const s = m.schedule
          const printed = [m.doseAsPrinted, m.timingAsPrinted, m.frequencyAsPrinted, m.durationAsPrinted].filter(Boolean).join(' · ')
          return (
            <li key={i} className={`flex flex-col gap-2 py-3 ${m.skip ? 'opacity-50' : ''}`}>
              {printed && <p className="text-xs text-gray-600 dark:text-gray-300">As written: <span className="font-mono">{printed}</span>{m.scheduleReading ? ` → ${m.scheduleReading}` : ''}</p>}
              {m.nameUnsure && <p className="flex items-center gap-1 text-xs font-medium text-amber-800 dark:text-amber-200"><TriangleAlert className="h-3 w-3" aria-hidden />Vita could not read this name. Type it from the prescription, or leave it out.</p>}
              {edit ? (
                <>
                  <div className="grid gap-2 sm:grid-cols-3">
                    <div className={m.nameUnsure || !m.name ? outline : ''}><TextField label="Name" value={m.name ?? ''} maxLength={120} onChange={name => set(i, { name, nameUnsure: false })} /></div>
                    <div className={m.needsCheck ? outline : ''}><TextField label="Strength" value={m.strength ?? ''} maxLength={40} onChange={strength => set(i, { strength })} /></div>
                    <SelectField label="Form" value={(m.form ?? 'tablet') as MedicineForm} onChange={form => set(i, { form })} options={(Object.keys(FORMS) as MedicineForm[]).map(f => ({ value: f, label: FORMS[f] }))} />
                  </div>
                  <CheckField label="Only when needed" checked={m.asNeeded} onChange={asNeeded => set(i, { asNeeded, schedule: asNeeded ? null : s ?? { times: ['08:00'], recurrence: 'FREQ=DAILY', doseUnits: 1, doseUnit: 'tablet', withFood: 'any' } })} />
                  {!m.asNeeded && (
                    <div className={`flex flex-col gap-2 rounded-xl bg-gray-50 p-2 dark:bg-gray-800/50 ${m.needsCheck || !s?.times?.length ? outline : ''}`}>
                      <TimesField times={s?.times ?? []} onChange={times => set(i, { schedule: { ...(s ?? {}), times } })} />
                      <DaysField rule={s?.recurrence ?? 'FREQ=DAILY'} onChange={recurrence => set(i, { schedule: { ...(s ?? {}), recurrence } })} />
                      <div className="grid gap-2 sm:grid-cols-3">
                        <TextField label="How much each time" value={String(s?.doseUnits ?? 1)} maxLength={6} onChange={v => set(i, { schedule: { ...(s ?? {}), doseUnits: Number(v) } })} />
                        <SelectField label="Unit" value={(s?.doseUnit ?? 'tablet') as DoseUnit} onChange={doseUnit => set(i, { schedule: { ...(s ?? {}), doseUnit } })} options={(Object.keys(DOSE_UNITS) as DoseUnit[]).map(u => ({ value: u, label: DOSE_UNITS[u][1] }))} />
                        <SelectField label="Food" value={(s?.withFood ?? 'any') as WithFood} onChange={withFood => set(i, { schedule: { ...(s ?? {}), withFood } })} options={(Object.keys(WITH_FOOD) as WithFood[]).map(f => ({ value: f, label: WITH_FOOD[f] }))} />
                      </div>
                    </div>
                  )}
                  <div className="flex flex-wrap items-end gap-3">
                    <TextField label="Until (for a course)" type="date" value={m.endDate ?? ''} onChange={endDate => set(i, { endDate: endDate || null })} />
                    <CheckField label="Leave out" checked={!!m.skip} onChange={skip => set(i, { skip })} />
                  </div>
                </>
              ) : (
                <p className="text-sm"><b className="font-medium">{m.name ?? 'Name not read'}</b>{m.strength ? ` ${m.strength}` : ''}{m.skip ? ' (left out)' : ''}</p>
              )}
              {m.notes?.map(n => <p key={n} className="text-xs text-gray-500 dark:text-gray-400">{n}</p>)}
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}
