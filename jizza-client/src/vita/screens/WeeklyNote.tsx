import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { CalendarClock, ChevronLeft, ChevronRight, FlaskConical, Pill, RefreshCw, TriangleAlert, XCircle, type LucideIcon } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { formatDay } from '@/portfolio/components/AsOf'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { NoteSentence } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { formatWhen } from '../data'
import { localDay } from '../medicines'
import { vitaKeys } from '../queryKeys'

const KIND_ICON: Record<string, LucideIcon> = {
  medicine_change: Pill, lab_report: FlaskConical, missed_doses: XCircle, outside_range: TriangleAlert, visit: CalendarClock, refill: RefreshCw,
}
/** Where each kind of row shows in the Health tab. */
const TABLE_VIEW: Record<string, { view: string; label: string }> = {
  vita_medicine: { view: 'medicines', label: 'Medicines' },
  vita_medicine_event: { view: 'medicines', label: 'Medicines' },
  vita_dose_log: { view: 'medicines', label: 'Medicines' },
  vita_measurement: { view: 'vitals', label: 'Vitals' },
  vita_visit: { view: 'visits', label: 'Doctors and visits' },
  vita_doctor: { view: 'visits', label: 'Doctors and visits' },
  vita_lab_report: { view: 'lab', label: 'Lab report' },
}

export function mondayOf(day: string): string {
  const d = new Date(`${day}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7))
  return d.toISOString().slice(0, 10)
}
const shiftWeek = (monday: string, by: number) => {
  const d = new Date(`${monday}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + 7 * by)
  return d.toISOString().slice(0, 10)
}

/**
 * The weekly note (Phase 4): what changed in the record this week, each sentence built from rows
 * that can be opened, with the disclaimer. Vita writes no sentence without a row behind it; this
 * shows the server's sentences as they are. Only whoever keeps the record and the person read it.
 */
export function WeeklyNote({ personId, personName: name, onOpen, onOpenReport }: {
  personId: string; personName: string; onOpen: (view: string) => void; onOpenReport: (id: string) => void
}) {
  const [params] = useSearchParams()
  const thisWeek = mondayOf(localDay(0))
  const asked = params.get('week')
  const [week, setWeek] = useState(asked && /^\d{4}-\d{2}-\d{2}$/.test(asked) ? mondayOf(asked) : thisWeek)
  const note = useQuery({ queryKey: vitaKeys.weeklyNote(personId, week), queryFn: () => vitaApi.weeklyNote(personId, week) })
  const you = name === 'You'
  const n = note.data

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Weekly note" sub={`What changed in ${you ? 'your' : `${name}’s`} record, Monday to Sunday`} />
      <Panel title={n ? `${formatDay(n.weekStart)} to ${formatDay(n.weekEnd)}` : 'This week'} action={
        <div className="flex gap-1">
          <button type="button" aria-label="Previous week" onClick={() => setWeek(w => shiftWeek(w, -1))} className="rounded-lg p-1 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><ChevronLeft className="h-4 w-4" aria-hidden /></button>
          <button type="button" aria-label="Next week" disabled={week >= thisWeek} onClick={() => setWeek(w => shiftWeek(w, 1))} className="rounded-lg p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:hover:bg-gray-800"><ChevronRight className="h-4 w-4" aria-hidden /></button>
        </div>
      }>
        {note.isLoading && <SkeletonRows rows={3} />}
        {note.error && <LoadFailed text="Could not load the weekly note." onRetry={() => note.refetch()} />}
        {n && (
          <>
            <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">
              {n.sentAt ? `Sent ${formatWhen(n.sentAt)}.` : week === thisWeek ? `So far this week${n.until ? `, up to ${formatDay(n.until.slice(0, 10))}` : ''}. It is sent on Sunday evening.` : 'Made from the record as it is now.'}
            </p>
            {n.empty
              ? <p className="text-sm text-gray-600 dark:text-gray-300">{n.message}</p>
              : <ul className="flex flex-col gap-2">{n.sentences.map((s, i) => <Sentence key={i} s={s} onOpen={onOpen} onOpenReport={onOpenReport} />)}</ul>}
            <p className="text-xs text-gray-500 dark:text-gray-400">{n.disclaimer}</p>
          </>
        )}
      </Panel>
    </div>
  )
}

function Sentence({ s, onOpen, onOpenReport }: { s: NoteSentence; onOpen: (view: string) => void; onOpenReport: (id: string) => void }) {
  const Icon = KIND_ICON[s.kind] ?? CalendarClock
  const places: { key: string; label: string; go: () => void }[] = []
  for (const r of s.rows) {
    const t = TABLE_VIEW[r.table]
    if (!t) continue
    const key = t.view === 'lab' ? `lab-${r.id}` : t.view
    if (places.some(p => p.key === key)) continue
    places.push({ key, label: t.label, go: () => (t.view === 'lab' ? onOpenReport(r.id) : onOpen(t.view)) })
  }
  return (
    <li className="flex gap-2 rounded-lg bg-gray-50 px-3 py-2 dark:bg-gray-800/60">
      <Icon className="mt-0.5 h-4 w-4 shrink-0 text-[rgb(var(--agent-health))]" aria-hidden />
      <div className="flex min-w-0 flex-col gap-0.5">
        <p className="text-sm text-gray-900 dark:text-gray-100">{s.text}</p>
        {places.length > 0 && (
          <p className="flex flex-wrap items-center gap-x-2 text-xs text-gray-500 dark:text-gray-400">
            From {s.rows.length} {s.rows.length === 1 ? 'entry' : 'entries'} in
            {places.map(p => <button key={p.key} type="button" onClick={p.go} className="text-brand-700 underline dark:text-brand-300">{p.label}</button>)}
          </p>
        )}
      </div>
    </li>
  )
}
