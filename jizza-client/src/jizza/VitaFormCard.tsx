import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, HeartPulse } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import type { VitaForm, VitaFormKind } from '@/types/vita'
import { BLOOD_GROUPS, SEXES } from '@/vita/labels'
import { errorText } from '@/vita/data'
import { vitaKeys } from '@/vita/queryKeys'

type Values = Record<string, unknown>

interface FieldDef {
  name: string
  label: string
  kind: 'text' | 'date' | 'select' | 'yesno'
  options?: Record<string, string>
  placeholder?: string
  required?: boolean
}

interface FormDef {
  title: string
  saved: string
  fields: FieldDef[]
  save: (personId: string, body: Values) => Promise<unknown>
}

/** What each Vita chat form asks, and where it saves. The fields match the server's own forms. */
export const VITA_FORMS: Record<VitaFormKind, FormDef> = {
  profile: {
    title: 'Check and save to the health record', saved: 'Saved to the profile',
    fields: [
      { name: 'bloodGroup', label: 'Blood group', kind: 'select', options: BLOOD_GROUPS },
      { name: 'dob', label: 'Date of birth', kind: 'date' },
      { name: 'sexAtBirth', label: 'Sex at birth', kind: 'select', options: SEXES },
      { name: 'heightCm', label: 'Height (cm)', kind: 'text', placeholder: '162' },
      { name: 'organDonor', label: 'Organ donor', kind: 'yesno' },
      { name: 'preferredHospital', label: 'Preferred hospital', kind: 'text' },
      { name: 'preferredHospitalPhone', label: 'Hospital phone', kind: 'text' },
    ],
    save: (personId, body) => vitaApi.saveProfile(personId, body),
  },
  emergency_contact: {
    title: 'Add an emergency contact', saved: 'Contact saved',
    fields: [
      { name: 'name', label: 'Name', kind: 'text', required: true },
      { name: 'relation', label: 'Relation', kind: 'text', placeholder: 'Son' },
      { name: 'phone', label: 'Phone', kind: 'text', placeholder: '+91 …', required: true },
    ],
    save: (personId, body) => vitaApi.addContact(personId, body as { name: string; phone: string }),
  },
}

const blank = (v: unknown) => v === undefined || v === null || (typeof v === 'string' && v.trim() === '')

/** A profile form shows only the fields Vita filled in, so Save never clears something already recorded. */
export function shownFields(card: VitaForm): FieldDef[] {
  const def = VITA_FORMS[card.form]
  if (card.form !== 'profile') return def.fields
  const filled = def.fields.filter(f => card.values && f.name in card.values)
  return filled.length ? filled : def.fields
}

export function vitaStartValues(card: VitaForm): Values {
  const out: Values = {}
  for (const f of shownFields(card)) {
    const v = card.values?.[f.name]
    if (v === undefined || v === null) continue
    if (f.kind === 'select' && !(typeof v === 'string' && f.options && v in f.options)) continue
    out[f.name] = f.kind === 'yesno' ? (v === true ? 'yes' : v === false ? 'no' : '') : v
  }
  return out
}

/** The body to save: only the fields on the card, empty ones left out, yes/no as true/false. */
export function vitaBodyOf(card: VitaForm, values: Values): Values {
  const out: Values = {}
  for (const f of shownFields(card)) {
    const v = values[f.name]
    if (blank(v)) continue
    if (f.kind === 'yesno') out[f.name] = v === 'yes'
    else if (f.name === 'heightCm') out[f.name] = Number(v)
    else out[f.name] = typeof v === 'string' ? v.trim() : v
  }
  return out
}

export function vitaMissing(card: VitaForm, values: Values): string[] {
  const fields = shownFields(card)
  const need = fields.filter(f => f.required && blank(values[f.name])).map(f => f.label)
  if (!need.length && !fields.some(f => !blank(values[f.name]))) return ['at least one value']
  return need
}

const read = (key: string): boolean => {
  try { return localStorage.getItem(key) === '1' } catch { return false }
}

/**
 * A short Health form Vita put in the chat with what the person told it (spec 6.1, 8): they check it,
 * change anything, and save it here through the Health tab's own endpoint with their own session.
 * Nothing is saved until they press Save; Vita never saves it. Once saved, the card says so.
 */
export function VitaFormCard({ card }: { card: VitaForm }) {
  return VITA_FORMS[card.form] ? <FormCard card={card} /> : null
}

function FormCard({ card }: { card: VitaForm }) {
  const def = VITA_FORMS[card.form]
  const qc = useQueryClient()
  const key = `jizza.vitaform.${card.id}`
  const [values, setValues] = useState<Values>(() => vitaStartValues(card))
  const [done, setDone] = useState(() => read(key))
  const save = useMutation({
    mutationFn: () => def.save(card.personId, vitaBodyOf(card, values)),
    onSuccess: () => {
      try { localStorage.setItem(key, '1') } catch { /* the card just won't remember after a reload */ }
      setDone(true)
      qc.invalidateQueries({ queryKey: vitaKeys.all })
    },
  })
  const set = (name: string, v: unknown) => setValues(prev => ({ ...prev, [name]: v }))
  const needed = vitaMissing(card, values)
  const title = card.title ?? def.title

  return (
    <section aria-label={title} className="mt-2 flex flex-col gap-3 rounded-xl border border-gray-200 border-l-4 border-l-[rgb(var(--agent-health))] bg-white p-3 dark:border-gray-700 dark:border-l-[rgb(var(--agent-health))] dark:bg-gray-900">
      <header className="flex flex-wrap items-center gap-2">
        <HeartPulse className="h-4 w-4 text-[rgb(var(--agent-health))]" aria-hidden />
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
        {card.personName && <span className="text-xs text-gray-500 dark:text-gray-400">for {card.personName}</span>}
      </header>
      {card.intro && <p className="text-sm text-gray-600 dark:text-gray-300">{card.intro}</p>}
      {done ? (
        <p role="status" className="flex items-center gap-1.5 text-sm font-medium text-green-700 dark:text-green-400">
          <Check className="h-4 w-4" aria-hidden />{def.saved}. You'll find it in the Health tab.
        </p>
      ) : (
        <>
          <p className="text-xs text-gray-500 dark:text-gray-400">Not saved yet. Check it, then press Save.</p>
          <div className="grid gap-3 sm:grid-cols-2">
            {shownFields(card).map(f => {
              const label = f.required ? f.label : `${f.label} (optional)`
              const v = values[f.name]
              if (f.kind === 'select' || f.kind === 'yesno') {
                const opts = f.kind === 'yesno' ? { yes: 'Yes', no: 'No' } : f.options ?? {}
                return <SelectField key={f.name} label={label} value={(v as string) ?? ''} onChange={x => set(f.name, x || null)}
                  options={[{ value: '', label: 'Not set' }, ...Object.entries(opts).map(([value, l]) => ({ value, label: l }))]} />
              }
              return <TextField key={f.name} label={label} placeholder={f.placeholder} type={f.kind === 'date' ? 'date' : 'text'}
                value={v == null ? '' : String(v)} onChange={x => set(f.name, x)} />
            })}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" loading={save.isPending} disabled={needed.length > 0} onClick={() => save.mutate()}>Save</Button>
            {needed.length > 0 && <span className="text-xs text-gray-500 dark:text-gray-400">Still needed: {needed.join(', ')}</span>}
          </div>
          {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save. Check the values and try again.')}</p>}
        </>
      )}
    </section>
  )
}
