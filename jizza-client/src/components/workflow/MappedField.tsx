import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Braces, CheckCircle2, ChevronRight, Search, Sigma, X, XCircle } from 'lucide-react'
import { startCompletion } from '@codemirror/autocomplete'
import { Compartment, EditorState, Transaction } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { fieldClass } from '@/components/ui/SlideOver'
import { NODE_META, type CanvasGraph } from './canvasModel'
import { NODE_ICON } from './canvasParts'
import { scopeFor, type DataSource, type MappingMode } from './dataMapping'
import { formulaExtensions, problemsIn, refreshFormula, type Chip, type FormulaEnv } from './formula/editor'
import type { Problem } from './formula/analyze'
import { FORMULA_FUNCTIONS, signature, type FormulaFunction, type FunctionCategory } from './formula/functions'

/** What every mapped field needs to show and insert chips. */
export interface MappingContext { graph: CanvasGraph; sources: DataSource[] }

/** The field's frame; it is a wrapper, so it takes focus styles from the editor inside it. */
const FRAME = fieldClass.replace(/\bfocus:/g, 'focus-within:')

/** Marks a change that came from outside (the value prop), so it isn't handed back as an edit. */
const external = Transaction.userEvent.of('external')

/**
 * A field where values from earlier steps show as chips, like Zoho Flow's formula editor, built on
 * CodeMirror so a formula reads and edits like a line of script: colours, suggestions grouped by
 * earlier step and by function with docs, the function's values while you fill them in, and
 * problems underlined as you type.
 *
 * In an expression field the whole value is a formula; click a chip to point it at other data. In
 * a template field (text) formulas sit in {{ }}; type {{ to start one, click a chip to edit it.
 * The {} button or Ctrl/Cmd+E lists every earlier step's data. The value handed back is the plain
 * text the engine reads.
 */
