import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Circle, CirclePause, Flag, MinusCircle, Stethoscope, Target, Trophy, XCircle } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { formatDay } from '@/portfolio/components/AsOf'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { GoalCheckin, GoalContext, GoalInput, GoalKind, GoalStatus, GoalTarget, HealthGoal } from '@/types/vita'
import { NeedsVerification } from '../components/FigureLabel'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { localDay } from '../medicines'
import { vitaKeys } from '../queryKeys'
import { useShareScope } from '../share'

export const GOAL_KINDS: Record<GoalKind, string> = {
  weight: 'Weight', steps: 'Steps', sleep_hours: 'Sleep', water: 'Water',
  bp_range: 'Blood pressure in range', glucose_range: 'Sugar in range', hba1c: 'HbA1c', custom: 'Something else',
}
const MEDICAL: GoalKind[] = ['bp_range', 'glucose_range', 'hba1c']
const CONTEXTS: Record<GoalContext, string> = { before_meal: 'before a meal', after_meal: 'after a meal' }
const DEFAULT_UNIT: Partial<Record<GoalKind, string>> = { weight: 'kg', steps: 'steps a day', sleep_hours: 'hours a night', water: 'glasses a day' }
const STATUS: Record<GoalStatus, { label: string; icon: typeof Flag; tone: 'ok' | 'info' | 'neutral' }> = {
  active: { label: 'Active', icon: Flag, tone: 'info' },
  paused: { label: 'Paused', icon: CirclePause, tone: 'neutral' },
  met: { label: 'Met', icon: Trophy, tone: 'ok' },
  dropped: { label: 'Stopped', icon: MinusCircle, tone: 'neutral' },
}
const PLAN_LINES = 20
const LINE_MAX = 300

export function goalTitle(g: Pick<HealthGoal, 'kind' | 'title' | 'context'>): string {
  if (g.title) return g.title
  const base = GOAL_KINDS[g.kind]
  return g.kind === 'glucose_range' && g.context ? `${base} ${CONTEXTS[g.context]}` : base
}

const num = (n: number | null | undefined) => (n == null ? '' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: 1 }))
export function weekValue(g: Pick<HealthGoal, 'kind' | 'unit'>, c: Pick<GoalCheckin, 'value' | 'value2'>): string {
  if (c.value == null) return 'No value'
  if (g.kind === 'bp_range') return `${num(c.value)}/${num(c.value2)} mmHg`
  if (g.kind === 'hba1c') return `${num(c.value)}%`
  if (g.kind === 'glucose_range') return `${num(c.value)} mg/dL`
  return `${num(c.value)}${g.unit ? ` ${g.unit}` : ''}`
}
const meetsWords = (m: boolean | null) => (m === true ? 'meets the goal' : m === false ? 'does not meet it yet' : 'nothing to compare')

