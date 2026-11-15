import type { EditOp, RecordedStep, RecordingInput } from '@/api/web'

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

/** A box typed into that is not a password (a username box): it can type a saved login's username. */
export const isUsernameBox = (s: RecordedStep): boolean =>
  !isPassword(s) && (s.step.action === 'browser.setText' || s.step.action === 'browser.typeInto')

/** The values in a step that can become an input. A password never can; it takes a saved login. */
export function mappableFields(s: RecordedStep): { field: string; value: string }[] {
  if (isPassword(s) || s.step.secretRef) return []
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

/** The live steps whose mappings use this input name (an input can feed several steps). */
export function stepsUsingInput(steps: RecordedStep[] | undefined, name: string): RecordedStep[] {
  return liveSteps(steps).filter(s =>
    Object.values(s.step.mappings ?? {}).some(m => m.name === name))
}

/** The edits that stop a field being an input: the recorded value stays and is used as is. */
export function unmapOps(targets: { step: RecordedStep; field: string }[]): EditOp[] {
  return targets.map(({ step, field }) => ({ op: 'map', seq: step.seq, field, input: null }))
}

/** List choices that follow the list this step chooses from (they would lose their anchor). */
export function dependentListChoices(steps: RecordedStep[] | undefined, s: RecordedStep): RecordedStep[] {
  const title = s.step.meta?.list?.title
  if (!title) return []
  return liveSteps(steps).filter(other => other.seq !== s.seq
    && other.step.meta?.list?.dependsOn?.title === title)
}

/** The page a step ran on (origin and path), for grouping; steps with no page inherit the previous one. */
function pageKey(s: RecordedStep): string | null {
  const url = s.step.meta?.url
  if (typeof url !== 'string' || !url) return null
  try {
    const parsed = new URL(url)
    return parsed.origin + parsed.pathname
  } catch {
    return url
  }
}

/**
 * Tidy up: steps grouped by the page they ran on, pages in the order first visited, the steps of
 * each page in their current relative order. Returns the new order of seqs and which moved, or
 * null when nothing changes or the recording has conditions (their blocks must not be split).
 */
export function pageArrangement(steps: RecordedStep[] | undefined):
  { order: number[]; moved: Set<number> } | { blocked: string } | null {
  const live = liveSteps(steps)
  if (live.some(isCondition)) {
    return { blocked: 'This recording has conditions, and Tidy up does not reorder those yet.' }
  }
  const keys: string[] = []
  let last = ''
  for (const s of live) {
    const key = pageKey(s) ?? last
    keys.push(key)
    last = key
  }
  const pageOrder: string[] = []
  for (const key of keys) if (!pageOrder.includes(key)) pageOrder.push(key)
  const order = pageOrder.flatMap(page => live.filter((_, i) => keys[i] === page).map(s => s.seq))
  const moved = new Set(order.filter((seq, i) => live[i]?.seq !== seq))
  return moved.size === 0 ? null : { order, moved }
}

/** The move edits that take the live steps to the given seq order, as the server applies them. */
export function movesFor(steps: RecordedStep[] | undefined, order: number[]): EditOp[] {
  return movesBetween(liveSteps(steps).map(s => s.seq), order)
}

/** The move edits that turn one seq order into another. */
export function movesBetween(from: number[], order: number[]): EditOp[] {
  const current = [...from]
  const ops: EditOp[] = []
  for (let i = 0; i < order.length; i++) {
    if (current[i] === order[i]) continue
    const from = current.indexOf(order[i])
    current.splice(from, 1)
    current.splice(i, 0, order[i])
    ops.push({ op: 'move', seq: order[i], toIndex: i })
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

/**
 * Conditions a save leaves out because nothing is inside them (straight followed by their end, or
 * by an "Otherwise" with nothing under it), as the server's step compiler does. Their seqs.
 */
export function emptyConditions(steps: RecordedStep[]): number[] {
  const left = steps.map(s => ({ action: s.step.action, seq: s.seq }))
  const empty: number[] = []
  let removed = true
  while (removed) {
    removed = false
    let depth = 0
    for (let i = 0; i < left.length && !removed; i++) {
      const { action } = left[i]
      const next = left[i + 1]?.action ?? ''
      if (action === 'flow.if') depth++
      if (action === 'flow.endIf') depth = Math.max(0, depth - 1)
      if (action === 'flow.else' && (depth === 0 || next === 'flow.endIf' || next === 'flow.else')) {
        left.splice(i, 1); removed = true
      } else if (action === 'flow.if' && next === 'flow.endIf') {
        empty.push(left[i].seq); left.splice(i, 2); removed = true
      }
    }
  }
  return empty
}

/** The optional details of an input as the Make-input dialog edits them: plain text boxes. */
export interface InputDetails {
  label: string
  group: string
  ask: '' | 'atStep' | 'ifMissing'
  options: string
  synonyms: string
  min: string
  max: string
  pattern: string
  patternHint: string
}

export function detailsOf(m: RecordingInput | undefined): InputDetails {
  return {
    label: m?.label ?? '',
    group: m?.group ?? '',
    ask: m?.ask === 'atStep' || m?.ask === 'ifMissing' ? m.ask : '',
    options: (m?.options ?? []).join('\n'),
    synonyms: Object.entries(m?.synonyms ?? {}).map(([k, v]) => `${k} = ${v}`).join('\n'),
    min: m?.min !== undefined ? String(m.min) : '',
    max: m?.max !== undefined ? String(m.max) : '',
    pattern: m?.pattern ?? '',
    patternHint: m?.patternHint ?? '',
  }
}

const lines = (text: string) => text.split('\n').map(l => l.trim()).filter(Boolean)

function synonymsOf(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of lines(text)) {
    const at = line.lastIndexOf('=')
    if (at > 0 && line.slice(at + 1).trim()) out[line.slice(0, at).trim()] = line.slice(at + 1).trim()
  }
  return out
}

/** What is wrong with the details as typed, or null. */
export function detailsProblem(d: InputDetails): string | null {
  const min = d.min.trim() ? Number(d.min) : undefined
  const max = d.max.trim() ? Number(d.max) : undefined
  if ((min !== undefined && !Number.isFinite(min)) || (max !== undefined && !Number.isFinite(max))) return 'The smallest and largest must be numbers'
  if (min !== undefined && max !== undefined && min > max) return "The smallest can't be above the largest"
  if (d.pattern.trim()) {
    try { new RegExp(d.pattern) } catch { return 'That pattern is not a valid regular expression' }
  }
  const options = new Set(lines(d.options))
  const unknown = Object.values(synonymsOf(d.synonyms)).find(v => !options.has(v))
  if (unknown) return `"${unknown}" is not one of the accepted values`
  return null
}

/** The details as the input's keys, leaving out what was left empty. */
export function detailsInput(d: InputDetails, type: string): Partial<RecordingInput> {
  const options = lines(d.options)
  const synonyms = synonymsOf(d.synonyms)
  return {
    ...(d.label.trim() ? { label: d.label.trim() } : {}),
    ...(d.group.trim() ? { group: d.group.trim() } : {}),
    ...(d.ask ? { ask: d.ask } : {}),
    ...(options.length ? { options } : {}),
    ...(options.length && Object.keys(synonyms).length ? { synonyms } : {}),
    ...(type === 'number' && d.min.trim() ? { min: Number(d.min) } : {}),
    ...(type === 'number' && d.max.trim() ? { max: Number(d.max) } : {}),
    ...(type === 'text' && !options.length && d.pattern.trim() ? { pattern: d.pattern.trim() } : {}),
    ...(type === 'text' && !options.length && d.pattern.trim() && d.patternHint.trim() ? { patternHint: d.patternHint.trim() } : {}),
  }
}