export function MappedField({ value, onChange, mode, context, multiline, rows = 3, placeholder, invalid, mono, ...aria }: {
  value: string; onChange: (v: string) => void; mode: MappingMode; context: MappingContext
  multiline?: boolean; rows?: number; placeholder?: string; invalid?: boolean; mono?: boolean
  'aria-label'?: string; id?: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const [picker, setPicker] = useState<{ replace?: Chip; functions?: boolean } | null>(null)
  const disabled = useDisabled(host)
  const template = mode === 'template'

  const env = useRef<FormulaEnv>(null!)
  const scope = useMemo(() => scopeFor(context.sources), [context.sources])
  env.current = { graph: context.graph, sources: context.sources, scope }
  const emit = useRef(onChange)
  emit.current = onChange
  const editable = useRef(new Compartment())
  /** Whether the field has been in use; until then data from the picker goes at the end. */
  const touched = useRef(false)
  /** Where the cursor is while the field has focus, so a placeholder being typed isn't called a problem. */
  const [caret, setCaret] = useState<number | undefined>()

  useEffect(() => {
    if (!host.current) return
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          formulaExtensions({
            template, multiline: !!multiline, placeholder, env: () => env.current,
            onChipClick: chip => setPicker({ replace: chip }),
            onPicker: () => setPicker(p => (p ? null : {})),
          }),
          editable.current.of([EditorView.editable.of(true)]),
          EditorView.contentAttributes.of({
            'aria-label': aria['aria-label'] ?? '', ...(aria.id ? { id: aria.id } : {}),
            'aria-multiline': multiline ? 'true' : 'false', 'aria-keyshortcuts': 'Control+E Meta+E',
            ...(mono ? { spellcheck: 'false' } : {}),
          }),
          EditorView.updateListener.of(u => {
            if (u.focusChanged && u.view.hasFocus) touched.current = true
            if (u.selectionSet || u.focusChanged) setCaret(u.view.hasFocus ? u.state.selection.main.head : undefined)
            if (!u.docChanged || u.transactions.every(t => t.annotation(Transaction.userEvent) === 'external')) return
            emit.current(u.state.doc.toString())
          }),
        ],
      }),
    })
    view.current = v
    return () => { v.destroy(); view.current = null }
    // The editor is made once; value, data and editability flow in through the effects below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [template, multiline])

  useEffect(() => {
    const v = view.current
    if (!v || v.state.doc.toString() === value) return
    v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, annotations: external })
  }, [value])

  useEffect(() => { view.current?.dispatch({ effects: refreshFormula.of(null) }) }, [context.graph, context.sources])

  useEffect(() => {
    view.current?.dispatch({ effects: editable.current.reconfigure([EditorView.editable.of(!disabled), EditorState.readOnly.of(disabled)]) })
  }, [disabled])

  /** Puts a reference where the cursor is, or in place of the chip being repointed. */
  const pick = (expr: string) => {
    const v = view.current
    const replacing = picker?.replace
    setPicker(null)
    if (!v) return
    const sel = v.state.selection.main
    const end = v.state.doc.length
    const range = replacing ? { from: replacing.from, to: replacing.to } : touched.current ? { from: sel.from, to: sel.to } : { from: end, to: end }
    const inFormula = template && !replacing && insidePlaceholder(v.state.doc.toString(), range.from)
    const insert = template && !inFormula ? `{{ ${expr} }}` : expr
    v.dispatch({ changes: { ...range, insert }, selection: { anchor: range.from + insert.length }, userEvent: 'input.complete' })
    v.focus()
  }

  /** Puts a function at the cursor: chained on the value just before it, or as a plain call. */
  const pickFunction = (f: FormulaFunction) => {
    const v = view.current
    setPicker(null)
    if (!v) return
    const doc = v.state.doc.toString()
    const sel = touched.current ? v.state.selection.main : { from: doc.length, to: doc.length }
    const inFormula = !template || insidePlaceholder(doc, sel.from)
    const chained = inFormula && !!f.subject && /[\w)\]'"]$/.test(doc.slice(0, sel.from))
    const call = `${chained ? '.' : ''}${f.name}(`
    const values = (chained ? 0 : f.subject ? 1 : 0) + f.params.length
    const insert = inFormula ? `${call})` : `{{ ${call}) }}`
    const caret = sel.from + (inFormula ? 0 : 3) + call.length + (values ? 0 : 1)
    v.dispatch({ changes: { from: sel.from, to: sel.to, insert }, selection: { anchor: caret }, userEvent: 'input.complete' })
    v.focus()
    if (values) startCompletion(v)
  }

  // Nothing is called a problem right where the cursor is typing; it shows once the cursor moves on.
  const problems = useMemo(() => problemsIn(value, template, scope, caret)
    .filter(p => caret === undefined || p.to < caret - 1 || p.from > caret), [value, template, scope, caret])
  const jumpTo = (from: number, to: number) => {
    const v = view.current
    if (!v) return
    v.focus()
    v.dispatch({ selection: { anchor: from, head: to }, scrollIntoView: true })
  }
  const clear = () => {
    const v = view.current
    if (!v) return
    v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: '' }, userEvent: 'delete' })
    v.focus()
  }

  return (
    <div className="group/fx relative min-w-0">
      <div className="group relative">
        <div ref={host} onClick={e => { if (e.target === host.current) view.current?.focus() }}
          style={multiline ? { minHeight: `${rows * 1.5 + 1}rem` } : undefined}
          className={`jz-formula ${FRAME} min-w-0 cursor-text break-words ${mono ? 'font-mono text-xs' : ''} ${multiline ? '' : 'min-h-[2.375rem]'} ${disabled ? '' : 'pr-10'}
            ${invalid ? '!border-red-400' : ''} ${disabled ? 'cursor-not-allowed opacity-60' : ''}`} />
        {!disabled && (
          <button type="button" onMouseDown={e => { e.preventDefault(); e.stopPropagation() }} onClick={() => setPicker(p => (p ? null : {}))}
            aria-label="Insert data from earlier steps" title="Insert data from earlier steps (Ctrl+E)"
            className={`absolute right-1.5 top-[5px] grid h-7 w-7 place-items-center rounded-md text-gray-400 transition-opacity hover:bg-brand-50 hover:text-brand-600 focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500/40 group-hover:opacity-100 group-focus-within:opacity-100 [@media(hover:none)]:opacity-100 dark:hover:bg-brand-500/10 ${picker ? 'text-brand-600 opacity-100' : 'opacity-0'}`}>
            <Braces className="h-4 w-4" />
          </button>
        )}
      </div>
      {!disabled && (
        <FormulaBar template={template} empty={!value} problems={problems} open={!!picker}
          onData={() => setPicker(p => (p && !p.functions ? null : {}))}
          onFunctions={() => setPicker(p => (p?.functions ? null : { functions: true }))}
          onClear={clear} onJump={jumpTo} />
      )}
      {picker && !picker.functions && <DataPicker sources={context.sources} onPick={pick} onClose={() => setPicker(null)}
        title={picker.replace ? 'Point this chip at other data' : undefined} />}
      {picker?.functions && <FunctionPicker onPick={pickFunction} onClose={() => setPicker(null)} />}
    </div>
  )
}

/**
 * The strip under a field while it is in use, like the bar of Zoho Flow's formula editor: data and
 * functions to insert, a clear button, and whether the formula is fine (click a problem to go to it).
 */
