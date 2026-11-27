import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { ApiError } from '@/lib/api'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import type { Meal, Reading, ReadingContext, ReadingInput, ReadingKind, RedFlag } from '@/types/vita'
import { errorText, toLocalInput, useUnits, type Units } from '../data'
import { CONTEXTS, MEALS, READING_KINDS, inputUnit, showReading, showValue } from '../readings'
import { vitaKeys } from '../queryKeys'
import { RedFlagBanner } from './RedFlagBanner'
import { StatusBadge, TargetLabel } from './Readings'

interface Draft {
  value: string
  value2: string
  value3: string
  context: ReadingContext
  meal: Meal | ''
  setting: 'home' | 'clinic'
  when: string
  note: string
}

const EMPTY: Draft = { value: '', value2: '', value3: '', context: 'before_meal', meal: '', setting: 'home', when: '', note: '' }

function draftOf(r: Reading | undefined, units: Units): Draft {
  if (!r) return EMPTY
  const shown = (v: number) => {
    if (r.kind === 'weight' && units.weight === 'lb') return (v / 0.45359237).toFixed(1)
    if (r.kind === 'temperature' && units.temperature === 'F') return (v * 9 / 5 + 32).toFixed(1)
    return String(Number(v.toFixed(2)))
  }
  return {
    value: shown(r.value), value2: r.value2 == null ? '' : String(Math.round(r.value2)), value3: r.value3 == null ? '' : String(Math.round(r.value3)),
    context: r.context ?? 'before_meal', meal: r.meal ?? '', setting: r.setting ?? 'home', when: toLocalInput(r.takenAt), note: r.note ?? '',
  }
}

export function bodyOf(kind: ReadingKind, d: Draft, units: Units, editing = false): ReadingInput {
  const body: ReadingInput = { kind, value: d.value.trim(), unit: inputUnit(kind, units) }
  if (kind === 'bp') {
    body.value2 = d.value2.trim()
    body.value3 = d.value3.trim() || null
    body.setting = d.setting
  }
  if (kind === 'glucose') {
    body.context = d.context
    body.meal = (d.context === 'before_meal' || d.context === 'after_meal') && d.meal ? d.meal : null
  }
  if (d.when) body.takenAt = d.when
  if (d.note.trim() || editing) body.note = d.note.trim() || null
  return body
}

function ready(kind: ReadingKind, d: Draft) {
  return !!d.value.trim() && (kind !== 'bp' || !!d.value2.trim())
}

type Step =
  | { at: 'form' }
  | { at: 'duplicate'; message: string }
  | { at: 'confirm'; reading: Reading }
  | { at: 'wrong'; reading: Reading }
  | { at: 'help'; reading: Reading; help: RedFlag; alerted: boolean }
  | { at: 'saved'; reading: Reading; first?: Reading }

/**
 * Add a reading (7.7): one row form, then what the server says. The same values a moment ago ask
 * "Already saved. Add anyway?"; a typed red flag asks "Is 182/112 right?" before anyone is alerted,
 * and only a yes shows the help and alerts; BP offers a second reading and shows the average.
 * `editing` corrects a reading instead: its kind cannot change.
 */
