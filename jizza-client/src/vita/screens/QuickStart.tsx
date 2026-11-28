import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Plus, X } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Meter, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { AllergySeverity, BloodGroup, ConditionTemplate, QuickStartStep, SexAtBirth } from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { errorText, feetToCm, useUnits } from '../data'
import { BLOOD_GROUPS, COMMON_CONDITIONS, QUICK_START_ORDER, QUICK_START_TITLES, SEVERITIES, SEXES } from '../labels'
import { vitaKeys } from '../queryKeys'

type Answers = Record<string, unknown>

/**
 * Quick start (7.3): eight steps, one per screen, each saved as soon as it is answered so it can be
 * left and resumed; Back, Next and Skip on every step. Ends on Home with the emergency card ready.
 */
export function QuickStart({ personId, personName, onDone }: { personId: string; personName: string; onDone: () => void }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.quickStart(personId), queryFn: () => vitaApi.quickStart(personId) })
  const [step, setStep] = useState<QuickStartStep | 'done' | null>(null)

  useEffect(() => {
    if (data && step == null) setStep(data.progress.next ?? 'done')
  }, [data, step])

  const next = (from: QuickStartStep) => {
    const at = QUICK_START_ORDER.indexOf(from)
    setStep(at < QUICK_START_ORDER.length - 1 ? QUICK_START_ORDER[at + 1] : 'done')
  }
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const save = useMutation({
    mutationFn: ({ id, answers }: { id: QuickStartStep; answers: Answers }) => vitaApi.saveQuickStart(personId, id, answers),
    onSuccess: (_, { id }) => { refresh(); next(id) },
  })
  const skip = useMutation({
    mutationFn: (id: QuickStartStep) => vitaApi.skipQuickStart(personId, id),
    onSuccess: (_, id) => { refresh(); next(id) },
  })

  if (isLoading || (data && step == null)) return <SkeletonRows rows={4} />
  if (error || !data) return <LoadFailed text="Could not open Quick start." onRetry={() => refetch()} />

  const progress = data.progress
  const handled = progress.completed.length + progress.skipped.length

  if (step === 'done') {
    return (
      <div className="flex flex-col gap-4">
        <ViewTitle title="Quick start is done" sub={`${progress.completed.length} answered, ${progress.skipped.length} skipped`} />
        <Panel title={<><CheckCircle2 className="h-4 w-4 text-green-600 dark:text-green-400" aria-hidden />The emergency card is ready</>}>
          <p className="text-sm text-gray-600 dark:text-gray-300">Anything you skipped can be filled in from Profile, Conditions or Allergies, or by starting Quick start again.</p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={onDone}>Go to Home</Button>
            <Button size="sm" variant="secondary" onClick={() => setStep(QUICK_START_ORDER[0])}>Go through it again</Button>
          </div>
        </Panel>
      </div>
    )
  }
  const id = step as QuickStartStep
  const index = QUICK_START_ORDER.indexOf(id)
  const busy = save.isPending || skip.isPending
  const failed = save.error ?? skip.error

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <p className="text-xs text-gray-500 dark:text-gray-400">Quick start for {personName} · step {index + 1} of {QUICK_START_ORDER.length} · {handled} done so far</p>
        <Meter pct={((index) / QUICK_START_ORDER.length) * 100} label="Quick start progress" className="bg-[rgb(var(--agent-health))]" />
      </div>
      <Panel title={QUICK_START_TITLES[id].title}>
        <p className="text-sm text-gray-500 dark:text-gray-400">{QUICK_START_TITLES[id].why}</p>
        <StepBody key={id} step={id} busy={busy} onSave={answers => save.mutate({ id, answers })}
          footer={(
            <>
              <Button type="button" size="sm" variant="ghost" disabled={index === 0 || busy} onClick={() => setStep(QUICK_START_ORDER[index - 1])}>
                <ArrowLeft className="h-3.5 w-3.5" aria-hidden />Back
              </Button>
              <Button type="button" size="sm" variant="secondary" loading={skip.isPending} disabled={busy} onClick={() => skip.mutate(id)}>Skip</Button>
              <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={onDone}>Finish later</Button>
            </>
          )} />
        {failed && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(failed, 'Could not save this step. Check it and try again.')}</p>}
      </Panel>
    </div>
  )
}