function FormulaBar({ template, empty, problems, open, onData, onFunctions, onClear, onJump }: {
  template: boolean; empty: boolean; problems: Problem[]; open: boolean
  onData: () => void; onFunctions: () => void; onClear: () => void; onJump: (from: number, to: number) => void
}) {
  const errors = problems.filter(p => p.severity === 'error')
  const first = errors[0] ?? problems[0]
  const keep = (e: React.MouseEvent) => e.preventDefault()
  const tool = 'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium text-gray-500 hover:bg-gray-100 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
  return (
    <div className={`mt-1 items-center gap-0.5 ${open ? 'flex' : 'hidden group-focus-within/fx:flex'}`}>
      <button type="button" onMouseDown={keep} onClick={onData} className={tool} title="Data from earlier steps (Ctrl+E)">
        <Braces className="h-3 w-3" /> Data
      </button>
      <button type="button" onMouseDown={keep} onClick={onFunctions} className={tool} title="Functions to change a value">
        <span className="font-serif text-[12px] italic leading-none">ƒ</span> Functions
      </button>
      {!empty && (
        <button type="button" onMouseDown={keep} onClick={onClear} className={tool} title="Clear the field">
          <X className="h-3 w-3" /> Clear
        </button>
      )}
      <span className="ml-auto truncate pl-2 text-[11px] text-gray-400">
        {!first && !empty && <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400"><CheckCircle2 className="h-3 w-3" /> Looks good</span>}
        {!first && empty && (template ? 'Type {{ for a formula' : 'Ctrl+Space for suggestions')}
        {first && (
          <button type="button" onMouseDown={keep} onClick={() => onJump(first.from, first.to)} title={problems.map(p => p.message).join('\n')}
            className={`inline-flex max-w-full items-center gap-1 hover:underline ${errors.length ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'}`}>
            {errors.length ? <XCircle className="h-3 w-3 shrink-0" /> : <AlertTriangle className="h-3 w-3 shrink-0" />}
            <span className="truncate">{problems.length > 1 ? `${problems.length} problems: ` : ''}{first.message}</span>
          </button>
        )}
      </span>
    </div>
  )
}

const CATEGORIES: FunctionCategory[] = ['Text', 'Number', 'Date', 'List', 'Check']

/** Every function, by kind, with what it does; picking one puts it at the cursor. */
function FunctionPicker({ onPick, onClose }: { onPick: (f: FormulaFunction) => void; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<FunctionCategory | 'All'>('All')
  const [hover, setHover] = useState<FormulaFunction | null>(null)
  const ref = useOutsideClose(onClose)
  const q = query.trim().toLowerCase()
  const shown = FORMULA_FUNCTIONS.filter(f => (category === 'All' || f.category === category)
    && (!q || f.name.toLowerCase().includes(q) || f.description.toLowerCase().includes(q)))
  const focus = hover ?? shown[0]
  return (
    <div ref={ref} role="dialog" aria-label="Insert a function" onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }}
      className="absolute left-0 right-0 top-full z-30 mt-1 flex max-h-80 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-900">
      <label className="flex shrink-0 items-center gap-1.5 border-b border-gray-100 px-2 py-1.5 dark:border-gray-800">
        <Search className="h-3.5 w-3.5 text-gray-400" />
        <input autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder="Search functions"
          onKeyDown={e => { if (e.key === 'Enter' && shown[0]) { e.preventDefault(); onPick(shown[0]) } }}
          className="w-full bg-transparent text-xs text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
      </label>
      <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-gray-100 px-2 py-1.5 dark:border-gray-800">
        {(['All', ...CATEGORIES] as const).map(c => (
          <button key={c} type="button" onClick={() => setCategory(c)}
            className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ${category === c ? 'bg-brand-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-800 dark:text-gray-300'}`}>{c}</button>
        ))}
      </div>
      <div className="flex min-h-0 flex-1">
        <div className="min-w-0 flex-1 overflow-y-auto py-1">
          {shown.length === 0 && <p className="px-3 py-2 text-xs text-gray-500">Nothing matches.</p>}
          {shown.map(f => (
            <button key={f.name} type="button" onClick={() => onPick(f)} onMouseEnter={() => setHover(f)} onFocus={() => setHover(f)}
              className={`flex w-full items-center gap-2 px-2 py-1 text-left text-xs hover:bg-brand-50 dark:hover:bg-brand-500/10 ${focus === f ? 'bg-brand-50/60 dark:bg-brand-500/5' : ''}`}>
              <span className="grid h-5 w-5 shrink-0 place-items-center rounded bg-violet-500/10 font-serif text-[11px] italic text-violet-600 dark:text-violet-300">ƒ</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium text-gray-800 dark:text-gray-100">{f.name}</span>
                <span className="block truncate text-[10px] text-gray-500">{f.description}</span>
              </span>
              <span className="shrink-0 rounded-full bg-gray-100 px-1.5 text-[10px] text-gray-500 dark:bg-gray-800">{f.returns}</span>
            </button>
          ))}
        </div>
        {focus && (
          <div className="hidden w-48 shrink-0 flex-col gap-1.5 border-l border-gray-100 p-2 text-[11px] sm:flex dark:border-gray-800">
            <code className="break-all font-mono text-[10px] text-violet-600 dark:text-violet-300">{signature(focus)}</code>
            <p className="text-gray-600 dark:text-gray-300">{focus.description}</p>
            <div className="rounded bg-gray-50 p-1.5 dark:bg-gray-800">
              <p className="text-[10px] text-gray-400">Example</p>
              <code className="break-all font-mono text-[10px] text-gray-700 dark:text-gray-200">{focus.example}</code>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function insidePlaceholder(doc: string, pos: number): boolean {
  const open = doc.lastIndexOf('{{', pos - 1)
  if (open < 0) return false
  const close = doc.indexOf('}}', open)
  return close < 0 || close >= pos
}

/** Whether the field sits in a disabled fieldset, which the editor does not notice by itself. */
function useDisabled(ref: React.RefObject<HTMLElement>): boolean {
  const [disabled, setDisabled] = useState(false)
  useEffect(() => { setDisabled(!!ref.current?.closest('fieldset:disabled')) }, [ref])
  return disabled
}

/** Lists what earlier steps give, grouped by step, to insert as a chip. */
function DataPicker({ sources, onPick, onClose, title }: { sources: DataSource[]; onPick: (expr: string) => void; onClose: () => void; title?: string }) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState<string | null>(sources.length === 1 ? sources[0].id : null)
  const ref = useOutsideClose(onClose)
  const q = query.trim().toLowerCase()
  const shown = sources.map(s => ({
    ...s, fields: q && !s.label.toLowerCase().includes(q) ? s.fields.filter(f => f.label.toLowerCase().includes(q) || f.expr.toLowerCase().includes(q)) : s.fields,
  })).filter(s => s.fields.length)

  return (
    <div ref={ref} role="dialog" aria-label="Insert data" onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }}
      className="absolute left-0 right-0 top-full z-30 mt-1 flex max-h-72 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-900">
      {title && <p className="border-b border-gray-100 px-2 py-1 text-[11px] font-medium text-gray-500 dark:border-gray-800">{title}</p>}
      <label className="flex items-center gap-1.5 border-b border-gray-100 px-2 py-1.5 dark:border-gray-800">
        <Search className="h-3.5 w-3.5 text-gray-400" />
        <input autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder="Search earlier steps"
          className="w-full bg-transparent text-xs text-gray-900 outline-none placeholder:text-gray-400 dark:text-gray-100" />
      </label>
      <div className="overflow-y-auto py-1">
        {shown.length === 0 && (
          <p className="px-3 py-2 text-xs text-gray-500">{sources.length ? 'Nothing matches.' : 'Connect a step before this one to use its data here.'}</p>
        )}
        {shown.map(s => {
          const expanded = !!q || open === s.id
          const Icon = s.type === 'VARS' ? Sigma : NODE_ICON[s.type]
          return (
            <div key={s.id}>
              <button type="button" onClick={() => setOpen(expanded && !q ? null : s.id)}
                className="flex w-full items-center gap-2 px-2 py-1.5 text-left text-xs font-medium text-gray-800 hover:bg-gray-50 dark:text-gray-200 dark:hover:bg-gray-800">
                <ChevronRight className={`h-3.5 w-3.5 shrink-0 text-gray-400 transition-transform ${expanded ? 'rotate-90' : ''}`} />
                <span className={`grid h-5 w-5 shrink-0 place-items-center rounded ${s.type === 'VARS' ? 'bg-purple-600 text-white' : NODE_META[s.type].tone}`}><Icon className="h-3 w-3" /></span>
                <span className="truncate">{s.label}</span>
              </button>
              {expanded && s.fields.map(f => (
                <button key={f.expr} type="button" onClick={() => onPick(f.expr)} title={f.expr}
                  className="flex w-full items-center justify-between gap-2 py-1 pl-10 pr-2 text-left text-xs text-gray-700 hover:bg-brand-50 hover:text-brand-700 dark:text-gray-300 dark:hover:bg-brand-500/10">
                  <span className="truncate">{f.label}</span>
                  <code className="truncate text-[10px] text-gray-400">{f.expr}</code>
                </button>
              ))}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function useOutsideClose(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const down = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose() }
    // Deferred, so the click that opened it doesn't close it.
    const t = setTimeout(() => document.addEventListener('mousedown', down))
    return () => { clearTimeout(t); document.removeEventListener('mousedown', down) }
  }, [onClose])
  return ref
}
