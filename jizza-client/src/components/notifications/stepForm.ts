import type { NotificationDto } from '@/types'
import { kindOf, pickerValue } from '@/lib/dateFormat'
import { rowsEmpty, type RowsColumn } from '@/components/ui/RowsInput'

/**
 * The values a web step stopped to have approved, as its approval card shows them: what each
 * value resolved to from earlier steps, which mandatory ones are still empty, and, for a choice
 * made at run time, the items the page lists now. Pure, so it can be tested without a browser.
 */

export type StepFieldType = 'text' | 'number' | 'checkbox' | 'select' | 'list' | 'date' | 'datetime' | 'note' | 'rows' | 'taps'

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
  /** For a date: how the site writes it (DD/MM/YYYY). The picker's date is written that way. */
  format?: string
  /** The field this one's choices follow, such as the theatre a showtime is in. */
  dependsOn?: string
  /** The choices for each value of `dependsOn`: the showtimes of each theatre. */
  optionsBy?: Record<string, string[]>
  /** For a section the page repeats (passengers): the columns of its table, one row each. */
  columns?: RowsColumn[]
  /** A device layer's group this value is in (Task details), shown as a heading above it. */
  group?: string
  /** For taps: the picture of the page area to tap on, kept by that device. */
  picture?: { device: string; file: string }
}

/** The pictures taken of the listed items: which device keeps them, and one per option name. */
export interface StepShots { device: string; byOption: Record<string, string> }

export interface StepForm {
  action: string
  title: string
  fields: StepField[]
  missing: string[]
  /** When the page's list was read, for a choice made at run time. */
  choicesAt?: string
  /** The run's values so far, to map an empty field from. */
  data: { path: string; value: string }[]
  /** Pictures of the listed items, shown for the option chosen. */
  shots?: StepShots
  /** A device layer's fixed values: filled every run as they are, listed but not asked. */
  fixed?: { label: string; value: string }[]
  /** Refresh: how to read each value's choices again from the page the run is on. */
  live?: StepLive
}

/** One value the card can read again from the page: a dropdown's items, or the suggestions a
 *  field shows for the text typed into it (`typed`). */
export interface StepLiveField { action: string; version: number; typed: boolean; element: Record<string, unknown> }
export interface StepLive { device: string; session?: string; fields: Record<string, StepLiveField> }

export type FormValues = Record<string, string | boolean | string[]>

const TYPES: StepFieldType[] = ['text', 'number', 'checkbox', 'select', 'list', 'date', 'datetime', 'note', 'rows']

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
      ...(typeof f.format === 'string' && f.format ? { format: f.format } : {}),
      ...(typeof f.group === 'string' && f.group ? { group: f.group } : {}),
      ...(Array.isArray(f.columns)
        ? { columns: (f.columns as Record<string, unknown>[]).filter(c => typeof c?.key === 'string')
          .map(c => ({ key: String(c.key), label: typeof c.label === 'string' ? c.label : String(c.key) })) }
        : {}),
      ...(typeof f.dependsOn === 'string' && f.optionsBy && typeof f.optionsBy === 'object'
        ? { dependsOn: f.dependsOn, optionsBy: optionsByOf(f.optionsBy as Record<string, unknown>) }
        : {}),
    })
  }
  const shots = shotsOf(raw.shots)
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
    ...(shots ? { shots } : {}),
    ...(Array.isArray(raw.fixed) && raw.fixed.length > 0
      ? { fixed: (raw.fixed as Record<string, unknown>[]).filter(x => typeof x?.label === 'string')
        .map(x => ({ label: String(x.label), value: x.value == null ? '' : String(x.value) })) }
      : {}),
    ...(liveOf(raw.live) ? { live: liveOf(raw.live)! } : {}),
  }
}

function liveOf(raw: unknown): StepLive | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const { device, session, fields } = raw as Record<string, unknown>
  if (typeof device !== 'string' || !device || !fields || typeof fields !== 'object') return undefined
  const out: Record<string, StepLiveField> = {}
  for (const [key, f] of Object.entries(fields as Record<string, unknown>)) {
    const field = f as Record<string, unknown> | null
    if (!field || typeof field.action !== 'string' || !field.element || typeof field.element !== 'object') continue
    out[key] = {
      action: field.action,
      version: typeof field.version === 'number' ? field.version : 1,
      typed: field.typed === true,
      element: field.element as Record<string, unknown>,
    }
  }
  if (Object.keys(out).length === 0) return undefined
  return { device, ...(typeof session === 'string' && session ? { session } : {}), fields: out }
}

function shotsOf(raw: unknown): StepShots | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const { device, byOption } = raw as Record<string, unknown>
  if (typeof device !== 'string' || !byOption || typeof byOption !== 'object') return undefined
  const out: Record<string, string> = {}
  for (const [option, id] of Object.entries(byOption as Record<string, unknown>)) {
    if (typeof id === 'string' && id) out[option] = id
  }
  return Object.keys(out).length ? { device, byOption: out } : undefined
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
  // A repeated section's entries go in its table as JSON text.
  if (field.type === 'rows') return value == null ? '[]' : typeof value === 'string' ? value : JSON.stringify(value)
  if (field.type === 'checkbox') return value === true || value === 'true' || value === 'checked'
  if (field.type === 'taps') {
    return Array.isArray(value)
      ? value.map(v => (v && typeof v === 'object' && 'x' in v && 'y' in v ? `${(v as { x: number }).x},${(v as { y: number }).y}` : String(v)))
      : []
  }
  if (field.type === 'list') {
    if (Array.isArray(value)) return value.map(String)
    return typeof value === 'string' && value ? splitList(value) : []
  }
  if (field.type === 'select' && field.options && value != null && !field.options.includes(String(value))) {
    // A choice the page no longer offers is not a choice.
    return ''
  }
  if (isDate(field) && typeof value === 'string') {
    // A date the site wrote its way (20/11/2026) goes in the picker as ISO; the browser writes
    // the date picked back the site's way. Text that is not a date is kept as it is.
    return pickerValue(value, kindOf(field.type, field.format), field.format) || value
  }
  return value == null ? '' : String(value)
}

/** Whether a field takes a date, or a date and time. */
export function isDate(field: Pick<StepField, 'type'>): boolean {
  return field.type === 'date' || field.type === 'datetime'
}

export function splitList(text: string): string[] {
  return text.split(/[,\n]/).map(s => s.trim()).filter(Boolean)
}

export function isEmpty(value: unknown): boolean {
  return value == null || value === '' || (Array.isArray(value) && value.length === 0)
}

/** Whether a field's value is empty: a rows table counts as empty with nothing filled in. */
export function fieldEmpty(field: Pick<StepField, 'type'>, value: unknown): boolean {
  return field.type === 'rows' ? rowsEmpty(value) : isEmpty(value)
}

/** The mandatory fields still empty, by label: Approve waits until there are none. */
export function unfilled(form: StepForm, values: FormValues): string[] {
  return form.fields
    .filter(f => (f.required || form.missing.includes(f.key)) && f.type !== 'checkbox' && f.type !== 'note' && fieldEmpty(f, values[f.key]))
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
