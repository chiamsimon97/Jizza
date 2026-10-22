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
export interface DataField { expr: string; label: string }

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

/** Field paths in a value from a past run, two levels deep, such as body.price. */
function pathsOf(value: unknown, depth = 2, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth === 0) return []
  return Object.entries(value as Record<string, unknown>).slice(0, 40).flatMap(([k, v]) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) return []
    const path = prefix ? `${prefix}.${k}` : k
    return [path, ...pathsOf(v, depth - 1, path)]
  })
}

/**
 * What a step can map in: the run's input, each step before it with the fields it gives, and the
 * variables set before it. {@code outputs} are the steps' outputs from the last run, which add the
 * fields a step actually returned, such as the keys of a webhook's response body.
 */
export function dataSourcesFor(graph: CanvasGraph, id: string, outputs?: ReadonlyMap<string, unknown>): DataSource[] {
  const upstream = upstreamOf(graph, id)
  const sources: DataSource[] = []
  for (const n of upstream) {
    if (n.type === 'TRIGGER') {
      const fields = pathsOf(outputs?.get(n.id)).map(p => ({ expr: `input.${p}`, label: p }))
      sources.push({ id: n.id, label: 'Start (the run\'s input)', type: n.type, fields: [{ expr: 'input', label: 'The whole input' }, ...fields] })
      continue
    }
    if (n.type === 'PARALLEL') continue
    const known = STEP_OUTPUTS[n.type] ?? []
    const seen = new Set(known)
    const ran = pathsOf(outputs?.get(n.id)).filter(p => !seen.has(p))
    sources.push({
      id: n.id, label: n.label || NODE_META[n.type].label, type: n.type,
      fields: [{ expr: `nodes.${n.id}`, label: 'The whole result' }, ...[...known, ...ran].map(p => ({ expr: `nodes.${n.id}.${p}`, label: p }))],
    })
  }
  const vars = variablesOf(upstream)
  if (vars.length) sources.push({ id: 'vars', label: 'Variables', type: 'VARS', fields: vars.map(v => ({ expr: `vars.${v}`, label: v })) })
  return sources
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

/** Common fmt: functions offered when editing a chip; {@code x} is where the value goes. */
export const FUNCTIONS: Array<{ name: string; template: string; desc: string }> = [
  { name: 'Upper case', template: 'fmt:upper(x)', desc: 'HELLO' },
  { name: 'Lower case', template: 'fmt:lower(x)', desc: 'hello' },
  { name: 'Title case', template: 'fmt:titleCase(x)', desc: 'Hello World' },
  { name: 'Trim spaces', template: 'fmt:trim(x)', desc: 'Drops spaces at both ends' },
  { name: 'Default if empty', template: "fmt:defaultIfEmpty(x, '')", desc: 'A fallback for a blank value' },
  { name: 'To number', template: 'fmt:toNumber(x)', desc: '"₹ 1,234.50" → 1234.5' },
  { name: 'Round', template: 'fmt:round(x, 2)', desc: 'To 2 decimal places' },
  { name: 'Format number', template: 'fmt:formatNumber(x, 2)', desc: '1,23,456.50' },
  { name: 'Format currency', template: "fmt:formatCurrency(x, 'INR')", desc: '₹1,23,456.50' },
  { name: 'Format date', template: "fmt:formatDate(x, 'dd MMM yyyy')", desc: '22 Oct 2026' },
  { name: 'Add days', template: 'fmt:addDays(x, 1)', desc: 'A date a day later' },
  { name: 'Count', template: 'fmt:count(x)', desc: 'How many items' },
  { name: 'Join', template: "fmt:join(x, ', ')", desc: 'A list as text' },
  { name: 'First item', template: 'fmt:first(x)', desc: 'The first item of a list' },
]

/** Wraps an expression in a function from {@link FUNCTIONS}. */
export function applyFunction(template: string, expr: string): string {
  return template.replace(/\bx\b/, expr)
}
