import type { EditOp, RecordedStep } from '@/api/web'

/** Fields of a step that hold a typed value a person might want to change on each run.
 *  `item` and `within.item` are what a "Choose from a list" step chooses (the movie, and the
 *  theatre whose showtimes it chooses among). */
const VALUE_FIELDS = ['text', 'url', 'value', 'keys', 'query', 'item', 'within.item', 'items', 'date', 'state'] as const

/** A field of a step's input, where `within.item` is field `item` of object `within`. */
function valueAt(input: Record<string, unknown>, field: string): unknown {
  let node: unknown = input
  for (const part of field.split('.')) {
    if (typeof node !== 'object' || node === null) return undefined
    node = (node as Record<string, unknown>)[part]
  }
  return node
}

/** The steps still in the recording, in the order they will run. */
export const liveSteps = (steps: RecordedStep[] | undefined): RecordedStep[] =>
  [...(steps ?? [])].filter(s => !s.deleted).sort((a, b) => a.position - b.position)

export const isPassword = (s: RecordedStep): boolean => s.step.meta?.sensitive === true

/** The values in a step that can become an input. A password never can; it takes a saved login. */
export function mappableFields(s: RecordedStep): { field: string; value: string }[] {
  if (isPassword(s)) return []
  const input = s.step.input ?? {}
  // A form step (browser agent 0.8.0+): each field's value.
  const form = (Array.isArray(input.fields) ? input.fields : []).flatMap((f, i) => {
    const v = (f as { value?: unknown } | null)?.value
    return typeof v === 'string' && v !== '' ? [{ field: `fields.${i}.value`, value: v }] : []
  })
  return [...form, ...VALUE_FIELDS.flatMap(field => {
    let v = valueAt(input, field)
    // A dropdown's one chosen item (items: ["Sleeper"]) is a value too.
    if (Array.isArray(v) && v.length === 1) v = v[0]
    return typeof v === 'string' || typeof v === 'number' ? [{ field, value: String(v) }] : []
  })]
}

const FIELD_NAMES: Record<string, string> = { item: 'Item chosen', 'within.item': 'Chosen in', items: 'Option chosen', date: 'Date', state: 'Ticked or not' }

/** What a value of a step is called: a form field's label, or the names given to a marked list. */
export function fieldName(s: RecordedStep, field: string): string {
  const form = /^fields\.(\d+)\.value$/.exec(field)
  if (form) {
    const fields = s.step.input?.fields
    const label = Array.isArray(fields) ? (fields[Number(form[1])] as { label?: unknown } | undefined)?.label : undefined
    return typeof label === 'string' && label ? label : `Field ${Number(form[1]) + 1}`
  }
  const list = s.step.meta?.list
  if (list?.named && field === 'item' && list.title) return list.title
  if (list?.named && field === 'within.item' && list.withinTitle) return list.withinTitle
  return FIELD_NAMES[field] ?? field
}

/** The name an input gets by default: the last words of the step's label, as a lowercase word. */
export function suggestInputName(s: RecordedStep, field: string): string {
  const list = s.step.meta?.list
  const offered = field === 'item' ? list?.inputName : field === 'within.item' ? list?.withinInputName : undefined
  if (offered && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(offered)) return offered
  const quoted = field.startsWith('fields.') ? fieldName(s, field) : /"([^"]+)"/.exec(s.step.label ?? '')?.[1] ?? field
  const words = quoted.replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ').filter(Boolean)
  if (words.length === 0) return field
  return words.map((w, i) => (i === 0 ? w.toLowerCase() : w[0].toUpperCase() + w.slice(1).toLowerCase())).join('')
}

/** The edit for dragging or nudging a step to a new place among the live steps. */
export const moveOp = (s: RecordedStep, toIndex: number): EditOp => ({ op: 'move', seq: s.seq, toIndex })

/** Password steps that still have no saved login: the recording can't be saved until each has one. */
export const missingLogins = (steps: RecordedStep[] | undefined): RecordedStep[] =>
  liveSteps(steps).filter(s => isPassword(s) && !s.step.secretRef)

/** The host of a start page, or null while the address is not a full http(s) URL yet. */
export function hostOf(url: string): string | null {
  try {
    const parsed = new URL(url.trim())
    return /^https?:$/.test(parsed.protocol) && parsed.hostname ? parsed.hostname.toLowerCase() : null
  } catch {
    return null
  }
}

/** Whether a host is on a browser's allowed-sites list (`example.com` or `*.example.com`), as the extension checks it. */
export function siteAllowed(host: string, patterns: string[]): boolean {
  return patterns.some(raw => {
    const pattern = raw.trim().toLowerCase()
    if (pattern.startsWith('*.')) return host.endsWith(pattern.slice(1))
    return host === pattern
  })
}

/** Whether the step chooses an item from a list the page shows. */
export const isListChoice = (s: RecordedStep): boolean => s.step.action === 'browser.clickItem'

/**
 * The edits that make a recording reusable: each list choice's item (and the card it is chosen
 * in) not yet an input becomes one, named as the recording suggests and unique, with the recorded
 * item as its default. Run as is, the workflow does what was recorded; given other values, it
 * chooses another movie, date or showtime.
 */
export function reusableOps(steps: RecordedStep[] | undefined): EditOp[] {
  const live = liveSteps(steps)
  const used = new Set(live.flatMap(s => Object.values(s.step.mappings ?? {}).map(m => m.name)))
  const ops: EditOp[] = []
  for (const s of live) {
    if (!isListChoice(s)) continue
    for (const { field, value } of mappableFields(s)) {
      if ((field !== 'item' && field !== 'within.item') || s.step.mappings?.[field]) continue
      const base = suggestInputName(s, field)
      let name = base
      for (let n = 2; used.has(name); n++) name = `${base}${n}`
      used.add(name)
      ops.push({ op: 'map', seq: s.seq, field, input: { name, type: 'text', default: value } })
    }
  }
  return ops
}

/** A condition's start (`flow.if`), its "Otherwise" (`flow.else`) or its end (`flow.endIf`). */
export function isCondition(step: RecordedStep): boolean {
  return step.step.action === 'flow.if' || step.step.action === 'flow.else' || step.step.action === 'flow.endIf'
}

/** How deep in conditions each step is: a condition's own steps are one deeper than it. */
export function conditionDepths(steps: RecordedStep[]): number[] {
  let depth = 0
  return steps.map(s => {
    if (s.step.action === 'flow.endIf') { depth = Math.max(0, depth - 1); return depth }
    // "Otherwise" lines up with its condition; the steps after it are inside again.
    if (s.step.action === 'flow.else') return Math.max(0, depth - 1)
    const at = depth
    if (s.step.action === 'flow.if') depth += 1
    return at
  })
}
