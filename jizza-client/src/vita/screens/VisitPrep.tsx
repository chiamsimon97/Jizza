import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Printer } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { CheckField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { AdherenceResult, MedicineSchedule, PrepLab, PrepMedicine, PrepMedicineChange, PrepPerson, PrepReading } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { LabFlagPill } from '../components/Labs'
import { StatusBadge, TargetLabel } from '../components/Readings'
import { formatWhen, useUnits, type Units } from '../data'
import { BLOOD_GROUPS, SEXES } from '../labels'
import { scheduleWords } from '../medicines'
import { CONTEXTS, kindLabel, showValue } from '../readings'
import { vitaKeys } from '../queryKeys'
import { adherenceWords } from './Medicines'

/** Only the sheet is printed. */
export const PRINT_CSS = `@media print {
  body * { visibility: hidden !important; }
  #vita-sheet, #vita-sheet * { visibility: visible !important; }
  #vita-sheet { position: absolute; left: 0; top: 0; width: 100%; color: #000 !important; background: #fff !important; box-shadow: none !important; border: 0 !important; }
  #vita-sheet .no-print { display: none !important; }
  @page { margin: 14mm; }
}`

const FLAG_LABEL: Record<string, string> = { high: 'high', low: 'low', critical: 'critical', normal: 'normal', unknown: 'no flag' }

/**
 * Visit prep (7.10): what to take to an upcoming visit, prepared from the record when it opens.
 * Nothing is sent to the doctor; the page is printed or shown.
 */
export function VisitPrepPage({ personId, visitId, onBack }: { personId: string; visitId: string | null | undefined; onBack: () => void }) {
  const units = useUnits()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: vitaKeys.prep(personId, visitId ?? ''), queryFn: () => vitaApi.prepareVisit(visitId as string), enabled: !!visitId, staleTime: 60_000,
  })
  const back = <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft className="h-3.5 w-3.5" aria-hidden />Doctors and visits</Button>
  if (!visitId) return <Panel title="No visit picked">{back}</Panel>
  if (isLoading) return <SkeletonRows rows={6} />
  if (error || !data) return <><div>{back}</div><LoadFailed text="Could not prepare this visit." onRetry={() => refetch()} /></>
  const d = data.doctor
  return (
    <div className="flex flex-col gap-4">
      <style>{PRINT_CSS}</style>
      <div>{back}</div>
      <ViewTitle title="Prepare for the visit" sub="From the record as it is now. Nothing is sent to the doctor: print it or show it.">
        <Button size="sm" variant="secondary" onClick={() => window.print()}><Printer className="h-3.5 w-3.5" aria-hidden />Print or save as PDF</Button>
      </ViewTitle>
      <Sheet title={`Visit on ${formatWhen(data.visit.scheduledAt)}`}
        sub={[d ? `${d.name}${d.speciality ? `, ${d.speciality}` : ''}${d.clinic ? `, ${d.clinic}` : ''}` : 'Doctor not named', data.visit.reason].filter(Boolean).join(' · ')}
        person={data.person} note={data.note}>
        <Section title="Questions to ask">
          {data.questions.length
            ? <ol className="list-decimal pl-5 text-sm">{data.questions.map((q, i) => <li key={i}>{q}</li>)}</ol>
            : <Empty>No questions written yet. Add them to the visit under Doctors and visits.</Empty>}
        </Section>
        <ReadingsSection readings={data.readings} units={units} />
        <MedicinesSection medicines={data.medicines} adherence={data.adherence} />
        <ChangesSection title={`Medicine changes ${data.changesSinceLabel}`} changes={data.medicineChanges} />
        <LabsSection labs={data.labs} />
      </Sheet>
    </div>
  )
}

