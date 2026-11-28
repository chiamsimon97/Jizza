import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Angry, ChevronLeft, ChevronRight, Frown, Laugh, Meh, Plus, Smile, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatDay } from '@/portfolio/components/AsOf'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { JournalEntry, JournalInput } from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { localDay } from '../medicines'
import { vitaKeys } from '../queryKeys'
import { useShareScope } from '../share'

const FACES = [Angry, Frown, Meh, Smile, Laugh]
export const MOOD_WORDS = ['Very low', 'Low', 'Okay', 'Good', 'Very good']
export const ENERGY_WORDS = ['Very low', 'Low', 'Some', 'Good', 'Full of energy']
/** Common symptoms offered as chips; anything else is typed. */
export const SYMPTOMS = ['Headache', 'Tired', 'Dizzy', 'Breathless', 'Cough', 'Fever', 'Nausea', 'Joint pain', 'Swelling', 'Poor sleep', 'Low appetite', 'Chest discomfort']
const MAX_SYMPTOMS = 20
const SYMPTOM_MAX = 40
const NOTES_MAX = 2000
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

export function painWords(n: number): string {
  if (n === 0) return 'No pain'
  if (n <= 3) return 'Mild'
  if (n <= 6) return 'Moderate'
  if (n <= 9) return 'Severe'
  return 'Worst possible'
}

const pad = (n: number) => String(n).padStart(2, '0')
const monthOf = (day: string) => day.slice(0, 7)
const daysIn = (month: string) => { const [y, m] = month.split('-').map(Number); return new Date(Date.UTC(y, m, 0)).getUTCDate() }
const shiftMonth = (month: string, by: number) => {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + by, 1))
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`
}
const monthWords = (month: string) => new Date(`${month}-01T00:00:00Z`).toLocaleDateString('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })

/**
 * Journal (7.12): a daily check-in in under 30 seconds (mood and energy as five faces, pain on a
 * 0-10 slider, symptom chips and a note) and a month grid of the days with entries. Nothing is
 * saved until Save is pressed. Only whoever keeps the record, and a share of the journal, reads it.
 */
export function Journal({ personId, personName: name, readOnly }: { personId: string; personName: string; readOnly: boolean }) {
  const share = useShareScope()
  const today = localDay(0)
  const [month, setMonth] = useState(monthOf(today))
  const [picked, setPicked] = useState(today)
  const from = `${month}-01`
  const last = `${month}-${pad(daysIn(month))}`
  const to = last > today ? today : last
  const entries = useQuery({ queryKey: vitaKeys.journal(personId, from, to), queryFn: () => vitaApi.journal(personId, from, to), enabled: from <= today })
  const byDay = useMemo(() => new Map((entries.data ?? []).map(e => [e.entryDate.slice(0, 10), e])), [entries.data])
  const entry = byDay.get(picked)
  const you = name === 'You'

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Journal" sub={`How ${you ? 'you' : name} felt each day. ${share.shared ? 'Shared with you by the person who keeps this record.' : 'Private unless you share the journal.'}`} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Panel title={picked === today ? 'Today' : formatDay(picked)}>
          {entries.isLoading && <SkeletonRows rows={3} />}
          {entries.error && <LoadFailed text="Could not load the journal." onRetry={() => entries.refetch()} />}
          {entries.data && (readOnly
            ? <EntryView entry={entry} />
            : <CheckIn key={picked} personId={personId} day={picked} entry={entry} canRemove={!share.shared} />)}
        </Panel>
        <MonthGrid month={month} today={today} picked={picked} byDay={byDay}
          onMonth={by => setMonth(m => shiftMonth(m, by))}
          onPick={d => setPicked(d)} />
      </div>
    </div>
  )
}

function Faces({ label, words, value, onChange }: { label: string; words: string[]; value: number | null; onChange: (v: number | null) => void }) {
  return (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="text-xs font-medium text-gray-600 dark:text-gray-300">{label}{value ? `: ${words[value - 1]}` : ''}</legend>
      <div className="flex flex-wrap gap-1.5">
        {FACES.map((Face, i) => {
          const n = i + 1
          const on = value === n
          return (
            <button key={n} type="button" aria-pressed={on} aria-label={`${label}: ${words[i]}`} title={words[i]}
              onClick={() => onChange(on ? null : n)}
              className={`flex w-16 flex-col items-center gap-0.5 rounded-xl border px-1 py-1.5 text-[11px] leading-tight transition-colors ${on
                ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:border-brand-400 dark:bg-brand-900/30 dark:text-brand-200'
                : 'border-gray-200 text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
              <Face className="h-6 w-6" aria-hidden />
              <span>{words[i]}</span>
            </button>
          )
        })}
      </div>
    </fieldset>
  )
}

