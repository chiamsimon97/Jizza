/**
 * The CodeMirror side of a mapped field: syntax colours, chips for data from earlier steps, typed
 * suggestions with docs, a signature hint inside a call's brackets, and checks as you type.
 *
 * A field is one of two kinds. An expression field is one formula; each reference in it shows as a
 * chip, and clicking one points it at different data. A template field is text with {{ formula }}
 * placeholders; each shows as a chip until the cursor goes into it, where the formula can be edited
 * as script. Either way the document is the plain text the engine reads.
 */
import { autocompletion, startCompletion } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { linter } from '@codemirror/lint'
import { EditorState, Prec, StateEffect, StateField, Transaction, type Extension } from '@codemirror/state'
import {
  Decoration, EditorView, ViewPlugin, WidgetType, keymap, placeholder as placeholderText, showTooltip, tooltips,
  type DecorationSet, type Tooltip, type ViewUpdate,
} from '@codemirror/view'
import type { CanvasGraph } from '../canvasModel'
import { chipLabel, type DataSource } from '../dataMapping'
import { analyze, type FormulaScope, type Problem } from './analyze'
import { callAt, formulaCompletions, type FormulaCompletion } from './completion'
import { parseFormula, placeholdersIn, referencesIn, tokenize, type Node, type Token } from './parser'

export interface FormulaEnv { graph: CanvasGraph; sources: DataSource[]; scope: FormulaScope }

/** A chip: a reference in an expression field, or a whole {{ }} placeholder in a template field. */
export interface Chip { from: number; to: number; expr: string; innerFrom?: number; innerTo?: number }

export interface FormulaOptions {
  template: boolean
  multiline: boolean
  placeholder?: string
  env: () => FormulaEnv
  /** A chip in an expression field was clicked: point it at different data. */
  onChipClick: (chip: Chip) => void
  /** Ctrl/Cmd+E: open the list of earlier steps' data. */
  onPicker: () => void
}

/** Tells the editor its data changed (another step was renamed or ran), so chips and checks redo. */
export const refreshFormula = StateEffect.define<null>()

/**
 * Every problem in a field, with positions in the field. {@code typingAt} is the cursor: a
 * placeholder still empty or open there is being written, so it isn't a problem yet.
 */
export function problemsIn(doc: string, template: boolean, scope: FormulaScope, typingAt?: number): Problem[] {
  if (!template) return analyze(doc, scope).problems
  return placeholdersIn(doc).flatMap(p => {
    const inner = doc.slice(p.innerFrom, p.innerTo)
    const typing = typingAt !== undefined && typingAt >= p.innerFrom && typingAt <= p.innerTo
    if (!p.closed) return typing ? [] : [{ from: p.from, to: p.to, severity: 'error' as const, message: 'Close this with }}' }]
    if (!inner.trim()) return typing ? [] : [{ from: p.from, to: p.to, severity: 'error' as const, message: 'This placeholder is empty' }]
    return analyze(inner, scope, p.innerFrom).problems
  })
}

class ChipWidget extends WidgetType {
  constructor(readonly expr: string, readonly source: string | undefined, readonly field: string, readonly formula: boolean,
    readonly severity: 'error' | 'warning' | undefined, readonly message: string | undefined) { super() }

  eq(o: ChipWidget) {
    return o.expr === this.expr && o.source === this.source && o.field === this.field && o.severity === this.severity && o.message === this.message
  }

  toDOM() {
    const chip = document.createElement('span')
    chip.className = `cm-fx-chip ${this.formula ? 'cm-fx-chip-formula' : ''} ${this.severity ? `cm-fx-chip-${this.severity}` : ''}`
    chip.dataset.expr = this.expr
    chip.dataset.chip = ''
    chip.title = this.message ? `${this.expr}\n${this.message}` : this.expr
    const part = (cls: string, text: string) => {
      const s = document.createElement('span')
      s.className = cls
      s.textContent = text
      return s
    }
    if (this.source) chip.append(part('cm-fx-chip-source', this.source), part('cm-fx-chip-sep', '›'))
    else if (this.formula) chip.append(part('cm-fx-chip-fx', 'ƒ'))
    chip.append(part('cm-fx-chip-field', this.field))
    return chip
  }