/** The print-ready health summary (7.10): the whole record, or one condition's part of it. */
export function SummaryPage({ personId, conditionId, onBack, backLabel }: { personId: string; conditionId?: string | null; onBack: () => void; backLabel: string }) {
  const units = useUnits()
  const [includePrivate, setIncludePrivate] = useState(false)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: vitaKeys.summaryPage(personId, conditionId ?? '', includePrivate),
    queryFn: () => vitaApi.summary(personId, { condition: conditionId, includePrivate }),
  })
  const back = <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft className="h-3.5 w-3.5" aria-hidden />{backLabel}</Button>
  return (
    <div className="flex flex-col gap-4">
      <style>{PRINT_CSS}</style>
      <div>{back}</div>
      <ViewTitle title="Health summary" sub="One page to hand to a doctor or a hospital. Nothing is sent: print it or show it.">
        <Button size="sm" variant="secondary" disabled={!data} onClick={() => window.print()}><Printer className="h-3.5 w-3.5" aria-hidden />Print or save as PDF</Button>
      </ViewTitle>
      <CheckField label="Include medicines marked private" checked={includePrivate} onChange={setIncludePrivate} />
      {isLoading && <SkeletonRows rows={6} />}
      {error && <LoadFailed text="Could not prepare the summary." onRetry={() => refetch()} />}
      {data && (
        <Sheet title={data.condition ? `Health summary: ${data.condition.name}` : 'Health summary'} sub={`Prepared on ${formatDay(data.preparedOn)}`} person={data.person} note={data.note}>
          <Section title="Allergies">
            {data.allergies.length
              ? <ul className="text-sm">{data.allergies.map(a => <li key={a.substance}><b className="font-medium">{a.substance}</b>{a.severity ? ` · ${a.severity}` : ''}{a.reaction ? ` · ${a.reaction}` : ''}</li>)}</ul>
              : <Empty>None recorded.</Empty>}
          </Section>
          {!data.condition && (
            <Section title="Conditions">
              {data.conditions.length
                ? <ul className="text-sm">{data.conditions.map(c => <li key={c.name}>{c.name}{c.since ? `, since ${formatDay(c.since)}` : ''}</li>)}</ul>
                : <Empty>None recorded.</Empty>}
            </Section>
          )}
          <MedicinesSection medicines={data.medicines} adherence={data.adherence} />
          <ChangesSection title="Medicine changes in the last months" changes={data.medicineChanges} />
          <ReadingsSection readings={data.readings} units={units} />
          <LabsSection labs={data.labs} />
          {data.nextVisit && (
            <Section title={`Next visit: ${formatWhen(data.nextVisit.scheduledAt)}`}>
              {data.nextVisit.questions.length
                ? <ol className="list-decimal pl-5 text-sm">{data.nextVisit.questions.map((q, i) => <li key={i}>{q}</li>)}</ol>
                : <Empty>No questions written yet.</Empty>}
            </Section>
          )}
        </Sheet>
      )}
    </div>
  )
}

function personLine(p: PrepPerson): string {
  return [p.age != null ? `${p.age} years` : null, p.sexAtBirth ? SEXES[p.sexAtBirth as keyof typeof SEXES] ?? p.sexAtBirth : null,
    p.bloodGroup ? `Blood group ${BLOOD_GROUPS[p.bloodGroup as keyof typeof BLOOD_GROUPS] ?? p.bloodGroup}` : null,
    p.heightCm ? `${p.heightCm} cm` : null, p.dob ? `Born ${formatDay(p.dob)}` : null].filter(Boolean).join(' · ')
}