export function ReadingEntry({ personId, kind: startKind, editing, lockKind = false, onDone, onOpenCard }: {
  personId: string
  kind: ReadingKind
  editing?: Reading
  lockKind?: boolean
  onDone: () => void
  onOpenCard?: () => void
}) {
  const qc = useQueryClient()
  const units = useUnits()
  const [kind, setKind] = useState<ReadingKind>(editing?.kind ?? startKind)
  const [d, setD] = useState<Draft>(() => draftOf(editing, units))
  const [step, setStep] = useState<Step>({ at: 'form' })
  const [secondOf, setSecondOf] = useState<Reading | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const set = (patch: Partial<Draft>) => setD(v => ({ ...v, ...patch }))

  const afterSave = (r: Reading) => {
    refresh()
    if (r.confirmNeeded) setStep({ at: 'confirm', reading: r })
    else setStep({ at: 'saved', reading: r, first: secondOf ?? undefined })
  }

  const save = useMutation({
    mutationFn: (confirmDuplicate: boolean) => editing
      ? vitaApi.updateReading(personId, editing.id, bodyOf(kind, d, units, true))
      : vitaApi.addReading(personId, bodyOf(kind, d, units), { secondOf: secondOf?.id, confirmDuplicate }),
    onSuccess: afterSave,
    onError: e => { if (e instanceof ApiError && e.status === 409) setStep({ at: 'duplicate', message: e.message }) },
  })

  const answer = useMutation({
    mutationFn: ({ reading, right, signsNow }: { reading: Reading; right: boolean; signsNow?: boolean }) =>
      vitaApi.answerRedFlag(personId, reading.id, right, signsNow ?? null),
    onSuccess: (out, { reading, right }) => {
      refresh()
      if (!right) setStep({ at: 'wrong', reading })
      else if (out.help) setStep({ at: 'help', reading, help: out.help, alerted: out.alerted })
      else setStep({ at: 'saved', reading })
    },
  })

  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeMeasurement(personId, id), onSuccess: () => { refresh(); onDone() } })

  if (step.at === 'confirm') {
    return (
      <div role="alertdialog" aria-label="Check the reading" className="flex flex-col gap-3 rounded-xl border-2 border-red-500 bg-red-50 p-3 dark:bg-red-950/40">
        <p className="text-base font-semibold text-red-950 dark:text-red-50">{step.reading.confirmQuestion ?? `Is ${showReading(step.reading, units)} right?`}</p>
        <p className="text-sm text-red-900 dark:text-red-100">Nobody is alerted until you say it is right.</p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="danger" loading={answer.isPending && answer.variables?.right} onClick={() => answer.mutate({ reading: step.reading, right: true })}>Yes, it’s right</Button>
          <Button size="sm" variant="secondary" loading={answer.isPending && !answer.variables?.right} onClick={() => answer.mutate({ reading: step.reading, right: false })}>No, I typed it wrong</Button>
        </div>
        {answer.error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{errorText(answer.error, 'Could not send the answer.')}</p>}
      </div>
    )
  }

  if (step.at === 'wrong') {
    return (
      <div className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50">
        <p className="text-sm text-gray-800 dark:text-gray-100">Nobody was alerted. Correct the reading or delete it.</p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={() => { setStep({ at: 'form' }); setD(draftOf(step.reading, units)) }}>Correct it</Button>
          <Button size="sm" variant="secondary" loading={remove.isPending} onClick={() => remove.mutate(step.reading.id)}>Delete it</Button>
        </div>
        {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the reading.')}</p>}
      </div>
    )
  }

  if (step.at === 'help') {
    const raise = step.help.level === 'urgent' && step.help.question && step.help.signs?.length
      ? () => answer.mutate({ reading: step.reading, right: true, signsNow: true }) : undefined
    return (
      <div className="flex flex-col gap-2">
        <RedFlagBanner flag={{ ...step.help, reading: showReading(step.reading, units) }} onRaise={raise} onOpenCard={onOpenCard} />
        <p className="text-xs text-gray-500 dark:text-gray-400">{step.alerted ? 'The record’s manager was alerted.' : 'The record’s manager already knows about this reading.'}</p>
        <div><Button size="sm" variant="secondary" onClick={onDone}>Close</Button></div>
      </div>
    )
  }

  if (step.at === 'saved') {
    const r = step.reading
    const first = step.first
    const avg = first && r.kind === 'bp'
      ? showValue('bp', (first.value + r.value) / 2, first.value2 != null && r.value2 != null ? (first.value2 + r.value2) / 2 : null, units)
      : first ? showValue(r.kind, (first.value + r.value) / 2, null, units) : null
    return (
      <div role="status" className="flex flex-col gap-2 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50">
        <p className="text-sm text-gray-800 dark:text-gray-100">{editing ? 'Corrected' : 'Saved'}: <b className="font-mono">{showReading(r, units)}</b></p>
        {avg && <p className="text-sm text-gray-800 dark:text-gray-100">Average of the two: <b className="font-mono">{avg}</b></p>}
        {r.evaluation && (
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={r.evaluation.status} outside={r.evaluation.outside} />
            <TargetLabel source={r.evaluation.status === 'not_set' ? null : r.evaluation.rangeSource} label={r.evaluation.label} needsVerification={r.evaluation.needsVerification} range={r.evaluation.range} />
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          {!editing && !first && r.kind === 'bp' && (
            <Button size="sm" variant="secondary" onClick={() => { setSecondOf(r); setD({ ...EMPTY, setting: d.setting }); setStep({ at: 'form' }) }}>Add a second reading</Button>
          )}
          <Button size="sm" onClick={onDone}>Done</Button>
        </div>
      </div>
    )
  }

  return (
    <form className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" aria-label={editing ? 'Correct the reading' : 'Add a reading'}
      onSubmit={e => { e.preventDefault(); save.mutate(false) }}>
      {secondOf && <p className="text-sm text-gray-700 dark:text-gray-200">Second reading, a minute or two after <b className="font-mono">{showReading(secondOf, units)}</b>. Vita shows the average of the two.</p>}
      {!editing && !lockKind && !secondOf && (
        <SelectField label="Reading" value={kind} onChange={k => { setKind(k); setD(EMPTY) }}
          options={READING_KINDS.map(k => ({ value: k.kind, label: k.label }))} />
      )}
      <Fields kind={kind} d={d} set={set} units={units} />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField label="When (leave empty for now)" type="datetime-local" value={d.when} onChange={when => set({ when })} />
        <TextField label="Note (optional)" value={d.note} maxLength={500} onChange={note => set({ note })} />
      </div>
      {step.at === 'duplicate' && (
        <div role="alert" className="flex flex-wrap items-center gap-2 rounded-lg bg-yellow-50 p-2 text-sm text-yellow-900 dark:bg-yellow-900/30 dark:text-yellow-100">
          Already saved. Add anyway?
          <Button size="sm" type="button" loading={save.isPending} onClick={() => save.mutate(true)}>Add anyway</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Don’t add</Button>
        </div>
      )}
      {step.at !== 'duplicate' && (
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={!ready(kind, d)}>{editing ? 'Save correction' : 'Save reading'}</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
        </div>
      )}
      {save.error && !(save.error instanceof ApiError && save.error.status === 409) && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the reading.')}</p>
      )}
    </form>
  )
}

function Fields({ kind, d, set, units }: { kind: ReadingKind; d: Draft; set: (p: Partial<Draft>) => void; units: Units }) {
  if (kind === 'bp') {
    return (
      <div className="grid gap-3 sm:grid-cols-4">
        <TextField label="Systolic (top)" value={d.value} maxLength={3} placeholder="132" onChange={value => set({ value })} />
        <TextField label="Diastolic (bottom)" value={d.value2} maxLength={3} placeholder="84" onChange={value2 => set({ value2 })} />
        <TextField label="Pulse (optional)" value={d.value3} maxLength={3} placeholder="72" onChange={value3 => set({ value3 })} />
        <SelectField label="Where" value={d.setting} onChange={setting => set({ setting })}
          options={[{ value: 'home', label: 'At home' }, { value: 'clinic', label: 'At the clinic' }]} />
      </div>
    )
  }
  if (kind === 'glucose') {
    const withMeal = d.context === 'before_meal' || d.context === 'after_meal'
    return (
      <div className="grid gap-3 sm:grid-cols-3">
        <TextField label="Sugar (mg/dL)" value={d.value} maxLength={5} placeholder="118" onChange={value => set({ value })} />
        <SelectField label="When taken" value={d.context} onChange={context => set({ context })}
          options={(Object.keys(CONTEXTS) as ReadingContext[]).map(c => ({ value: c, label: CONTEXTS[c] }))} />
        {withMeal && (
          <SelectField label="Which meal" value={d.meal} onChange={meal => set({ meal })}
            options={[{ value: '' as const, label: 'Not said' }, ...(Object.keys(MEALS) as Meal[]).map(m => ({ value: m, label: MEALS[m] }))]} />
        )}
      </div>
    )
  }
  const unit = inputUnit(kind, units)
  const hint: Record<string, string> = { weight: unit === 'kg' ? '64.5' : '142', heart_rate: '72', spo2: '97', temperature: unit === 'F' ? '98.6' : '37.0' }
  const label: Record<string, string> = { weight: `Weight (${unit})`, heart_rate: 'Heart rate (bpm)', spo2: 'Oxygen (%)', temperature: `Temperature (°${unit})` }
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <TextField label={label[kind]} value={d.value} maxLength={6} placeholder={hint[kind]} onChange={value => set({ value })} />
    </div>
  )
}
