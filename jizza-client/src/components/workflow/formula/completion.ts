/**
 * Suggestions while typing a formula, grouped the way Zoho Flow's editor groups them: the data of
 * each earlier step, then functions. After a dot they follow what is before it: a step's fields,
 * then the functions that suit its type ("Recommended"), then the rest.
 */
import type { Completion, CompletionContext, CompletionResult, CompletionSection } from '@codemirror/autocomplete'
import { startCompletion } from '@codemirror/autocomplete'
import type { EditorView } from '@codemirror/view'
import { NODE_META } from '../canvasModel'
import type { DataSource } from '../dataMapping'
import { analyze, type FormulaScope } from './analyze'
import { FORMULA_FUNCTIONS, fits, signature, type FormulaFunction, type ValueType } from './functions'
import { placeholdersIn, tokenize, type Token } from './parser'

export interface CompletionEnv { sources: DataSource[]; scope: FormulaScope }

/** Where the formula around {@code pos} starts and ends: the whole field, or one {{ }} placeholder. */
export function formulaAround(doc: string, pos: number, template: boolean): { from: number; to: number } | null {
  if (!template) return { from: 0, to: doc.length }
  const p = placeholdersIn(doc).find(x => x.innerFrom <= pos && pos <= x.innerTo)
  return p ? { from: p.innerFrom, to: p.innerTo } : null
}

/** A suggestion, with the type of value it gives for the badge on its right. */
export type FormulaCompletion = Completion & { valueType?: ValueType }

/** A group heading: the step's colour beside its name, or a plain heading for functions. */
const section = (name: string, rank: number, tone?: string): CompletionSection => ({
  name, rank,
  header: () => {
    const h = document.createElement('completion-section')
    if (tone) {
      const dot = document.createElement('span')
      dot.className = `cm-fx-section-dot ${tone}`
      h.append(dot)
    }
    h.append(document.createTextNode(name))
    return h
  },
})
const toneOf = (s: DataSource) => (s.type === 'VARS' ? 'bg-purple-600' : NODE_META[s.type].tone)
const CATEGORY_RANK: Record<string, number> = { Text: 0, Number: 1, Date: 2, List: 3, Check: 4 }

export function formulaCompletions(env: () => CompletionEnv, template: boolean) {
  return (ctx: CompletionContext): CompletionResult | null => {
    const doc = ctx.state.doc.toString()
    const region = formulaAround(doc, ctx.pos, template)
    if (!region) return null
    const before = doc.slice(region.from, ctx.pos)
    const tokens = tokenize(before, region.from)
    const last = tokens[tokens.length - 1]
    if (last?.kind === 'string' && last.open) return null
    const word = /[A-Za-z_$][\w$]*$/.exec(before)?.[0] ?? ''
    const from = ctx.pos - word.length
    const head = tokens.filter(t => t.to <= from)
    const dot = head[head.length - 1]
    const { sources, scope } = env()

    if (dot?.text === '.' && dot.to === from) {
      const start = receiverStart(head, head.length - 1)
      if (start < 0) return null
      const receiver = doc.slice(head[start].from, dot.from)
      return { from, options: afterDot(receiver, sources, scope), validFor: /^(?:[A-Za-z_$][\w$]*)?$/ }
    }
    if (!word && !ctx.explicit) return null
    // Just after a number, text or a closing bracket there is nothing to suggest.
    if (!word && last && last.to === ctx.pos && (last.kind === 'number' || last.kind === 'string' || last.text === ')' || last.text === ']')) return null
    return { from, options: anywhere(sources), validFor: /^(?:[A-Za-z_$][\w$]*)?$/ }
  }
}

/** Fields of a reference, and functions by how well they suit the value before the dot. */
function afterDot(receiver: string, sources: DataSource[], scope: FormulaScope): Completion[] {
  const options: FormulaCompletion[] = []
  const ref = receiver.trim()
  if (ref === 'nodes') {
    sources.filter(s => s.type !== 'VARS' && s.type !== 'TRIGGER').forEach(s => {
      options.push({ label: s.label, apply: s.id, detail: s.id, type: 'variable', section: section('Earlier steps', 0) })
    })
    return options
  }
  const prefix = `${ref}.`
  const seen = new Set<string>()
  for (const s of sources) {
    for (const f of s.fields) {
      if (!f.expr.startsWith(prefix)) continue
      const next = /^[A-Za-z_$][\w$]*/.exec(f.expr.slice(prefix.length))?.[0]
      if (!next || seen.has(next)) continue
      seen.add(next)
      const exact = sources.flatMap(x => x.fields).find(x => x.expr === prefix + next)
      options.push({ label: next, valueType: exact?.type ?? 'map', type: 'property', section: section('Fields', 0), boost: 1 })
    }
  }
  const type = analyze(ref, scope).type
  for (const f of FORMULA_FUNCTIONS) {
    if (!f.subject) continue
    const good = type !== 'any' && f.subject.type === type
    const fine = fits(f.subject.type, type)
    if (type !== 'any' && !fine) continue
    const sec = good ? section('Recommended functions', 1)
      : type === 'any' ? section(`Functions of ${f.category}`, 2 + CATEGORY_RANK[f.category]) : section('Other functions', 2)
    options.push(functionOption(f, true, sec))
  }
  return options
}

