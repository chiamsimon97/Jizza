/**
 * Mapping data from earlier steps into a step's fields, the way Zoho Flow's formula editor and
 * Zapier's field mapping do: a reference to an earlier step's value shows as a chip.
 *
 * Fields come in two kinds. Template fields (a webhook's URL, a notification's message) hold text
 * with {{ expression }} placeholders, and each placeholder is a chip. Expression fields (a
 * condition, a transform) are JEXL, and each reference to nodes.<id>…, vars.<name> or input… in
 * them is a chip. Either way the saved value is the plain text the engine reads.
 */
import { NODE_META, upstreamOf, type CanvasGraph, type CanvasNode, type NodeType } from './canvasModel'
import type { FormulaScope } from './formula/analyze'
import type { ValueType } from './formula/functions'
import { fieldsOf, moduleInputs } from './moduleContract'

export type MappingMode = 'template' | 'expression'

/** A field's value cut into plain text and references (chips). */
export type Segment = { kind: 'text'; text: string } | { kind: 'ref'; expr: string }

const PLACEHOLDER = /\{\{([\s\S]+?)}}/g

/** A reference to a value: nodes.<id> with a path, vars.<name>, or input with a path. */
const PATH = String.raw`(?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*`
const REFERENCE = new RegExp(String.raw`(?<![\w.:])(?:nodes\.[A-Za-z0-9_]+${PATH}|vars\.[A-Za-z_][A-Za-z0-9_]*${PATH}|input${PATH})(?![\w(])`, 'g')

/** Cuts a value into text and chips. */
export function parse(value: string, mode: MappingMode): Segment[] {
  const out: Segment[] = []
  const pattern = mode === 'template' ? PLACEHOLDER : REFERENCE
  let at = 0
  for (const m of value.matchAll(pattern)) {
    const expr = (mode === 'template' ? m[1] : m[0]).trim()
    if (!expr) continue
    if (m.index! > at) out.push({ kind: 'text', text: value.slice(at, m.index) })
    out.push({ kind: 'ref', expr })
    at = m.index! + m[0].length
  }
  if (at < value.length) out.push({ kind: 'text', text: value.slice(at) })
  return out
}

/** Puts text and chips back together as the value the engine reads. */
export function write(segments: Segment[], mode: MappingMode): string {
  return segments.map(s => (s.kind === 'text' ? s.text : mode === 'template' ? `{{ ${s.expr} }}` : s.expr)).join('')
}

/** One value a step can map in, such as a webhook's response body. */
export interface DataField { expr: string; label: string; type?: ValueType }

/** A field a step is known to give before it has run, such as a module's output. */
export interface DeclaredField { path: string; type?: ValueType }

/** A step (or the run's input, or its variables) and the values it gives later steps. */
export interface DataSource { id: string; label: string; type: NodeType | 'VARS'; fields: DataField[] }

/**
 * What each kind of step gives later steps, as nodes.<id>.<field>. This mirrors the engine's
 * executors; a step whose output depends on what it calls (a transform, a service action) lists
 * none, and its fields come from its last run instead.
 */
export const STEP_OUTPUTS: Partial<Record<NodeType, string[]>> = {
  AGENT_STEP: ['text', 'agent', 'truncated'],
  HUMAN_INTERVENTION: ['instruction'],
  APPROVAL: ['decision', 'comment', 'timedOut'],
  HTTP_REQUEST: ['status', 'ok', 'body', 'headers'],
  FILTER: ['passed'],
  BRANCH: ['condition'],
  SWITCH: ['path', 'label'],
  STORAGE: ['value', 'previous', 'key'],
  NOTIFY: ['title', 'message', 'sent'],
  WAIT: ['waitedUntil'],
  FOR_EACH: ['count', 'results', 'failed'],
}

/** What the known outputs hold, so the formula editor can check and suggest by type. */
const OUTPUT_TYPES: Record<string, ValueType> = {
  text: 'text', agent: 'text', truncated: 'boolean', instruction: 'text', decision: 'text', comment: 'text',
  timedOut: 'boolean', status: 'number', ok: 'boolean', headers: 'map', passed: 'boolean', condition: 'boolean',
  label: 'text', key: 'text', title: 'text', message: 'text', sent: 'boolean', waitedUntil: 'date', count: 'number',
  results: 'list', failed: 'list',
}

/** The type of a value from a past run. */
export function typeOfValue(value: unknown): ValueType {
  if (typeof value === 'string') return /^\d{4}-\d{2}-\d{2}(T|$)/.test(value) ? 'date' : 'text'
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (Array.isArray(value)) return 'list'
  if (value && typeof value === 'object') return 'map'
  return 'any'
}

/** Field paths in a value from a past run, two levels deep, such as body.price, with their types. */
function pathsOf(value: unknown, depth = 2, prefix = ''): Array<{ path: string; type: ValueType }> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth === 0) return []
  return Object.entries(value as Record<string, unknown>).slice(0, 40).flatMap(([k, v]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) return []
    const path = prefix ? `${prefix}.${k}` : k
    return [{ path, type: typeOfValue(v) }, ...pathsOf(v, depth - 1, path)]
  })
}