function CheckIn({ personId, day, entry, canRemove }: { personId: string; day: string; entry?: JournalEntry; canRemove: boolean }) {
  const qc = useQueryClient()
  const [mood, setMood] = useState<number | null>(entry?.mood ?? null)
  const [energy, setEnergy] = useState<number | null>(entry?.energy ?? null)
  const [pain, setPain] = useState<number | null>(entry?.pain ?? null)
  const [symptoms, setSymptoms] = useState<string[]>(entry?.symptoms ?? [])
  const [typed, setTyped] = useState('')
  const [notes, setNotes] = useState(entry?.notes ?? '')
  const [saved, setSaved] = useState(false)
  const offered = [...SYMPTOMS, ...symptoms.filter(s => !SYMPTOMS.some(x => x.toLowerCase() === s.toLowerCase()))]
  const has = (s: string) => symptoms.some(x => x.toLowerCase() === s.toLowerCase())
  const toggle = (s: string) => { setSaved(false); setSymptoms(list => has(s) ? list.filter(x => x.toLowerCase() !== s.toLowerCase()) : list.length >= MAX_SYMPTOMS ? list : [...list, s]) }
  const addTyped = () => {
    const s = typed.trim().slice(0, SYMPTOM_MAX)
    if (s && !has(s)) toggle(s)
    setTyped('')
  }
  const empty = mood == null && energy == null && pain == null && !symptoms.length && !notes.trim()
  const done = () => { setSaved(true); qc.invalidateQueries({ queryKey: ['vita', personId, 'journal'] }) }
  const save = useMutation({
    mutationFn: () => {
      const body: JournalInput = { mood, energy, pain, symptoms, notes: notes.trim() || null }
      return vitaApi.putJournal(personId, day, body)
    },
    onSuccess: done,
  })
  const remove = useMutation({ mutationFn: () => vitaApi.removeJournal(personId, day), onSuccess: () => qc.invalidateQueries({ queryKey: ['vita', personId, 'journal'] }) })
  const touched = <T,>(set: (v: T) => void) => (v: T) => { setSaved(false); set(v) }

  return (
    <form className="flex flex-col gap-4" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <Faces label="Mood" words={MOOD_WORDS} value={mood} onChange={touched(setMood)} />
      <Faces label="Energy" words={ENERGY_WORDS} value={energy} onChange={touched(setEnergy)} />
      <div className="flex flex-col gap-1.5">
        <label htmlFor={`pain-${day}`} className="text-xs font-medium text-gray-600 dark:text-gray-300">
          Pain{pain == null ? ': not noted' : `: ${pain} of 10, ${painWords(pain).toLowerCase()}`}
        </label>
        <div className="flex items-center gap-3">
          <input id={`pain-${day}`} type="range" min={0} max={10} step={1} value={pain ?? 0}
            aria-valuetext={pain == null ? 'Not noted' : `${pain} of 10, ${painWords(pain)}`}
            onChange={e => touched(setPain)(Number(e.target.value))}
            onClick={e => { if (pain == null) touched(setPain)(Number(e.currentTarget.value)) }} className="w-full accent-brand-600" />
          {pain != null && <button type="button" className="shrink-0 text-xs text-gray-500 underline dark:text-gray-400" onClick={() => touched(setPain)(null)}>Clear</button>}
        </div>
        <div className="flex justify-between text-[11px] text-gray-400"><span>0 none</span><span>10 worst</span></div>
      </div>
      <fieldset className="flex flex-col gap-1.5">
        <legend className="text-xs font-medium text-gray-600 dark:text-gray-300">Symptoms{symptoms.length ? ` (${symptoms.length})` : ''}</legend>
        <div className="flex flex-wrap gap-1.5">
          {offered.map(s => <Chip key={s} on={has(s)} onClick={() => toggle(s)}>{s}</Chip>)}
        </div>
        <div className="flex gap-2">
          <input aria-label="Another symptom" placeholder="Another symptom" className={`${fieldClass} max-w-xs`} value={typed} maxLength={SYMPTOM_MAX}
            onChange={e => setTyped(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addTyped() } }} />
          <Button size="sm" variant="secondary" type="button" disabled={!typed.trim() || symptoms.length >= MAX_SYMPTOMS} onClick={addTyped}>
            <Plus className="h-3.5 w-3.5" aria-hidden />Add
          </Button>
        </div>
      </fieldset>
      <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
        Note
        <textarea className={`${fieldClass} min-h-[3.5rem]`} value={notes} maxLength={NOTES_MAX} onChange={e => touched(setNotes)(e.target.value)} />
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" size="sm" loading={save.isPending} disabled={empty}>{entry ? 'Save changes' : 'Save'}</Button>
        {entry && canRemove && (
          <Button type="button" size="sm" variant="ghost" loading={remove.isPending} onClick={() => remove.mutate()}>
            <Trash2 className="h-3.5 w-3.5" aria-hidden />Remove this day
          </Button>
        )}
        {saved && !save.isPending && <span role="status" className="text-sm text-gray-700 dark:text-gray-200">Saved.</span>}
        {empty && !entry && <span className="text-xs text-gray-500 dark:text-gray-400">Pick at least one thing to save the day.</span>}
      </div>
      {(save.error || remove.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error ?? remove.error, 'Could not save the day.')}</p>}
    </form>
  )
}

