import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRightLeft, CheckCircle2, ExternalLink, FileText, Loader2, Lock, Pencil, ScanText, Trash2, TriangleAlert, Upload } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { DocumentInput, DocumentKind, VitaDocument } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText, useVitaPeople } from '../data'
import { splitPeople } from '../people'
import { vitaKeys } from '../queryKeys'
import { useDoctors } from '../share'

export const DOCUMENT_KINDS: Record<DocumentKind, string> = {
  prescription: 'Prescription', lab_report: 'Lab report', scan: 'Scan', discharge_summary: 'Discharge summary',
  certificate: 'Certificate', bill: 'Bill', policy: 'Policy', other: 'Other',
}
const ACCEPT = 'application/pdf,image/jpeg,image/png,image/gif,image/webp'

/** Where a document stands, in words with an icon. */
export function DocumentState({ d }: { d: VitaDocument }) {
  if (d.confirmed) return <Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />Saved to the record</Pill>
  if (d.hasDraft) return <Pill tone="warn"><ScanText className="h-3 w-3" aria-hidden />Waiting for your review{d.draftRows ? ` · ${d.draftRows} rows` : ''}</Pill>
  if (d.needsPassword) return <Pill tone="warn"><Lock className="h-3 w-3" aria-hidden />Locked PDF</Pill>
  if (d.extractionStatus === 'pending') return <Pill tone="info"><Loader2 className="h-3 w-3 animate-spin" aria-hidden />Reading</Pill>
  if (d.extractionStatus === 'failed') return <Pill tone="bad"><TriangleAlert className="h-3 w-3" aria-hidden />Could not read</Pill>
  return <Pill>Kept, not read</Pill>
}

/**
 * Documents (7.9): upload a PDF or a photo, filter by kind, doctor, condition and year, search on
 * title. Vita reads a document only when asked and shows what it found before anything is saved.
 */