/** Mondays (India time) from this week back, as yyyy-mm-dd. */
function recentMondays(count: number): string[] {
  const today = localDay(0)
  const d = new Date(`${today}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7))
  return Array.from({ length: count }, (_, i) => {
    const m = new Date(d)
    m.setUTCDate(d.getUTCDate() - 7 * i)
    return m.toISOString().slice(0, 10)
  })
}

/**
 * Goals (7.14, 5.7): goal cards with the target and where it comes from, the latest weekly value,
 * the last 12 weeks as marks that carry their meaning in words too, and the status. A goal for blood
 * pressure, sugar or HbA1c shows only the doctor's target or Vita's default from config, never a
 * figure the screen makes up; the weekly plan is what the user typed.
 */
export function Goals({ personId, personName: name, readOnly }: { personId: string; personName: string; readOnly: boolean }) {
  const share = useShareScope()
  const goals = useQuery({ queryKey: vitaKeys.goals(personId), queryFn: () => vitaApi.goals(personId) })
  const [adding, setAdding] = useState(false)
  const you = name === 'You'
  const list = goals.data ?? []
  const open = list.filter(g => g.status === 'active' || g.status === 'paused')
  const ended = list.filter(g => g.status === 'met' || g.status === 'dropped')

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Goals" sub={`Targets for ${you ? 'you' : name}, a weekly plan, and a check-in each week`}>
        {!readOnly && !adding && <Button size="sm" onClick={() => setAdding(true)}>Add a goal</Button>}
      </ViewTitle>
      {adding && <NewGoal personId={personId} onDone={() => setAdding(false)} />}
      {goals.isLoading && <SkeletonRows rows={3} />}
      {goals.error && <LoadFailed text="Could not load the goals." onRetry={() => goals.refetch()} />}
      {goals.data && !list.length && !adding && (
        <p className="rounded-xl bg-gray-50 p-4 text-sm text-gray-600 dark:bg-gray-800/60 dark:text-gray-300">
          No goals yet. A goal can be a weight, daily steps, sleep, water, or keeping blood pressure, sugar or HbA1c in the range the doctor set.
        </p>
      )}
      {open.map(g => <GoalCard key={g.id} personId={personId} goal={g} readOnly={readOnly} canRemove={!share.shared} />)}
      {ended.length > 0 && (
        <details className="flex flex-col gap-3">
          <summary className="cursor-pointer text-sm font-medium text-gray-700 dark:text-gray-200">Met and stopped ({ended.length})</summary>
          <div className="mt-3 flex flex-col gap-3">
            {ended.map(g => <GoalCard key={g.id} personId={personId} goal={g} readOnly={readOnly} canRemove={!share.shared} />)}
          </div>
        </details>
      )}
    </div>
  )
}

/** The target and where it comes from, in the server's words. */
function TargetLine({ target, note }: { target: GoalTarget | null | undefined; note?: string }) {
  if (!target) return note ? <p className="text-sm text-gray-600 dark:text-gray-300">{note}</p> : null
  return (
    <div className="flex flex-col gap-0.5">
      <p className="text-sm text-gray-900 dark:text-gray-100"><span className="text-gray-500 dark:text-gray-400">Target </span><span className="font-medium">{target.text}</span></p>
      <p className="flex flex-wrap items-center gap-1 text-xs text-gray-600 dark:text-gray-300">
        {target.source === 'doctor' && <Stethoscope className="h-3 w-3 text-brand-600 dark:text-brand-300" aria-hidden />}
        {target.source === 'doctor' && target.doctorName
          ? <>from {target.doctorName}{target.setOn ? `, set ${formatDay(target.setOn.slice(0, 10))}` : ''}</>
          : target.label}
        {target.needsVerification && <NeedsVerification />}
      </p>
      {target.note && <p className="text-xs text-gray-600 dark:text-gray-300">{target.note}</p>}
    </div>
  )
}

function WeekMarks({ goal }: { goal: HealthGoal }) {
  return (
    <ol aria-label="The last 12 weeks" className="flex flex-wrap items-center gap-1">
      {goal.checkins.map(c => {
        const words = `Week of ${formatDay(c.weekStart)}: ${c.value == null ? 'no value' : `${weekValue(goal, c)}, ${meetsWords(c.meets)}`}${c.source === 'measurements' ? ', from readings' : ''}`
        const Icon = c.value == null ? Circle : c.meets === true ? CheckCircle2 : c.meets === false ? XCircle : MinusCircle
        const tone = c.value == null ? 'text-gray-300 dark:text-gray-600' : c.meets === true ? 'text-green-600 dark:text-green-400' : c.meets === false ? 'text-amber-600 dark:text-amber-400' : 'text-gray-500'
        return (
          <li key={c.weekStart} title={words} aria-label={words}>
            <Icon className={`h-5 w-5 ${tone}`} aria-hidden />
          </li>
        )
      })}
    </ol>
  )
}

function GoalCard({ personId, goal: g, readOnly, canRemove }: { personId: string; goal: HealthGoal; readOnly: boolean; canRemove: boolean }) {
  const qc = useQueryClient()
  const [checking, setChecking] = useState(false)
  const [editing, setEditing] = useState(false)
  const done = () => qc.invalidateQueries({ queryKey: vitaKeys.goals(personId) })
  const setStatus = useMutation({ mutationFn: (status: GoalStatus) => vitaApi.updateGoal(personId, g.id, { status }), onSuccess: done })
  const remove = useMutation({ mutationFn: () => vitaApi.removeGoal(personId, g.id), onSuccess: done })
  const title = goalTitle(g)
  const status = STATUS[g.status] ?? STATUS.active
  const StatusIcon = status.icon
  const latest = [...g.checkins].reverse().find(c => c.value != null)
  const live = g.status === 'active' || g.status === 'paused'

  return (
    <section aria-label={title}>
      <Panel title={<><Target className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />{title}</>}
        action={<Pill tone={status.tone}><StatusIcon className="h-3 w-3" aria-hidden />{status.label}{g.status === 'met' && g.metOn ? ` on ${formatDay(g.metOn)}` : ''}</Pill>}>
        <TargetLine target={g.target} note={g.targetNote} />
        {g.kind === 'weight' && g.startValue != null && <p className="text-xs text-gray-500 dark:text-gray-400">Started at {num(g.startValue)} {g.unit ?? 'kg'}</p>}
        {g.byDate && <p className="text-xs text-gray-500 dark:text-gray-400">By {formatDay(g.byDate)}</p>}
        <div className="flex flex-col gap-1">
          <p className="text-sm text-gray-700 dark:text-gray-200">
            {latest ? <>Latest: <span className="font-medium">{weekValue(g, latest)}</span>, week of {formatDay(latest.weekStart)}, {meetsWords(latest.meets)}</> : 'No weekly value yet.'}
          </p>
          <WeekMarks goal={g} />
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {g.weeksMetInARow} of {g.metAfterWeeks} weeks in a row met. The goal is met after {g.metAfterWeeks} weeks in a row.
          </p>
        </div>
        {g.weeklyPlan.length > 0 && (
          <div>
            <p className="text-xs font-medium text-gray-600 dark:text-gray-300">Weekly plan</p>
            <ul className="ml-4 list-disc text-sm text-gray-800 dark:text-gray-100">{g.weeklyPlan.map((l, i) => <li key={i}>{l.text}</li>)}</ul>
          </div>
        )}
        {!readOnly && live && !checking && !editing && (
          <div className="flex flex-wrap gap-2">
            {g.status === 'active' && <Button size="sm" onClick={() => setChecking(true)}>Check in</Button>}
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>Edit</Button>
            <Button size="sm" variant="ghost" loading={setStatus.isPending} onClick={() => setStatus.mutate(g.status === 'paused' ? 'active' : 'paused')}>{g.status === 'paused' ? 'Resume' : 'Pause'}</Button>
            <Button size="sm" variant="ghost" onClick={() => setStatus.mutate('met')}>Mark met</Button>
            <Button size="sm" variant="ghost" onClick={() => setStatus.mutate('dropped')}>Stop</Button>
          </div>
        )}
        {!readOnly && !live && (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" loading={setStatus.isPending} onClick={() => setStatus.mutate('active')}>Start again</Button>
            {canRemove && <Button size="sm" variant="ghost" loading={remove.isPending} onClick={() => remove.mutate()}>Remove</Button>}
          </div>
        )}
        {checking && <CheckInForm personId={personId} goal={g} onDone={() => setChecking(false)} />}
        {editing && <GoalForm personId={personId} goal={g} onDone={() => setEditing(false)} />}
        {(setStatus.error || remove.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(setStatus.error ?? remove.error, 'That did not work.')}</p>}
      </Panel>
    </section>
  )
}

function CheckInForm({ personId, goal: g, onDone }: { personId: string; goal: HealthGoal; onDone: () => void }) {
  const qc = useQueryClient()
  const weeks = recentMondays(4)
  const [week, setWeek] = useState(weeks[0])
  const [value, setValue] = useState('')
  const [value2, setValue2] = useState('')
  const [note, setNote] = useState('')
  const bp = g.kind === 'bp_range'
  const v = Number(value)
  const v2 = Number(value2)
  const ok = value.trim() !== '' && v > 0 && (!bp || (value2.trim() !== '' && v2 > 0))
  const save = useMutation({
    mutationFn: () => vitaApi.checkIn(personId, g.id, { weekStart: week, value: v, value2: bp ? v2 : null, note: note.trim() || null }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.goals(personId) }); onDone() },
  })
  const unit = bp ? 'mmHg' : g.kind === 'hba1c' ? '%' : g.kind === 'glucose_range' ? 'mg/dL' : g.unit ?? ''
  return (
    <form className="flex flex-col gap-2 rounded-xl border border-gray-200 p-3 dark:border-gray-700" onSubmit={e => { e.preventDefault(); if (ok) save.mutate() }}>
      <p className="text-xs text-gray-500 dark:text-gray-400">A typed value replaces what the readings gave for that week.</p>
      <div className="grid gap-2 sm:grid-cols-3">
        <SelectField label="Week" value={week} onChange={setWeek} options={weeks.map((w, i) => ({ value: w, label: i === 0 ? `This week (from ${formatDay(w)})` : `Week of ${formatDay(w)}` }))} />
        <TextField label={bp ? 'Upper (systolic)' : `Value${unit ? ` (${unit})` : ''}`} type="number" value={value} onChange={setValue} />
        {bp && <TextField label="Lower (diastolic)" type="number" value={value2} onChange={setValue2} />}
      </div>
      <TextField label="Note (optional)" value={note} maxLength={500} onChange={setNote} />
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={!ok}>Save check-in</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the check-in.')}</p>}
    </form>
  )
}

/** Picking a kind shows the target the goal will read before anything is saved. */
function NewGoal({ personId, onDone }: { personId: string; onDone: () => void }) {
  const [kind, setKind] = useState<GoalKind>('weight')
  const [context, setContext] = useState<GoalContext | ''>('')
  return (
    <Panel title="New goal">
      <div className="grid gap-2 sm:grid-cols-2">
        <SelectField label="Kind of goal" value={kind} onChange={k => { setKind(k); setContext('') }}
          options={(Object.keys(GOAL_KINDS) as GoalKind[]).map(k => ({ value: k, label: GOAL_KINDS[k] }))} />
        {kind === 'glucose_range' && (
          <SelectField label="When" value={context} onChange={setContext}
            options={[{ value: '', label: 'Pick before or after a meal' }, { value: 'before_meal', label: 'Before a meal' }, { value: 'after_meal', label: 'After a meal' }]} />
        )}
      </div>
      <GoalForm key={`${kind}-${context}`} personId={personId} kind={kind} context={context || null} onDone={onDone} />
    </Panel>
  )
}

function GoalForm({ personId, goal, kind: newKind, context, onDone }: {
  personId: string; goal?: HealthGoal; kind?: GoalKind; context?: GoalContext | null; onDone: () => void
}) {
  const qc = useQueryClient()
  const kind = goal?.kind ?? newKind ?? 'weight'
  const medical = MEDICAL.includes(kind)
  const preview = useQuery({
    queryKey: vitaKeys.goalPreview(personId, kind, context ?? ''),
    queryFn: () => vitaApi.goalPreview(personId, kind, context),
    enabled: !goal,
  })
  const ownTarget = goal && !goal.medical && goal.target?.source === 'you' ? goal.target.text.split(' ')[0].replace(/,/g, '') : ''
  const [title, setTitle] = useState(goal?.title ?? '')
  const [target, setTarget] = useState(ownTarget)
  const [unit, setUnit] = useState(goal?.unit ?? DEFAULT_UNIT[kind] ?? '')
  const [start, setStart] = useState(goal?.startValue != null ? String(goal.startValue) : '')
  const [byDate, setByDate] = useState(goal?.byDate ?? '')
  const [plan, setPlan] = useState((goal?.weeklyPlan ?? []).map(l => l.text).join('\n'))
  const lines = plan.split('\n').map(l => l.trim()).filter(Boolean)
  const t = Number(target)
  const problems: string[] = []
  if (kind === 'custom' && !title.trim()) problems.push('Name the goal.')
  if (!medical && kind !== 'custom' && !(target.trim() && t > 0)) problems.push('Type the number you are aiming for.')
  if (lines.length > PLAN_LINES) problems.push(`Keep the plan to ${PLAN_LINES} lines.`)
  if (lines.some(l => l.length > LINE_MAX)) problems.push(`Each plan line is ${LINE_MAX} characters at most.`)
  if (byDate && byDate < localDay(0) && !goal) problems.push('Pick a date still to come, or leave it blank.')
  const blocked = !goal && (!preview.data || !preview.data.canCreate)

  const save = useMutation({
    mutationFn: () => {
      const body: GoalInput = {
        title: title.trim() || null, byDate: byDate || null, weeklyPlan: lines,
        ...(medical ? {} : { target: target.trim() ? t : null, unit: unit.trim() || null }),
        ...(kind === 'weight' && !goal && start.trim() ? { startValue: Number(start) } : {}),
      }
      return goal ? vitaApi.updateGoal(personId, goal.id, body) : vitaApi.addGoal(personId, { ...body, kind, context: kind === 'glucose_range' ? context : undefined })
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: vitaKeys.goals(personId) }); onDone() },
  })

  return (
    <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); if (!problems.length && !blocked) save.mutate() }}>
      {!goal && preview.isLoading && <SkeletonRows rows={1} />}
      {!goal && preview.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(preview.error, 'Could not check the target.')}</p>}
      {!goal && preview.data && (medical
        ? (preview.data.target
          ? <div className="rounded-xl bg-gray-50 p-3 dark:bg-gray-800/60"><TargetLine target={preview.data.target} /></div>
          : <p role="status" className="rounded-xl bg-gray-50 p-3 text-sm text-gray-700 dark:bg-gray-800/60 dark:text-gray-200">{preview.data.message}</p>)
        : <p className="text-xs text-gray-500 dark:text-gray-400">{preview.data.message}</p>)}
      {medical && <p className="text-xs text-gray-500 dark:text-gray-400">This goal reads its target from the doctor or from Vita’s settings, and keeps no figure of its own.</p>}
      {(!blocked || goal) && (
        <>
          <div className="grid gap-2 sm:grid-cols-2">
            <TextField label={kind === 'custom' ? 'Name of the goal' : 'Name (optional)'} value={title} maxLength={120} onChange={setTitle} />
            {!medical && <TextField label={kind === 'custom' ? 'Weekly number to aim for (optional)' : 'Aiming for'} type="number" value={target} onChange={setTarget} />}
            {!medical && <TextField label="Unit" value={unit} maxLength={20} onChange={setUnit} />}
            {kind === 'weight' && !goal && <TextField label="Starting weight (optional)" type="number" value={start} onChange={setStart} hint="Left blank, the latest weight on record is used." />}
            <TextField label="By (optional)" type="date" value={byDate} onChange={setByDate} />
          </div>
          <label className="flex flex-col gap-1 text-xs font-medium text-gray-600 dark:text-gray-300">
            Weekly plan, one step a line
            <textarea className={`${fieldClass} min-h-[4.5rem]`} value={plan} onChange={e => setPlan(e.target.value)} placeholder="Walk 30 minutes after dinner, 5 days" />
            <span className="font-normal text-gray-500 dark:text-gray-400">{goal?.planNote ?? preview.data?.planNote}</span>
          </label>
          {problems.length > 0 && <ul className="text-xs text-gray-600 dark:text-gray-300">{problems.map(p => <li key={p}>{p}</li>)}</ul>}
        </>
      )}
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={save.isPending} disabled={problems.length > 0 || blocked}>{goal ? 'Save changes' : 'Save goal'}</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the goal.')}</p>}
    </form>
  )
}