function StepBody({ step, busy, onSave, footer }: { step: QuickStartStep; busy: boolean; onSave: (a: Answers) => void; footer: React.ReactNode }) {
  const units = useUnits()
  const [dob, setDob] = useState('')
  const [sex, setSex] = useState<SexAtBirth | ''>('')
  const [height, setHeight] = useState({ cm: '', ft: '', in: '', weight: '' })
  const [blood, setBlood] = useState<BloodGroup | ''>('')
  const [conds, setConds] = useState<{ name: string; template: ConditionTemplate }[]>([])
  const [other, setOther] = useState('')
  const [condNone, setCondNone] = useState(false)
  const [allergies, setAllergies] = useState<{ substance: string; severity: AllergySeverity }[]>([])
  const [allergyNone, setAllergyNone] = useState(false)
  const [contact, setContact] = useState({ name: '', relation: '', phone: '' })

  let body: React.ReactNode
  let answers: Answers | null = null
  switch (step) {
    case 'dob':
      body = <TextField label="Date of birth" type="date" value={dob} onChange={setDob} />
      answers = dob ? { dob } : null
      break
    case 'sex':
      body = (
        <div className="flex flex-wrap gap-2" role="group" aria-label="Sex at birth">
          {(Object.keys(SEXES) as SexAtBirth[]).map(s => <Chip key={s} on={sex === s} onClick={() => setSex(s)}>{SEXES[s]}</Chip>)}
        </div>
      )
      answers = sex ? { sexAtBirth: sex } : null
      break
    case 'height_weight': {
      const cm = units.height === 'cm' ? (height.cm ? Number(height.cm) : null) : height.ft ? feetToCm(Number(height.ft), Number(height.in || 0)) : null
      body = (
        <div className="grid gap-3 sm:grid-cols-2">
          {units.height === 'cm'
            ? <TextField label="Height (cm)" value={height.cm} maxLength={5} placeholder="162" onChange={v => setHeight({ ...height, cm: v })} />
            : <div className="grid grid-cols-2 gap-2">
              <TextField label="Height (feet)" value={height.ft} maxLength={1} onChange={v => setHeight({ ...height, ft: v })} />
              <TextField label="Inches" value={height.in} maxLength={2} onChange={v => setHeight({ ...height, in: v })} />
            </div>}
          <TextField label={`Weight (${units.weight})`} value={height.weight} maxLength={6} placeholder={units.weight === 'kg' ? '64.5' : '142'}
            onChange={v => setHeight({ ...height, weight: v })} />
        </div>
      )
      // Quick start always sends kilograms; a weight in pounds is converted by the server on the readings form.
      const kg = height.weight ? (units.weight === 'lb' ? Math.round(Number(height.weight) * 0.45359237 * 100) / 100 : Number(height.weight)) : null
      answers = cm != null || kg != null ? { ...(cm != null ? { heightCm: cm } : {}), ...(kg != null ? { weightKg: kg } : {}) } : null
      break
    }
    case 'blood_group':
      body = (
        <div className="flex flex-wrap gap-2" role="group" aria-label="Blood group">
          {(Object.keys(BLOOD_GROUPS) as BloodGroup[]).map(b => <Chip key={b} on={blood === b} onClick={() => setBlood(b)}><span className="font-mono">{BLOOD_GROUPS[b]}</span></Chip>)}
        </div>
      )
      answers = blood ? { bloodGroup: blood } : null
      break
    case 'conditions': {
      const toggle = (c: { name: string; template: ConditionTemplate }) => {
        setCondNone(false)
        setConds(v => v.some(x => x.name === c.name) ? v.filter(x => x.name !== c.name) : [...v, c])
      }
      body = (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            {COMMON_CONDITIONS.map(c => <Chip key={c.name} on={conds.some(x => x.name === c.name)} onClick={() => toggle(c)}>{c.name}</Chip>)}
            <Chip on={condNone} onClick={() => { setCondNone(!condNone); setConds([]) }}>None known</Chip>
          </div>
          {conds.filter(c => !COMMON_CONDITIONS.some(x => x.name === c.name)).map(c => (
            <span key={c.name} className="inline-flex items-center gap-1 self-start rounded-full bg-brand-50 px-3 py-1 text-sm text-brand-700 dark:bg-brand-900/30 dark:text-brand-200">
              {c.name}<button type="button" aria-label={`Remove ${c.name}`} onClick={() => toggle(c)}><X className="h-3.5 w-3.5" /></button>
            </span>
          ))}
          <div className="flex items-end gap-2">
            <TextField className="flex-1" label="Other (type its name)" value={other} maxLength={120} onChange={setOther} />
            <Button type="button" size="sm" variant="secondary" disabled={!other.trim()} onClick={() => { toggle({ name: other.trim(), template: 'generic' }); setOther('') }}>
              <Plus className="h-3.5 w-3.5" aria-hidden />Add
            </Button>
          </div>
        </div>
      )
      answers = condNone ? { noneKnown: true } : conds.length ? { conditions: conds } : null
      break
    }
    case 'allergies':
      body = (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            <Chip on={allergyNone} onClick={() => { setAllergyNone(!allergyNone); setAllergies([]) }}>None known</Chip>
            <Chip on={false} onClick={() => { setAllergyNone(false); setAllergies(a => [...a, { substance: '', severity: 'unknown' }]) }}>Add an allergy</Chip>
          </div>
          {allergies.map((a, i) => (
            <div key={i} className="grid grid-cols-[1fr_auto_auto] items-end gap-2">
              <TextField label="Substance" value={a.substance} maxLength={120} onChange={s => setAllergies(v => v.map((x, j) => j === i ? { ...x, substance: s } : x))} />
              <SelectField label="Severity" value={a.severity} onChange={s => setAllergies(v => v.map((x, j) => j === i ? { ...x, severity: s } : x))}
                options={(Object.keys(SEVERITIES) as AllergySeverity[]).map(s => ({ value: s, label: SEVERITIES[s] }))} />
              <Button type="button" size="sm" variant="ghost" aria-label="Remove this allergy" onClick={() => setAllergies(v => v.filter((_, j) => j !== i))}><X className="h-4 w-4" /></Button>
            </div>
          ))}
        </div>
      )
      answers = allergyNone ? { noneKnown: true } : allergies.some(a => a.substance.trim())
        ? { allergies: allergies.filter(a => a.substance.trim()).map(a => ({ substance: a.substance.trim(), severity: a.severity })) } : null
      break
    case 'medicines':
      body = <p className="rounded-lg bg-gray-50 p-3 text-sm text-gray-700 dark:bg-gray-800/60 dark:text-gray-200">Add each medicine with its times on the Medicines page once Quick start is done; Vita then makes a card for each time of day on Home. Answer “Later” to carry on.</p>
      answers = { later: true }
      break
    case 'contact':
      body = (
        <div className="grid gap-3 sm:grid-cols-3">
          <TextField label="Name" value={contact.name} maxLength={80} onChange={name => setContact({ ...contact, name })} />
          <TextField label="Relation (optional)" value={contact.relation} maxLength={40} placeholder="Son" onChange={relation => setContact({ ...contact, relation })} />
          <TextField label="Phone" value={contact.phone} maxLength={30} placeholder="+91 …" onChange={phone => setContact({ ...contact, phone })} />
        </div>
      )
      answers = contact.name.trim() && contact.phone.trim()
        ? { name: contact.name.trim(), phone: contact.phone.trim(), ...(contact.relation.trim() ? { relation: contact.relation.trim() } : {}) } : null
      break
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={e => { e.preventDefault(); if (answers) onSave(answers) }}>
      {body}
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" size="sm" loading={busy} disabled={!answers}>{step === 'medicines' ? 'Later' : 'Next'}</Button>
        {footer}
      </div>
    </form>
  )
}