export function Documents({ personId, readOnly, onReview, uploading, onUploadDone }: {
  personId: string
  readOnly: boolean
  onReview: (documentId: string) => void
  /** The Add menu asked for a document. */
  uploading: boolean
  onUploadDone: () => void
}) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.documents(personId), queryFn: () => vitaApi.documents(personId) })
  const doctors = useDoctors()
  const conditions = useQuery({ queryKey: vitaKeys.conditions(personId), queryFn: () => vitaApi.conditions(personId) })
  const [filter, setFilter] = useState({ kind: '', doctorId: '', conditionId: '', year: '', q: '' })
  const [editing, setEditing] = useState<VitaDocument | null>(null)
  const [moving, setMoving] = useState<VitaDocument | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeDocument(id), onSuccess: refresh })
  const read = useMutation({
    mutationFn: (id: string) => vitaApi.extractDocument(id),
    onSuccess: (out) => { refresh(); if (out.draft) onReview(out.document.id) },
  })
  const docs = data ?? []
  const years = [...new Set(docs.map(d => (d.documentDate ?? d.createdAt ?? '').slice(0, 4)).filter(Boolean))].sort().reverse()
  const q = filter.q.trim().toLowerCase()
  const shown = docs.filter(d => (!filter.kind || d.kind === filter.kind) && (!filter.doctorId || d.doctorId === filter.doctorId)
    && (!filter.conditionId || d.conditionId === filter.conditionId) && (!filter.year || (d.documentDate ?? d.createdAt ?? '').startsWith(filter.year))
    && (!q || (d.title ?? '').toLowerCase().includes(q) || DOCUMENT_KINDS[d.kind].toLowerCase().includes(q)))
  const doctorName = (id: string | null) => doctors.data?.find(x => x.id === id)?.name
  const conditionName = (id: string | null) => conditions.data?.find(x => x.id === id)?.name

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Documents" sub="Prescriptions, reports and discharge papers, kept encrypted" />
      {!readOnly && <UploadArea personId={personId} open={uploading} onDone={(doc) => { onUploadDone(); refresh(); if (doc) read.mutate(doc.id) }} />}
      {read.isPending && <p role="status" className="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300"><Loader2 className="h-4 w-4 animate-spin" aria-hidden />Vita is reading the document. This can take up to a minute.</p>}
      {read.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(read.error, 'Could not read the document.')}</p>}

      <Panel title="Kept documents">
        {docs.length > 0 && (
          <div className="grid gap-2 sm:grid-cols-5">
            <TextField label="Search titles" value={filter.q} onChange={v => setFilter({ ...filter, q: v })} />
            <SelectField label="Kind" value={filter.kind} onChange={kind => setFilter({ ...filter, kind })}
              options={[{ value: '', label: 'All kinds' }, ...(Object.keys(DOCUMENT_KINDS) as DocumentKind[]).map(k => ({ value: k, label: DOCUMENT_KINDS[k] }))]} />
            <SelectField label="Doctor" value={filter.doctorId} onChange={doctorId => setFilter({ ...filter, doctorId })}
              options={[{ value: '', label: 'Any doctor' }, ...(doctors.data ?? []).map(d => ({ value: d.id, label: d.name }))]} />
            <SelectField label="Condition" value={filter.conditionId} onChange={conditionId => setFilter({ ...filter, conditionId })}
              options={[{ value: '', label: 'Any condition' }, ...(conditions.data ?? []).map(c => ({ value: c.id, label: c.name }))]} />
            <SelectField label="Year" value={filter.year} onChange={year => setFilter({ ...filter, year })}
              options={[{ value: '', label: 'Any year' }, ...years.map(y => ({ value: y, label: y }))]} />
          </div>
        )}
        {isLoading && <SkeletonRows rows={3} />}
        {error && <LoadFailed text="Could not load the documents." onRetry={() => refetch()} />}
        {data && !docs.length && <p className="text-sm text-gray-500 dark:text-gray-400">No documents yet. Upload a prescription or a lab report and Vita reads it for you.</p>}
        {docs.length > 0 && !shown.length && <p className="text-sm text-gray-500 dark:text-gray-400">No document matches these filters.</p>}
        {editing && <DocumentForm doc={editing} doctors={doctors.data ?? []} conditions={conditions.data ?? []} onDone={() => { setEditing(null); refresh() }} />}
        {moving && <MoveDocument personId={personId} doc={moving} onDone={() => { setMoving(null); qc.invalidateQueries({ queryKey: vitaKeys.all }) }} />}
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {shown.map(d => (
            <li key={d.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2.5">
              <FileText className="mt-0.5 h-4 w-4 shrink-0 text-gray-400" aria-hidden />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                  {d.title || DOCUMENT_KINDS[d.kind]}
                  <DocumentState d={d} />
                  {d.private && <Pill><Lock className="h-3 w-3" aria-hidden />Private</Pill>}
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {[DOCUMENT_KINDS[d.kind], d.documentDate ? formatDay(d.documentDate) : 'Date not known', doctorName(d.doctorId), conditionName(d.conditionId),
                    d.pageCount ? `${d.pageCount} ${d.pageCount === 1 ? 'page' : 'pages'}` : null].filter(Boolean).join(' · ')}
                </p>
                {d.extractionStatus === 'failed' && d.extractionError && !d.needsPassword && <p className="text-xs text-red-700 dark:text-red-300">{d.extractionError}</p>}
              </div>
              <span className="flex flex-wrap gap-1">
                {!readOnly && d.hasDraft && <Button size="sm" onClick={() => onReview(d.id)}>Review</Button>}
                {!readOnly && !d.hasDraft && !d.confirmed && (d.needsPassword
                  ? <Button size="sm" variant="secondary" onClick={() => onReview(d.id)}>Unlock and read</Button>
                  : <Button size="sm" variant="secondary" loading={read.isPending && read.variables === d.id} onClick={() => read.mutate(d.id)}>{d.extractionStatus === 'failed' ? 'Read again' : 'Read it'}</Button>)}
                {d.confirmed && <Button size="sm" variant="ghost" onClick={() => onReview(d.id)}>What was saved</Button>}
                <a href={vitaApi.documentFileUrl(d.id)} target="_blank" rel="noreferrer" aria-label={`Open ${d.title || DOCUMENT_KINDS[d.kind]}`}
                  className="inline-flex items-center rounded-lg px-2 py-1 text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800"><ExternalLink className="h-3.5 w-3.5" aria-hidden /></a>
                {!readOnly && (
                  <>
                    <Button size="sm" variant="ghost" aria-label="Edit details" onClick={() => setEditing(d)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                    <Button size="sm" variant="ghost" aria-label="Move to another person" onClick={() => setMoving(d)}><ArrowRightLeft className="h-3.5 w-3.5" aria-hidden /></Button>
                    <Button size="sm" variant="ghost" aria-label="Delete this document" onClick={() => remove.mutate(d.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
        {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the document.')}</p>}
      </Panel>
    </div>
  )
}

function UploadArea({ personId, open, onDone }: { personId: string; open: boolean; onDone: (doc: VitaDocument | null) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [kind, setKind] = useState<DocumentKind | ''>('')
  const [isPrivate, setPrivate] = useState(false)
  const [over, setOver] = useState(false)
  const up = useMutation({
    mutationFn: (f: File) => vitaApi.uploadDocument(personId, f, { kind: kind || undefined, private: isPrivate || undefined }),
    onSuccess: doc => { setFile(null); onDone(doc) },
  })
  const pick = (f: File | undefined | null) => { if (f) setFile(f) }
  return (
    <section aria-label="Upload a document"
      onDragOver={e => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
      onDrop={e => { e.preventDefault(); setOver(false); pick(e.dataTransfer.files?.[0]) }}
      className={`flex flex-col gap-3 rounded-2xl border-2 border-dashed p-4 ${over || open ? 'border-[rgb(var(--agent-health))] bg-rose-50/50 dark:bg-rose-950/20' : 'border-gray-300 dark:border-gray-700'}`}>
      <div className="flex flex-wrap items-center gap-3">
        <Upload className="h-5 w-5 text-[rgb(var(--agent-health))]" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{file ? file.name : 'Drop a PDF or a photo here'}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">Vita reads it and shows what it found before anything is saved. PDFs up to 10 MB, photos up to 5 MB.</p>
        </div>
        <input ref={input} type="file" accept={ACCEPT} className="sr-only" aria-label="Choose a file" onChange={e => pick(e.target.files?.[0])} />
        <Button size="sm" variant="secondary" onClick={() => input.current?.click()}>Choose a file</Button>
      </div>
      {file && (
        <div className="flex flex-wrap items-end gap-3">
          <SelectField label="Kind (Vita can tell)" value={kind} onChange={setKind}
            options={[{ value: '' as const, label: 'Let Vita tell' }, ...(Object.keys(DOCUMENT_KINDS) as DocumentKind[]).map(k => ({ value: k, label: DOCUMENT_KINDS[k] }))]} />
          <CheckField label="Private" checked={isPrivate} onChange={setPrivate} />
          <Button size="sm" loading={up.isPending} onClick={() => up.mutate(file)}>Upload and read</Button>
          <Button size="sm" variant="ghost" onClick={() => { setFile(null); onDone(null) }}>Cancel</Button>
        </div>
      )}
      {up.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(up.error, 'Could not upload the file.')}</p>}
    </section>
  )
}

function DocumentForm({ doc, doctors, conditions, onDone }: { doc: VitaDocument; doctors: { id: string; name: string }[]; conditions: { id: string; name: string }[]; onDone: () => void }) {
  const [v, setV] = useState({ kind: doc.kind, title: doc.title ?? '', documentDate: doc.documentDate ?? '', doctorId: doc.doctorId ?? '', conditionId: doc.conditionId ?? '', private: !!doc.private, notes: doc.notes ?? '' })
  const body: DocumentInput = { kind: v.kind, title: v.title.trim() || null, documentDate: v.documentDate || null, doctorId: v.doctorId || null, conditionId: v.conditionId || null, private: v.private, notes: v.notes.trim() || null }
  const save = useMutation({ mutationFn: () => vitaApi.updateDocument(doc.id, body), onSuccess: onDone })
  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label="Edit document details" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="Title" value={v.title} maxLength={200} onChange={title => setV({ ...v, title })} />
        <SelectField label="Kind" value={v.kind} onChange={kind => setV({ ...v, kind })} options={(Object.keys(DOCUMENT_KINDS) as DocumentKind[]).map(k => ({ value: k, label: DOCUMENT_KINDS[k] }))} />
        <TextField label="Date on it" type="date" value={v.documentDate} onChange={documentDate => setV({ ...v, documentDate })} />
        <SelectField label="Doctor" value={v.doctorId} onChange={doctorId => setV({ ...v, doctorId })} options={[{ value: '', label: 'Not said' }, ...doctors.map(d => ({ value: d.id, label: d.name }))]} />
        <SelectField label="Condition" value={v.conditionId} onChange={conditionId => setV({ ...v, conditionId })} options={[{ value: '', label: 'Not said' }, ...conditions.map(c => ({ value: c.id, label: c.name }))]} />
        <TextField label="Notes" value={v.notes} maxLength={1000} onChange={notes => setV({ ...v, notes })} />
      </div>
      <CheckField label="Private: only the record’s manager sees it" checked={v.private} onChange={p => setV({ ...v, private: p })} />
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the details.')}</p>}
    </form>
  )
}

export function MoveDocument({ personId, doc, onDone }: { personId: string; doc: VitaDocument; onDone: () => void }) {
  const people = useVitaPeople()
  const others = splitPeople(people.data ?? []).open.filter(p => p.id !== personId)
  const [to, setTo] = useState('')
  const move = useMutation({ mutationFn: () => vitaApi.moveDocument(doc.id, to), onSuccess: onDone })
  return (
    <form className="flex flex-col gap-2 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label="Move the document" onSubmit={e => { e.preventDefault(); move.mutate() }}>
      <p className="text-sm text-gray-800 dark:text-gray-100">Move “{doc.title || DOCUMENT_KINDS[doc.kind]}” to the person it belongs to.</p>
      {!others.length && <p className="text-sm text-gray-500 dark:text-gray-400">There is no one else in this household yet.</p>}
      {others.length > 0 && <SelectField label="Move to" value={to} onChange={setTo} className="max-w-xs" options={[{ value: '', label: 'Pick a person' }, ...others.map(p => ({ value: p.id, label: p.displayName }))]} />}
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={!to} loading={move.isPending}>Move document</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {move.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(move.error, 'Could not move the document.')}</p>}
    </form>
  )
}