  ignoreEvent() { return false }
}

/** A long path such as body.order.lines.0.price reads as body › … › price; the chip's title has it all. */
function shorten(path: string): string {
  const parts = path.split('.')
  return parts.length > 3 ? `${parts[0]}.….${parts[parts.length - 1]}` : path
}

/** How a placeholder's formula reads on its chip: the function and the data it works on. */
function chipFace(expr: string, graph: CanvasGraph): { source?: string; field: string; formula: boolean } {
  const plain = chipLabel(expr, graph)
  if (!plain.formula) return { ...plain, field: shorten(plain.field) }
  const parsed = parseFormula(expr)
  if (!parsed.ok || !parsed.node) return { field: expr, formula: true }
  const call = outerCall(parsed.node)
  const ref = referencesIn(tokenize(expr))[0]
  const data = ref ? chipLabel(ref.text, graph) : null
  const what = data && !data.formula ? `${data.source} › ${shorten(data.field)}` : ''
  if (call && what) return { field: `${call}(${what})`, formula: true }
  return { field: expr.length > 40 ? `${expr.slice(0, 38)}…` : expr, formula: true }
}

function outerCall(node: Node): string | null {
  if (node.kind === 'call') return node.name
  if (node.kind === 'group') return outerCall(node.inner)
  return null
}

const TOKEN_CLASS: Partial<Record<Token['kind'], string>> = {
  number: 'cm-fx-num', string: 'cm-fx-str', keyword: 'cm-fx-kw', op: 'cm-fx-op', error: 'cm-fx-err',
}

function tokenMarks(tokens: Token[], out: Array<{ from: number; to: number; cls: string }>) {
  tokens.forEach((t, i) => {
    let cls = TOKEN_CLASS[t.kind]
    if (t.kind === 'name') {
      if (tokens[i + 1]?.text === '(' || (tokens[i + 1]?.text === ':' && tokens[i + 1].from === t.to)) cls = 'cm-fx-fn'
      else if (tokens[i - 1]?.text === '.') cls = 'cm-fx-field'
      else cls = 'cm-fx-ref'
    }
    if (cls && t.to > t.from) out.push({ from: t.from, to: t.to, cls })
  })
}

function decorate(view: EditorView, o: FormulaOptions): { all: DecorationSet; atomic: DecorationSet; chips: Chip[] } {
  const doc = view.state.doc.toString()
  const env = o.env()
  const heads = view.hasFocus ? view.state.selection.ranges.map(r => r.head) : []
  const problems = problemsIn(doc, o.template, env.scope, heads[0])
  const chips: Chip[] = []
  const marks: Array<{ from: number; to: number; cls: string }> = []

  if (o.template) {
    for (const p of placeholdersIn(doc)) {
      const editing = heads.some(h => h >= p.innerFrom && h <= p.innerTo)
      const inner = doc.slice(p.innerFrom, p.innerTo)
      // A chip can't stand in for a line break, so a placeholder over two lines stays text.
      if (p.closed && !editing && inner.trim() && !inner.includes('\n')) {
        chips.push({ from: p.from, to: p.to, expr: inner.trim(), innerFrom: p.innerFrom, innerTo: p.innerTo })
        continue
      }
      marks.push({ from: p.from, to: p.innerFrom, cls: 'cm-fx-brace' })
      tokenMarks(tokenize(inner, p.innerFrom), marks)
      if (p.closed) marks.push({ from: p.innerTo, to: p.to, cls: 'cm-fx-brace' })
    }
  } else {
    const tokens = tokenize(doc)
    // A reference being typed (the cursor inside it, or at its end before it names known data) shows
    // as text, and becomes a chip once it points at something or the cursor leaves.
    for (const r of referencesIn(tokens)) {
      const typing = heads.some(h => (h > r.from && h < r.to) || (h === r.to && env.scope.typeOf(r.text) === undefined))
      if (!typing) chips.push({ from: r.from, to: r.to, expr: r.text })
    }
    tokenMarks(tokens.filter(t => !chips.some(c => t.from >= c.from && t.to <= c.to)), marks)
  }

  const chipDecos = chips.map(c => {
    const hits = problems.filter(p => p.from < c.to && p.to > c.from)
    const severity = hits.some(h => h.severity === 'error') ? 'error' : hits.length ? 'warning' : undefined
    const face = chipFace(c.expr, env.graph)
    const widget = new ChipWidget(c.expr, face.source, face.field, face.formula, severity, hits[0]?.message)
    return Decoration.replace({ widget }).range(c.from, c.to)
  })
  const markDecos = marks.map(m => Decoration.mark({ class: m.cls }).range(m.from, m.to))
  return { all: Decoration.set([...chipDecos, ...markDecos], true), atomic: Decoration.set(chipDecos, true), chips }
}