/** A day's entry for someone who can read the journal but not add to it. */
function EntryView({ entry }: { entry?: JournalEntry }) {
  if (!entry) return <p className="text-sm text-gray-500 dark:text-gray-400">Nothing noted for this day.</p>
  const row = (k: string, v: string) => (
    <div className="flex gap-2 text-sm"><dt className="w-20 shrink-0 text-gray-500 dark:text-gray-400">{k}</dt><dd className="text-gray-900 dark:text-gray-100">{v}</dd></div>
  )
  return (
    <dl className="flex flex-col gap-1.5">
      {entry.mood != null && row('Mood', MOOD_WORDS[entry.mood - 1])}
      {entry.energy != null && row('Energy', ENERGY_WORDS[entry.energy - 1])}
      {entry.pain != null && row('Pain', `${entry.pain} of 10, ${painWords(entry.pain).toLowerCase()}`)}
      {entry.symptoms.length > 0 && row('Symptoms', entry.symptoms.join(', '))}
      {entry.notes && row('Note', entry.notes)}
    </dl>
  )
}

function MonthGrid({ month, today, picked, byDay, onMonth, onPick }: {
  month: string; today: string; picked: string; byDay: Map<string, JournalEntry>
  onMonth: (by: number) => void; onPick: (day: string) => void
}) {
  const n = daysIn(month)
  const lead = (new Date(`${month}-01T00:00:00Z`).getUTCDay() + 6) % 7
  const count = [...byDay.keys()].filter(d => d.startsWith(month)).length
  useEffect(() => {
    // Keep the picked day inside the month on view.
    if (!picked.startsWith(month)) onPick(month === monthOf(today) ? today : `${month}-01`)
  }, [month]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Panel title={monthWords(month)} action={
      <div className="flex gap-1">
        <button type="button" aria-label="Previous month" onClick={() => onMonth(-1)} className="rounded-lg p-1 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><ChevronLeft className="h-4 w-4" aria-hidden /></button>
        <button type="button" aria-label="Next month" disabled={month >= monthOf(today)} onClick={() => onMonth(1)} className="rounded-lg p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:hover:bg-gray-800"><ChevronRight className="h-4 w-4" aria-hidden /></button>
      </div>
    }>
      <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">{count ? `${count} ${count === 1 ? 'day' : 'days'} noted. A day with a face has an entry.` : 'No days noted this month.'}</p>
      <div role="grid" aria-label={`Journal days in ${monthWords(month)}`} className="grid grid-cols-7 gap-1 text-center">
        {WEEKDAYS.map(w => <div key={w} role="columnheader" className="text-[11px] font-medium text-gray-400">{w}</div>)}
        {Array.from({ length: lead }, (_, i) => <div key={`x${i}`} aria-hidden />)}
        {Array.from({ length: n }, (_, i) => {
          const d = `${month}-${pad(i + 1)}`
          const e = byDay.get(d)
          const future = d > today
          const Face = e?.mood ? FACES[e.mood - 1] : null
          const words = `${formatDay(d)}${e ? `, noted${e.mood ? `, mood ${MOOD_WORDS[e.mood - 1].toLowerCase()}` : ''}` : ''}`
          return (
            <button key={d} type="button" role="gridcell" disabled={future} aria-selected={d === picked} aria-label={words} title={words}
              onClick={() => onPick(d)}
              className={`flex h-11 flex-col items-center justify-center rounded-lg text-xs transition-colors disabled:opacity-30 ${d === picked
                ? 'bg-brand-600 text-white'
                : e ? 'bg-brand-50 text-brand-800 hover:bg-brand-100 dark:bg-brand-900/30 dark:text-brand-200' : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
              <span className={d === today ? 'font-semibold underline' : ''}>{i + 1}</span>
              {e && (Face ? <Face className="h-3.5 w-3.5" aria-hidden /> : <span aria-hidden className="text-[10px] leading-none">●</span>)}
            </button>
          )
        })}
      </div>
    </Panel>
  )
}
