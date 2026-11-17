/**
 * A workflow used as a module: a step of another workflow, with named inputs and outputs like a
 * function. The start step declares the inputs (config.inputs), which the run reads as
 * input.<name>; the end steps declare the outputs (config.outputs), each a value mapped from earlier
 * steps, which a step running the module gives later steps as nodes.<step>.<name>.
 *
 * Mirrors the engine's ModuleContract. Works on the canvas graph and on a saved workflow's graph.
 */
import type { ValueType } from './formula/functions'

export interface ModuleField {
  name: string
  type: ValueType
  required?: boolean
  description?: string
  /** For an input: used when a run is not given it. */
  default?: unknown
  /** For an output: what it gives, as text with {{ }} placeholders. */
  value?: string
  /** For a date input: how the site it fills writes it (DD/MM/YYYY), kept from the recording. */
  format?: string
  /** For a list of entries (one per passenger): the columns each entry has, shown as a table. */
  columns?: ModuleColumn[]
  /** How the run form names it and the section it shows it in (the page it was recorded on). */
  label?: string
  group?: string
  /** When a run asks for it: before it starts, at its step (on the step's card), or only if missing. */
  ask?: InputAsk
  /** The choices it accepts; a value is matched ignoring case and spacing, then via synonyms. */
  options?: string[]
  synonyms?: Record<string, string>
  min?: number
  max?: number
  pattern?: string
  patternHint?: string
  maxLength?: number
  minRows?: number
  maxRows?: number
}

export type InputAsk = 'upFront' | 'atStep' | 'ifMissing'

export interface ModuleColumn {
  key: string
  label: string
  required?: boolean
  options?: string[]
  min?: number
  max?: number
}

const ASKS = new Set<InputAsk>(['upFront', 'atStep', 'ifMissing'])
const str = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)
const strings = (v: unknown) =>
  Array.isArray(v) ? v.filter((o): o is string | number => typeof o === 'string' || typeof o === 'number').map(String) : undefined

/** Only the keys that are set, so a field read and saved back keeps exactly what it had. */
function defined<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>
}

function columnOf(c: Record<string, unknown>): ModuleColumn {
  return {
    key: String(c.key),
    label: typeof c.label === 'string' ? c.label : String(c.key),
    ...defined({
      required: c.required === true ? true : undefined,
      options: strings(c.options),
      min: num(c.min),
      max: num(c.max),
    }),
  }
}

function rulesOf(f: Record<string, unknown>): Partial<ModuleField> {
  const synonyms = f?.synonyms && typeof f.synonyms === 'object' && !Array.isArray(f.synonyms)
    ? Object.fromEntries(Object.entries(f.synonyms as Record<string, unknown>)
      .filter(([, v]) => typeof v === 'string').map(([k, v]) => [k, v as string]))
    : undefined
  return defined({
    label: str(f?.label),
    group: str(f?.group),
    ask: ASKS.has(f?.ask as InputAsk) ? f.ask as InputAsk : undefined,
    options: strings(f?.options),
    synonyms,
    min: num(f?.min),
    max: num(f?.max),
    pattern: str(f?.pattern),
    patternHint: str(f?.patternHint),
    maxLength: num(f?.maxLength),
    minRows: num(f?.minRows),
    maxRows: num(f?.maxRows),
  })
}

export const FIELD_TYPES: Array<{ value: ValueType; label: string }> = [
  { value: 'text', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'True/false' },
  { value: 'date', label: 'Date' },
  { value: 'datetime', label: 'Date and time' },
  { value: 'list', label: 'List' },
  { value: 'map', label: 'Fields (map)' },
  { value: 'file', label: 'File' },
  { value: 'any', label: 'Anything' },
]

export const FIELD_NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
export const MAX_FIELDS = 30

const TYPES = new Set(FIELD_TYPES.map(t => t.value))

type GraphLike = { nodes?: Array<{ type?: unknown; config?: Record<string, unknown> | null }> } | null | undefined

/** The fields a step's config declares under {@code key}. */
export function fieldsOf(config: Record<string, unknown> | null | undefined, key: 'inputs' | 'outputs'): ModuleField[] {
  const raw = config?.[key]
  if (!Array.isArray(raw)) return []
  return raw.map((f: Record<string, unknown>) => ({
    name: typeof f?.name === 'string' ? f.name : '',
    type: TYPES.has(f?.type as ValueType) ? f.type as ValueType : 'any',
    ...(f?.required === true ? { required: true } : {}),
    ...(typeof f?.description === 'string' && f.description ? { description: f.description } : {}),
    ...(f?.default !== undefined && f?.default !== null ? { default: f.default } : {}),
    ...(typeof f?.value === 'string' ? { value: f.value } : {}),
    ...(typeof f?.format === 'string' && f.format ? { format: f.format } : {}),
    ...(Array.isArray(f?.columns)
      ? { columns: (f.columns as Record<string, unknown>[]).filter(c => typeof c?.key === 'string').map(columnOf) }
      : {}),
    ...rulesOf(f),
  }))
}

