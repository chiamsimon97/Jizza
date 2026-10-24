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
}

export const FIELD_TYPES: Array<{ value: ValueType; label: string }> = [
  { value: 'text', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'True/false' },
  { value: 'date', label: 'Date' },
  { value: 'list', label: 'List' },
  { value: 'map', label: 'Fields (map)' },
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

/** Problems with a step's declared fields, as [field key, message]. */
export function contractIssues(config: Record<string, unknown>, key: 'inputs' | 'outputs', name: string): Array<[string, string]> {
  const out: Array<[string, string]> = []
  const fields = fieldsOf(config, key)
  const what = key === 'inputs' ? 'input' : 'output'
  if (fields.length > MAX_FIELDS) out.push([key, `${name} can have at most ${MAX_FIELDS} ${key}.`])
  const seen = new Set<string>()
  fields.forEach((f, i) => {
    if (!FIELD_NAME.test(f.name)) out.push([`${key}.${i}.name`, `${name} needs a name for ${what} ${i + 1} (letters, digits and _, starting with a letter).`])
    else if (seen.has(f.name)) out.push([`${key}.${i}.name`, `${name} has two ${key} named "${f.name}".`])
    seen.add(f.name)
    if (key === 'outputs' && !f.value?.trim()) out.push([`${key}.${i}.value`, `${name} needs a value for ${f.name ? `"${f.name}"` : `output ${i + 1}`}.`])
  })
  return out
}