/** At the start of a value: each earlier step's data, the variables, then every function. */
function anywhere(sources: DataSource[]): Completion[] {
  const options: FormulaCompletion[] = []
  sources.forEach((s, i) => {
    const sec = section(s.label, i, toneOf(s))
    for (const f of s.fields) {
      options.push({ label: f.expr, displayLabel: f.label, valueType: f.type ?? 'any', type: 'variable', section: sec })
    }
  })
  const base = sources.length
  for (const f of FORMULA_FUNCTIONS) options.push(functionOption(f, false, section(`Functions of ${f.category}`, base + CATEGORY_RANK[f.category])))
  for (const k of ['true', 'false', 'null']) options.push({ label: k, type: 'keyword', section: section('Values', base + 10) })
  return options
}

function functionOption(f: FormulaFunction, chained: boolean, sec: CompletionSection): FormulaCompletion {
  const values = [...(!chained && f.subject ? [f.subject] : []), ...f.params]
  const inBrackets = values.length
  return {
    label: f.name, detail: values.length ? `(${values.map(p => p.name).join(', ')})` : undefined, valueType: f.returns, type: 'function', section: sec,
    info: () => functionDoc(f),
    apply: (view: EditorView, _c: Completion, from: number, to: number) => {
      const text = `${f.name}()`
      const cursor = from + f.name.length + (inBrackets ? 1 : 2)
      view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: cursor }, userEvent: 'input.complete' })
      // Straight on to the first value in brackets, which is usually data from an earlier step.
      if (inBrackets) startCompletion(view)
    },
  }
}

/** The doc popup for a function: its signature, what it does, what it takes, and an example. */
export function functionDoc(f: FormulaFunction): HTMLElement {
  const el = (tag: string, cls: string, text?: string) => {
    const e = document.createElement(tag)
    e.className = cls
    if (text !== undefined) e.textContent = text
    return e
  }
  const box = el('div', 'cm-fx-doc')
  const head = el('div', 'cm-fx-doc-head')
  head.append(el('span', 'cm-fx-doc-name', f.name), el('span', `cm-fx-type cm-fx-type-${f.returns}`, f.returns))
  box.append(head, el('code', 'cm-fx-doc-sig', signature(f)), el('p', 'cm-fx-doc-desc', f.description))
  const values = [...(f.subject ? [f.subject] : []), ...f.params]
  if (values.length) {
    const list = el('ul', 'cm-fx-doc-params')
    values.forEach((p, i) => {
      const li = el('li', '')
      li.append(el('code', '', p.name), el('span', `cm-fx-type cm-fx-type-${p.type}`, p.type))
      if (i === 0 && f.subject) li.append(el('span', 'cm-fx-doc-note', 'the value before the dot'))
      else if ('optional' in p && p.optional) li.append(el('span', 'cm-fx-doc-note', 'optional'))
      list.append(li)
    })
    box.append(list)
  }
  const ex = el('div', 'cm-fx-doc-example')
  ex.append(el('span', 'cm-fx-doc-note', 'Example'), el('code', '', f.example))
  box.append(ex)
  return box
}

/**
 * Where the value ends at {@code last} starts: walks back over its .field, [index] and call steps,
 * so the receiver of input.items[0].name.trim(). is everything before the last dot.
 */
export function receiverStart(tokens: Token[], dotIndex: number): number {
  let i = dotIndex - 1
  for (;;) {
    const t = tokens[i]
    if (!t) return -1
    let start: number
    if (t.text === ')' || t.text === ']') {
      const open = matchBack(tokens, i)
      if (open < 0) return -1
      start = open
      const before = tokens[open - 1]
      if (t.text === ')' && before?.kind === 'name') {
        start = open - 1
        const colon = tokens[start - 1]
        if (colon?.text === ':' && colon.to === tokens[start].from && tokens[start - 2]?.kind === 'name') start -= 2
      } else if (t.text === ']' && before && (before.kind === 'name' || before.kind === 'string' || before.text === ')' || before.text === ']')) {
        i = open - 1
        continue
      }
    } else if (t.kind === 'name' || t.kind === 'number' || t.kind === 'string' || t.kind === 'keyword') {
      start = i
    } else {
      return -1
    }
    if (tokens[start - 1]?.text === '.') { i = start - 2; continue }
    return start
  }
}

function matchBack(tokens: Token[], close: number): number {
  let depth = 0
  for (let i = close; i >= 0; i--) {
    const t = tokens[i].text
    if (tokens[i].kind !== 'punct') continue
    if (t === ')' || t === ']' || t === '}') depth++
    else if (t === '(' || t === '[' || t === '{') { if (--depth === 0) return i }
  }
  return -1
}

/** The call the cursor is in and which of its values it is on, for the signature hint. */
export function callAt(doc: string, pos: number, template: boolean): { fn: FormulaFunction; chained: boolean; arg: number } | null {
  const region = formulaAround(doc, pos, template)
  if (!region) return null
  const tokens = tokenize(doc.slice(region.from, pos), region.from)
  let depth = 0
  let arg = 0
  for (let i = tokens.length - 1; i >= 0; i--) {
    const t = tokens[i]
    if (t.kind !== 'punct') continue
    if (t.text === ')' || t.text === ']' || t.text === '}') depth++
    else if (t.text === '[' || t.text === '{') { if (depth === 0) return null; depth-- }
    else if (t.text === ',' && depth === 0) arg++
    else if (t.text === '(') {
      if (depth > 0) { depth--; continue }
      const name = tokens[i - 1]
      if (name?.kind !== 'name') return null
      const fn = FORMULA_FUNCTIONS.find(f => f.name === name.text)
      if (!fn) return null
      return { fn, chained: tokens[i - 2]?.text === '.', arg }
    }
  }
  return null
}

