import type { NotificationDto } from '@/types'

/**
 * The values a web step stopped to have approved, as its approval card shows them: what each
 * value resolved to from earlier steps, which mandatory ones are still empty, and, for a choice
 * made at run time, the items the page lists now. Pure, so it can be tested without a browser.
 */

export type StepFieldType = 'text' | 'number' | 'checkbox' | 'select' | 'list' | 'date'

export interface StepField {
  key: string
  label: string
  type: StepFieldType
  value?: unknown
  required: boolean
  missing: boolean
  /** The mapping the value came from, such as {{ input.movie }}. */
  source?: string
  options?: string[]
  /** What the site said is wrong with this value after the form was sent. */
  error?: string
  /** The field this one's choices follow, such as the theatre a showtime is in. */
  dependsOn?: string
  /** The choices for each value of `dependsOn`: the showtimes of each theatre. */
  optionsBy?: Record<string, string[]>
}

export interface StepForm {
  action: string
  title: string
  fields: StepField[]
  missing: string[]
  /** When the page's list was read, for a choice made at run time. */
  choicesAt?: string
  /** The run's values so far, to map an empty field from. */
  data: { path: string; value: string }[]
}

export type FormValues = Record<string, string | boolean | string[]>

const TYPES: StepFieldType[] = ['text', 'number', 'checkbox', 'select', 'list', 'date']

/** The approval card's values, or null for an item that has none. */
export function stepFormOf(n: NotificationDto): StepForm | null {
  const raw = (n.context as Record<string, unknown> | undefined)?.form as Record<string, unknown> | undefined
  if (!raw || !Array.isArray(raw.fields) || raw.fields.length === 0) return null
  const fields: StepField[] = []
  for (const f of raw.fields as Record<string, unknown>[]) {
    if (typeof f?.key !== 'string') continue
    fields.push({
      key: f.key,
      label: typeof f.label === 'string' ? f.label : f.key,
      type: TYPES.includes(f.type as StepFieldType) ? f.type as StepFieldType : 'text',
      value: f.value,
      required: f.required === true,
      missing: f.missing === true,
      ...(typeof f.source === 'string' ? { source: f.source } : {}),
      ...(Array.isArray(f.options) ? { options: (f.options as unknown[]).map(String) } : {}),
      ...(typeof f.error === 'string' && f.error ? { error: f.error } : {}),
      ...(typeof f.dependsOn === 'string' && f.optionsBy && typeof f.optionsBy === 'object'
        ? { dependsOn: f.dependsOn, optionsBy: optionsByOf(f.optionsBy as Record<string, unknown>) }
        : {}),
    })
  }
  const data = Array.isArray(raw.data)
    ? (raw.data as Record<string, unknown>[])
      .filter(d => typeof d?.path === 'string' && d.value != null)
      .map(d => ({ path: String(d.path), value: String(d.value) }))
    : []
  return {
    action: String(raw.action ?? ''),
    title: String(raw.title ?? 'Step'),
    fields,
    missing: Array.isArray(raw.missing) ? (raw.missing as unknown[]).map(String) : [],
    ...(typeof raw.choicesAt === 'string' ? { choicesAt: raw.choicesAt } : {}),
    data,
  }
}

function optionsByOf(raw: Record<string, unknown>): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const [key, list] of Object.entries(raw)) if (Array.isArray(list)) out[key] = list.map(String)
  return out
}

/**
 * The choices a field offers with the values as they are now: for a field that follows another
 * (a showtime follows its theatre), the choices for that field's current value; undefined when
 * the page's list for it is not known, so the value is typed instead.
 */
export function optionsFor(field: StepField, values: FormValues): string[] | undefined {
  if (!field.dependsOn || !field.optionsBy) return field.options
  const on = values[field.dependsOn]
  return typeof on === 'string' && field.optionsBy[on] ? field.optionsBy[on] : undefined
}

/** What each input starts with: the value the step resolved, in the input's own shape. */
export function initialValues(form: StepForm): FormValues {
  const out: FormValues = {}
  for (const f of form.fields) out[f.key] = toInput(f, f.value)
  return out
}

function toInput(field: StepField, value: unknown): string | boolean | string[] {
  if (field.type === 'checkbox') return value === true || value === 'true' || value === 'checked'
  if (field.type === 'list') {
    if (Array.isArray(value)) return value.map(String)
    return typeof value === 'string' && value ? splitList(value) : []
  }
  if (field.type === 'select' && field.options && value != null && !field.options.includes(String(value))) {
    // A choice the page no longer offers is not a choice.
    return ''
  }
  return value == null ? '' : String(value)
}

export function splitList(text: string): string[] {
  return text.split(/[,\n]/).map(s => s.trim()).filter(Boolean)
}

export function isEmpty(value: unknown): boolean {
  return value == null || value === '' || (Array.isArray(value) && value.length === 0)
}

/** The mandatory fields still empty, by label: Approve waits until there are none. */
export function unfilled(form: StepForm, values: FormValues): string[] {
  return form.fields
    .filter(f => (f.required || form.missing.includes(f.key)) && f.type !== 'checkbox' && isEmpty(values[f.key]))
    .map(f => f.label)
}

/**
 * What the approval sends back: each value the person changed, plus every mandatory one (so a
 * value they filled in is sent even when it matches what was shown).
 */
export function answerValues(form: StepForm, values: FormValues): Record<string, unknown> | undefined {
  const start = initialValues(form)
  const out: Record<string, unknown> = {}
  for (const f of form.fields) {
    const now = values[f.key]
    const changed = JSON.stringify(now) !== JSON.stringify(start[f.key])
    // A choice that follows another is sent again when that one changed: the same showtime in
    // another theatre is another choice.
    const followed = f.dependsOn !== undefined
      && JSON.stringify(values[f.dependsOn]) !== JSON.stringify(start[f.dependsOn])
    if (changed || followed || form.missing.includes(f.key)) out[f.key] = now
  }
  return Object.keys(out).length ? out : undefined
}

/** A value from earlier in the run, in the shape the field takes. */
export function mapped(field: StepField, value: string): string | boolean | string[] {
  return toInput(field, field.type === 'list' ? splitList(value) : value)
}