function chipPlugin(o: FormulaOptions) {
  return ViewPlugin.fromClass(class {
    all: DecorationSet
    atomic: DecorationSet
    chips: Chip[]
    constructor(view: EditorView) {
      ({ all: this.all, atomic: this.atomic, chips: this.chips } = decorate(view, o))
    }
    update(u: ViewUpdate) {
      if (u.docChanged || u.selectionSet || u.focusChanged || u.transactions.some(t => t.effects.some(e => e.is(refreshFormula)))) {
        ({ all: this.all, atomic: this.atomic, chips: this.chips } = decorate(u.view, o))
      }
    }
  }, {
    decorations: v => v.all,
    provide: plugin => EditorView.atomicRanges.of(view => view.plugin(plugin)?.atomic ?? Decoration.none),
    eventHandlers: {
      mousedown(e, view) {
        const el = (e.target as HTMLElement).closest<HTMLElement>('[data-chip]')
        if (!el || !view.state.facet(EditorView.editable)) return false
        const pos = view.posAtDOM(el)
        const chip = this.chips.find(c => c.from === pos) ?? this.chips.find(c => pos >= c.from && pos <= c.to)
        if (!chip) return false
        e.preventDefault()
        if (o.template && chip.innerTo !== undefined) {
          // Into the placeholder, where its formula opens up for editing.
          const inner = view.state.doc.sliceString(chip.innerFrom!, chip.innerTo)
          const at = chip.innerFrom! + inner.trimEnd().length
          view.focus()
          view.dispatch({ selection: { anchor: at } })
        } else {
          o.onChipClick(chip)
        }
        return true
      },
    },
  })
}

/** The function's values above the cursor while it is inside the brackets, the current one in bold. */
function signatureHint(template: boolean) {
  const hint = (state: EditorState): Tooltip | null => {
    const sel = state.selection.main
    if (!sel.empty) return null
    const call = callAt(state.doc.toString(), sel.head, template)
    if (!call) return null
    const { fn, chained, arg } = call
    const values = [...(!chained && fn.subject ? [fn.subject] : []), ...fn.params]
    return {
      pos: sel.head, above: true, strictSide: true, arrow: false,
      create: () => {
        const dom = document.createElement('div')
        dom.className = 'cm-fx-signature'
        dom.append(document.createTextNode(`${fn.name}(`))
        values.forEach((p, i) => {
          if (i) dom.append(document.createTextNode(', '))
          const s = document.createElement('span')
          if (i === arg) s.className = 'cm-fx-signature-active'
          s.textContent = `${p.name}${'optional' in p && p.optional ? '?' : ''}: ${p.type}`
          dom.append(s)
        })
        dom.append(document.createTextNode(`) → ${fn.returns}`))
        return { dom }
      },
    }
  }
  // Shown only while the field has focus, so it doesn't linger over the next field.
  const field = StateField.define<{ focused: boolean; tip: Tooltip | null }>({
    create: () => ({ focused: false, tip: null }),
    update: (v, tr) => {
      const focus = tr.effects.find(e => e.is(focusChanged))
      const focused = focus ? focus.value : v.focused
      if (!focused) return v.focused || v.tip ? { focused, tip: null } : v
      return focus || tr.docChanged || tr.selection ? { focused, tip: hint(tr.state) } : v
    },
    provide: f => showTooltip.from(f, v => v.tip),
  })
  return [field, EditorView.focusChangeEffect.of((_s, focusing) => focusChanged.of(focusing))]
}

