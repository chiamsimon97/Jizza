import { useEffect, useMemo, useRef, useState } from 'react'
import { Braces, ChevronRight, Search, Sigma } from 'lucide-react'
import { startCompletion } from '@codemirror/autocomplete'
import { Compartment, EditorState, Transaction } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { fieldClass } from '@/components/ui/SlideOver'
import { NODE_META, type CanvasGraph } from './canvasModel'
import { NODE_ICON } from './canvasParts'
import { scopeFor, type DataSource, type MappingMode } from './dataMapping'
import { formulaExtensions, refreshFormula, type Chip, type FormulaEnv } from './formula/editor'

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
  const [picker, setPicker] = useState<{ replace?: Chip } | null>(null)
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

  return (
    <div className="relative min-w-0">
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
      {!disabled && <FormulaHint template={template} onSuggest={() => { const v = view.current; if (v) { v.focus(); startCompletion(v) } }} />}
      {picker && <DataPicker sources={context.sources} onPick={pick} onClose={() => setPicker(null)}
        title={picker.replace ? 'Point this chip at other data' : undefined} />}
    </div>
  )
}

/** A line under the field on how to write a formula; it shows only while the field has focus. */
function FormulaHint({ template, onSuggest }: { template: boolean; onSuggest: () => void }) {
  return (
    <p className="mt-1 hidden text-[11px] leading-4 text-gray-400 [.group:focus-within+&]:block">
      {template ? 'Type {{ for a formula, ' : 'Chain functions with a dot, like '}
      {!template && <code className="text-gray-500">name.trim().upper()</code>}{!template && '. '}
      <button type="button" onMouseDown={e => e.preventDefault()} onClick={onSuggest} className="text-brand-600 hover:underline dark:text-brand-300">Ctrl+Space</button> suggests data and functions.
    </p>
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