/**
 * What a step can map in: the run's input, each step before it with the fields it gives, and the
 * variables set before it. {@code outputs} are the steps' outputs from the last run, which add the
 * fields a step actually returned, such as the keys of a webhook's response body. {@code declared}
 * gives the fields a step is known to return before it has run, such as an MCP tool's output fields
 * or a module's outputs, with their types where known. The run's input lists the inputs the start
 * step declares.
 */
export function dataSourcesFor(graph: CanvasGraph, id: string, outputs?: ReadonlyMap<string, unknown>,
  declared?: (node: CanvasNode) => Array<string | DeclaredField>): DataSource[] {
  const upstream = upstreamOf(graph, id)
  const sources: DataSource[] = []
  for (const n of upstream) {
    if (n.type === 'TRIGGER') {
      const inputs = moduleInputs(graph)
      const named = new Set(inputs.map(f => f.name))
      const fields = [
        ...inputs.map(f => ({ expr: `input.${f.name}`, label: f.name, type: f.type })),
        ...pathsOf(outputs?.get(n.id)).filter(p => !named.has(p.path)).map(p => ({ expr: `input.${p.path}`, label: p.path, type: p.type })),
      ]
      sources.push({ id: n.id, label: 'Start (the run\'s input)', type: n.type, fields: [{ expr: 'input', label: 'The whole input', type: 'map' }, ...fields] })
      continue
    }
    if (n.type === 'PARALLEL') continue
    // A step that calls something (an MCP tool) knows its fields from what that thing returns.
    // A script saved with its own code gives the outputs it declares, typed; one that runs a
    // function gives what the function returns, which comes through declared.
    const extra = [...(declared?.(n) ?? []), ...(n.type === 'SCRIPT' && !n.config.functionId ? fieldsOf(n.config, 'outputs').filter(f => f.name).map(f => ({ path: f.name, type: f.type })) : [])]
      .map(d => (typeof d === 'string' ? { path: d } : d))
    const declaredType = new Map(extra.filter(d => d.type).map(d => [d.path, d.type!]))
    const known = [...(STEP_OUTPUTS[n.type] ?? []), ...extra.map(d => d.path)]
    const seen = new Set(known)
    const ran = pathsOf(outputs?.get(n.id)).filter(p => !seen.has(p.path))
    const ranType = new Map(pathsOf(outputs?.get(n.id)).map(p => [p.path, p.type]))
    sources.push({
      id: n.id, label: n.label || NODE_META[n.type].label, type: n.type,
      fields: [
        { expr: `nodes.${n.id}`, label: 'The whole result', type: 'map' },
        ...known.map(p => ({ expr: `nodes.${n.id}.${p}`, label: p, type: declaredType.get(p) ?? OUTPUT_TYPES[p] ?? ranType.get(p) ?? 'any' })),
        ...ran.map(p => ({ expr: `nodes.${n.id}.${p.path}`, label: p.path, type: p.type })),
      ],
    })
  }
  const vars = variablesOf(upstream)
  if (vars.length) sources.push({ id: 'vars', label: 'Variables', type: 'VARS', fields: vars.map(v => ({ expr: `vars.${v}`, label: v, type: 'any' as const })) })
  return sources
}

/** What the formula editor knows about the data in {@code sources}. */
export function scopeFor(sources: DataSource[]): FormulaScope {
  const types = new Map(sources.flatMap(s => s.fields.map(f => [f.expr, f.type ?? 'any'] as const)))
  const steps = new Set(sources.filter(s => s.type !== 'VARS' && s.type !== 'TRIGGER').map(s => s.id))
  return {
    typeOf: ref => types.get(ref),
    hasStep: id => steps.has(id),
    hasVariable: name => types.has(`vars.${name}`),
  }
}

/** The variables set-variable steps keep, which later steps read as vars.<name>. */
export function variablesOf(nodes: CanvasNode[]): string[] {
  const names = nodes.filter(n => n.type === 'SET_VARIABLE')
    .flatMap(n => (Array.isArray(n.config.variables) ? n.config.variables as Array<{ name?: unknown }> : []).map(v => String(v?.name ?? '').trim()))
  return [...new Set(names.filter(Boolean))]
}

/**
 * How a chip reads: the step's name and the field ("Send webhook › body.price"), or the
 * expression itself when it does more than point at a value.
 */
export function chipLabel(expr: string, graph: CanvasGraph): { source?: string; field: string; formula: boolean } {
  const node = /^nodes\.([A-Za-z0-9_]+)((?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*)$/.exec(expr)
  if (node) {
    const n = graph.nodes.find(x => x.id === node[1])
    return { source: n ? n.label || NODE_META[n.type].label : node[1], field: node[2].replace(/^\./, '') || 'result', formula: false }
  }
  const input = /^input((?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*)$/.exec(expr)
  if (input) return { source: 'Start', field: input[1].replace(/^\./, '') || 'input', formula: false }
  const variable = /^vars\.([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+\])*)$/.exec(expr)
  if (variable) return { source: 'Variable', field: variable[1], formula: false }
  return { field: expr, formula: true }
}