/** The inputs a workflow's start step declares. */
export function moduleInputs(graph: unknown): ModuleField[] {
  const start = (graph as GraphLike)?.nodes?.find(n => n.type === 'TRIGGER')
  return fieldsOf(start?.config, 'inputs').filter(f => f.name)
}

/** The outputs a workflow's end steps declare; where two end steps share a name, the first wins. */
export function moduleOutputs(graph: unknown): ModuleField[] {
  const seen = new Map<string, ModuleField>()
  for (const n of (graph as GraphLike)?.nodes ?? []) {
    if (n.type !== 'END') continue
    for (const f of fieldsOf(n.config, 'outputs')) if (f.name && !seen.has(f.name)) seen.set(f.name, f)
  }
  return [...seen.values()]
}

/** Whether a workflow declares inputs or outputs, so it reads as a module. */
export function isModule(graph: unknown): boolean {
  return moduleInputs(graph).length > 0 || moduleOutputs(graph).length > 0
}

/**
 * Problems with a step's declared fields, as [field key, message]. {@code needsValue} says whether
 * each field must be mapped to a value: an end step's outputs and a script's inputs must, a start
 * step's inputs and a script's outputs (which the script returns) need not.
 */
export function contractIssues(config: Record<string, unknown>, key: 'inputs' | 'outputs', name: string,
  needsValue = key === 'outputs'): Array<[string, string]> {
  const out: Array<[string, string]> = []
  const fields = fieldsOf(config, key)
  const what = key === 'inputs' ? 'input' : 'output'
  if (fields.length > MAX_FIELDS) out.push([key, `${name} can have at most ${MAX_FIELDS} ${key}.`])
  const seen = new Set<string>()
  fields.forEach((f, i) => {
    if (!FIELD_NAME.test(f.name)) out.push([`${key}.${i}.name`, `${name} needs a name for ${what} ${i + 1} (letters, digits and _, starting with a letter).`])
    else if (seen.has(f.name)) out.push([`${key}.${i}.name`, `${name} has two ${key} named "${f.name}".`])
    seen.add(f.name)
    if (needsValue && !f.value?.trim()) out.push([`${key}.${i}.value`, `${name} needs a value for ${f.name ? `"${f.name}"` : `${what} ${i + 1}`}.`])
  })
  return out
}

/**
 * The inputs in the sections the run form shows: by group, in the order each group first
 * appears; inputs without a group come first. Inputs asked at their step are left for the card.
 */
export function inputSections(fields: ModuleField[]): Array<{ group: string; fields: ModuleField[] }> {
  const sections = new Map<string, ModuleField[]>()
  for (const f of fields) {
    if (f.ask === 'atStep') continue
    const g = f.group ?? ''
    sections.set(g, [...(sections.get(g) ?? []), f])
  }
  const out = [...sections.entries()].map(([group, fs]) => ({ group, fields: fs }))
  return [...out.filter(s => !s.group), ...out.filter(s => s.group)]
}

/** What the run form says is wrong with a value before the server is asked, or null. */
export function ruleProblem(f: ModuleField, value: unknown): string | null {
  const name = f.label ?? f.name
  if (value === undefined || value === null || value === '') return null
  if (typeof value === 'string' && f.type === 'list' && value.trim().startsWith('[')) {
    try { value = JSON.parse(value) } catch { return null }
  }
  if (Array.isArray(value)) {
    if (f.minRows && value.length < f.minRows) return `${name} needs at least ${f.minRows} ${f.minRows === 1 ? 'entry' : 'entries'}`
    if (f.maxRows && value.length > f.maxRows) return `${name} can have at most ${f.maxRows} ${f.maxRows === 1 ? 'entry' : 'entries'}`
    return null
  }
  const text = String(value)
  if (f.type === 'number') {
    const n = Number(text.replace(/,/g, ''))
    if (Number.isFinite(n)) {
      if (f.min !== undefined && n < f.min) return `${name} must be at least ${f.min}`
      if (f.max !== undefined && n > f.max) return `${name} can be at most ${f.max}`
    }
  }
  if (f.maxLength && text.length > f.maxLength) return `${name} can be at most ${f.maxLength} characters`
  if (f.pattern) {
    try {
      if (!new RegExp(`^(?:${f.pattern})$`).test(text)) return `${text} is not a valid ${name}${f.patternHint ? ` (${f.patternHint})` : ''}`
    } catch { /* a broken pattern never blocks a run */ }
  }
  return null
}