function Sheet({ title, sub, person, note, children }: { title: string; sub: string; person: PrepPerson; note: string; children: ReactNode }) {
  return (
    <article id="vita-sheet" className="flex flex-col gap-4 rounded-2xl border border-gray-200 bg-white p-5 dark:border-gray-800 dark:bg-gray-900">
      <header className="flex flex-col gap-1 border-b border-gray-200 pb-3 dark:border-gray-800">
        <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">{person.name}</h3>
        {personLine(person) && <p className="text-sm text-gray-600 dark:text-gray-300">{personLine(person)}</p>}
        <p className="text-sm font-medium text-gray-800 dark:text-gray-100">{title}</p>
        {sub && <p className="text-xs text-gray-500 dark:text-gray-400">{sub}</p>}
      </header>
      {children}
      <footer className="border-t border-gray-200 pt-2 text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">{note}</footer>
    </article>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5 break-inside-avoid">
      <h4 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h4>
      {children}
    </section>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="text-sm text-gray-500 dark:text-gray-400">{children}</p>
}

function ReadingsSection({ readings, units }: { readings: PrepReading[]; units: Units }) {
  return (
    <Section title="Readings, last 30 days">
      {!readings.length && <Empty>No readings in the last 30 days.</Empty>}
      <ul className="flex flex-col gap-1.5">
        {readings.map(r => (
          <li key={`${r.kind}-${r.context ?? ''}`} className="flex flex-col gap-0.5 text-sm">
            <span className="flex flex-wrap items-center gap-2">
              <b className="font-medium">{kindLabel(r.kind)}{r.context && r.context in CONTEXTS ? `, ${CONTEXTS[r.context as keyof typeof CONTEXTS].toLowerCase()}` : ''}</b>
              {r.average != null && <span>average <span className="font-mono">{showValue(r.kind, r.average, r.average2 ?? null, units)}</span></span>}
              <span className="text-xs text-gray-500 dark:text-gray-400">{r.count} readings{r.outside ? `, ${r.outside} outside the target` : ''}</span>
            </span>
            {r.last && <span className="flex flex-wrap items-center gap-2 text-xs text-gray-600 dark:text-gray-300">Last {formatWhen(r.last.takenAt)}: <span className="font-mono">{showValue(r.kind, r.last.value, r.last.value2, units)}</span><StatusBadge status={r.last.status} /></span>}
            {r.range && (r.range.status === 'not_set' ? <TargetLabel source={null} label={r.range.label} /> : <TargetLabel source={r.range.source} label={r.range.label} needsVerification={r.range.needsVerification} range={r.range.range} />)}
          </li>
        ))}
      </ul>
    </Section>
  )
}

function MedicinesSection({ medicines, adherence }: { medicines: PrepMedicine[]; adherence: AdherenceResult | null }) {
  return (
    <Section title="Medicines now">
      {!medicines.length && <Empty>No current medicines recorded.</Empty>}
      <ul className="flex flex-col gap-1 text-sm">
        {medicines.map((m, i) => (
          <li key={`${m.name}-${i}`}>
            <b className="font-medium">{m.name}{m.strength ? ` ${m.strength}` : ''}</b>
            <span className="text-gray-600 dark:text-gray-300"> · {m.asNeeded ? 'only when needed' : scheduleWords((m.schedule ?? null) as MedicineSchedule | null)}{m.status === 'paused' ? ' (paused)' : ''}</span>
          </li>
        ))}
      </ul>
      {adherence && adherence.due > 0 && <p className="text-xs text-gray-600 dark:text-gray-300">Doses in the last 30 days: {adherenceWords(adherence)}</p>}
    </Section>
  )
}

function ChangesSection({ title, changes }: { title: string; changes: PrepMedicineChange[] }) {
  return (
    <Section title={title}>
      {changes.length
        ? <ul className="flex flex-col gap-0.5 text-sm">{changes.map((c, i) => <li key={i}><span className="text-gray-500 dark:text-gray-400">{formatDay(c.onDate)}:</span> {c.line}</li>)}</ul>
        : <Empty>No changes.</Empty>}
    </Section>
  )
}

function LabsSection({ labs }: { labs: PrepLab[] }) {
  return (
    <Section title="Latest lab results">
      {!labs.length && <Empty>No lab results yet.</Empty>}
      <ul className="flex flex-col gap-1">
        {labs.map(l => (
          <li key={l.code} className="flex flex-wrap items-center gap-2 text-sm">
            <b className="font-medium">{l.name}</b>
            <span className="font-mono">{l.value ?? '—'} <span className="font-sans text-xs text-gray-500 dark:text-gray-400">{l.unit}</span></span>
            <LabFlagPill flag={l.flag} label={`Lab: ${FLAG_LABEL[l.flag] ?? l.flag}${l.rangeAsPrinted ? ` (${l.rangeAsPrinted})` : ''}`} />
            <span className="text-xs text-gray-500 dark:text-gray-400">{formatDay(l.date)}</span>
          </li>
        ))}
      </ul>
    </Section>
  )
}