const focusChanged = StateEffect.define<boolean>()

/** Keeps a one-line field on one line: pasted line breaks become spaces. */
const singleLine = EditorState.transactionFilter.of(tr => {
  if (!tr.docChanged) return tr
  let breaks = false
  tr.changes.iterChanges((_a, _b, _c, _d, inserted) => { if (inserted.lines > 1) breaks = true })
  if (!breaks) return tr
  const changes: Array<{ from: number; to: number; insert: string }> = []
  tr.changes.iterChanges((from, to, _c, _d, inserted) => changes.push({ from, to, insert: inserted.toString().replace(/\s*\n\s*/g, ' ') }))
  return { changes, userEvent: tr.annotation(Transaction.userEvent) }
})

/** In a template, typing {{ opens a placeholder with suggestions, the way the {} button does. */
const openPlaceholder = EditorView.inputHandler.of((view, from, to, text) => {
  if (text !== '{' || view.state.doc.sliceString(from - 1, from) !== '{') return false
  if (view.state.doc.sliceString(to, to + 2) === '}}') return false
  view.dispatch({ changes: { from, to, insert: '{  }}' }, selection: { anchor: from + 2 }, userEvent: 'input.type' })
  startCompletion(view)
  return true
})

/** Colours come from the app theme's variables, so the editor and its popups follow light, dark and sunset. */
const SANS = "'Inter', ui-sans-serif, system-ui, sans-serif"
const MONO = "'JetBrains Mono', ui-monospace, monospace"
const theme = EditorView.theme({
  '&': { backgroundColor: 'transparent' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': { fontFamily: 'inherit', lineHeight: '1.5rem', overflow: 'visible' },
  '.cm-content': { padding: '0', caretColor: 'rgb(var(--brand-500))', fontFamily: 'inherit', minHeight: '1.5rem' },
  '.cm-line': { padding: '0' },
  '.cm-tooltip': {
    border: '1px solid rgb(var(--fx-border))', borderRadius: '10px', backgroundColor: 'rgb(var(--fx-surface))',
    color: 'rgb(var(--fx-text))', boxShadow: '0 16px 32px -12px rgb(0 0 0 / .28), 0 2px 6px -2px rgb(0 0 0 / .08)',
    fontFamily: SANS, fontSize: '12px',
  },
  '.cm-tooltip.cm-tooltip-autocomplete': { padding: '4px 0' },
  '.cm-tooltip.cm-tooltip-autocomplete > ul': { borderRadius: '10px', fontFamily: SANS, maxHeight: '17rem', minWidth: '17rem', maxWidth: '24rem' },
  '.cm-tooltip.cm-tooltip-autocomplete > ul > li': {
    display: 'flex', alignItems: 'center', gap: '8px', padding: '4px 10px', lineHeight: '18px', borderRadius: '0', color: 'rgb(var(--fx-text))',
  },
  '.cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: 'rgb(var(--brand-500) / .12)', color: 'rgb(var(--fx-strong))' },
  '.cm-tooltip-autocomplete > ul > completion-section': {
    display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 10px 3px', border: 'none', opacity: '1',
    fontSize: '10px', fontWeight: '600', letterSpacing: '.05em', textTransform: 'uppercase', color: 'rgb(var(--gray-500))',
  },
  '.cm-fx-section-dot': { width: '8px', height: '8px', borderRadius: '3px', flex: 'none' },
  '.cm-completionIcon': {
    width: '18px', height: '18px', flex: 'none', display: 'grid', placeItems: 'center', padding: '0', marginRight: '0',
    borderRadius: '5px', fontSize: '11px', fontWeight: '700', opacity: '1', fontFamily: SANS,
  },
  '.cm-completionIcon-function': { backgroundColor: 'rgb(124 58 237 / .12)', color: '#7c3aed' },
  '.cm-completionIcon-function:after': { content: "'ƒ'", fontStyle: 'italic' },
  '.cm-completionIcon-variable, .cm-completionIcon-property': { backgroundColor: 'rgb(var(--brand-500) / .12)', color: 'rgb(var(--brand-600))' },
  '.cm-completionIcon-variable:after': { content: "'{}'", fontSize: '9px' },
  '.cm-completionIcon-property:after': { content: "'·'", fontSize: '16px' },
  '.cm-completionIcon-keyword': { backgroundColor: 'rgb(var(--gray-500) / .12)', color: 'rgb(var(--gray-600))' },
  '.cm-completionIcon-keyword:after': { content: "'≡'" },
  '.cm-completionLabel': { flex: '1', minWidth: '0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  '.cm-completionDetail': { marginLeft: '0', fontStyle: 'normal', fontFamily: MONO, fontSize: '10px', color: 'rgb(var(--gray-400))' },
  '.cm-completionMatchedText': { textDecoration: 'none', fontWeight: '700', color: 'rgb(var(--brand-600))' },
  '.cm-fx-type': {
    flex: 'none', borderRadius: '9999px', padding: '0 6px', fontSize: '10px', lineHeight: '16px', fontWeight: '500',
    backgroundColor: 'rgb(var(--gray-500) / .1)', color: 'rgb(var(--gray-500))',
  },
  '.cm-fx-type-text': { backgroundColor: 'rgb(16 185 129 / .12)', color: '#047857' },
  '.cm-fx-type-number': { backgroundColor: 'rgb(245 158 11 / .14)', color: '#b45309' },
  '.cm-fx-type-boolean': { backgroundColor: 'rgb(236 72 153 / .12)', color: '#be185d' },
  '.cm-fx-type-date': { backgroundColor: 'rgb(14 165 233 / .12)', color: '#0369a1' },
  '.cm-fx-type-list, .cm-fx-type-map': { backgroundColor: 'rgb(124 58 237 / .1)', color: '#6d28d9' },
  '.cm-tooltip.cm-completionInfo': { padding: '10px 12px', width: '18rem', maxWidth: '18rem' },
  '.cm-tooltip.cm-completionInfo.cm-completionInfo-right': { marginLeft: '4px' },
  '.cm-tooltip.cm-completionInfo.cm-completionInfo-left': { marginRight: '4px' },
  '.cm-tooltip-lint': { padding: '0' },
  '.cm-diagnostic': { padding: '6px 10px', fontFamily: SANS, fontSize: '12px', borderLeftWidth: '3px' },
  '.cm-diagnostic-error': { borderLeftColor: '#dc2626' },
  '.cm-diagnostic-warning': { borderLeftColor: '#d97706' },
  '.cm-tooltip:has(> .cm-fx-signature)': { borderRadius: '8px' },
})

export function formulaExtensions(o: FormulaOptions): Extension[] {
  return [
    history(),
    EditorView.lineWrapping,
    theme,
    tooltips({ parent: document.body }),
    chipPlugin(o),
    signatureHint(o.template),
    autocompletion({
      override: [formulaCompletions(() => o.env(), o.template)],
      icons: true,
      maxRenderedOptions: 300,
      // The type of value on the right of each row, as a coloured badge.
      addToOptions: [{
        position: 90,
        render: c => {
          const t = (c as FormulaCompletion).valueType
          if (!t || t === 'any') return null
          const badge = document.createElement('span')
          badge.className = `cm-fx-type cm-fx-type-${t}`
          badge.textContent = t
          return badge
        },
      }],
      // A template's plain text gets no suggestions; inside a formula they come as you type.
      activateOnTyping: true,
    }),
    linter(view => problemsIn(view.state.doc.toString(), o.template, o.env().scope, view.hasFocus ? view.state.selection.main.head : undefined)
      .map(p => ({ ...p, source: 'formula' })), { delay: 250, needsRefresh: u => u.selectionSet || u.focusChanged }),
    ...(o.placeholder ? [placeholderText(o.placeholder)] : []),
    ...(o.template ? [openPlaceholder] : []),
    ...(o.multiline ? [] : [singleLine]),
    Prec.high(keymap.of([
      { key: 'Mod-e', run: () => { o.onPicker(); return true }, preventDefault: true },
      ...(o.multiline ? [] : [{ key: 'Enter', run: () => true }]),
    ])),
    keymap.of([...defaultKeymap, ...historyKeymap]),
  ]
}
