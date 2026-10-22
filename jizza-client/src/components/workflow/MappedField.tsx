import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Braces, ChevronRight, Search, Sigma, Trash2 } from 'lucide-react'
import { fieldClass } from '@/components/ui/SlideOver'
import { NODE_META, type CanvasGraph } from './canvasModel'
import { NODE_ICON } from './canvasParts'
import { FUNCTIONS, applyFunction, chipLabel, parse, write, type DataSource, type MappingMode, type Segment } from './dataMapping'

/** What every mapped field needs to show and insert chips. */
export interface MappingContext { graph: CanvasGraph; sources: DataSource[] }

const CHIP = 'mx-0.5 inline-flex max-w-full cursor-pointer select-none items-center gap-1 whitespace-nowrap rounded-md border px-1.5 py-px align-baseline font-sans text-[11px] font-medium leading-5'
const CHIP_TONE = 'border-brand-200 bg-brand-50 text-brand-700 hover:border-brand-400 dark:border-brand-700/60 dark:bg-brand-500/10 dark:text-brand-200'
const FORMULA_TONE = 'border-violet-200 bg-violet-50 text-violet-700 hover:border-violet-400 dark:border-violet-700/60 dark:bg-violet-500/10 dark:text-violet-200'

/**
 * A field where values from earlier steps show as chips, like Zoho Flow's formula editor. Press
 * the {} button, or type {{ in a text field, to pick a value; click a chip to change it or put it
 * through a function. The value handed back is the plain text the engine reads.
 */
export function MappedField({ value, onChange, mode, context, multiline, rows = 3, placeholder, invalid, mono, ...aria }: {
  value: string; onChange: (v: string) => void; mode: MappingMode; context: MappingContext
  multiline?: boolean; rows?: number; placeholder?: string; invalid?: boolean; mono?: boolean
  'aria-label'?: string; id?: string
}) {
  const box = useRef<HTMLDivElement>(null)
  const shown = useRef<string | null>(null)
  const caret = useRef<Range | null>(null)
  const [picker, setPicker] = useState(false)
  const [editing, setEditing] = useState<{ chip: HTMLElement; expr: string } | null>(null)
  const disabled = useDisabled(box)

  // The box is edited by the browser; it is only redrawn when the value changes from outside.
  useLayoutEffect(() => {
    if (!box.current || value === shown.current) return
    draw(box.current, parse(value, mode), context.graph)
    shown.current = value
  }, [value, mode, context.graph])

  const emit = () => {
    if (!box.current) return
    const next = write(read(box.current), mode)
    shown.current = next
    if (next !== value) onChange(next)
  }

  const remember = () => {
    const sel = window.getSelection()
    if (sel && sel.rangeCount && box.current?.contains(sel.getRangeAt(0).startContainer)) caret.current = sel.getRangeAt(0).cloneRange()
  }

  /** Puts text or a chip where the caret was, or at the end. */
  const insert = (node: Node) => {
    const el = box.current
    if (!el) return
    const range = caret.current && el.contains(caret.current.startContainer) ? caret.current : endOf(el)
    range.deleteContents()
    range.insertNode(node)
    range.setStartAfter(node)
    range.collapse(true)
    caret.current = range
    const sel = window.getSelection()
    el.focus()
    sel?.removeAllRanges()
    sel?.addRange(range)
    emit()
  }

  const pick = (expr: string) => {
    setPicker(false)
    insert(chipFor(expr, context.graph))
  }

  return (
    <div className="relative min-w-0">
      <div className="flex items-start gap-1">
        <div ref={box} role="textbox" aria-multiline={multiline || undefined} {...aria}
          contentEditable={!disabled} suppressContentEditableWarning
          data-placeholder={placeholder}
          onInput={() => {
            remember()
            if (mode === 'template' && openedBraces()) { setPicker(true); emit(); return }
            emit()
          }}
          onKeyUp={remember} onMouseUp={remember} onBlur={remember}
          onKeyDown={e => {
            if (e.key === 'Escape') { setPicker(false); return }
            if (e.key !== 'Enter') return
            e.preventDefault()
            if (multiline) { remember(); insert(document.createTextNode('\n')) }
          }}
          onPaste={e => {
            e.preventDefault()
            remember()
            const text = e.clipboardData.getData('text/plain')
            insert(document.createTextNode(multiline ? text : text.replace(/\s*\n\s*/g, ' ')))
          }}
          onClick={e => {
            const chip = (e.target as HTMLElement).closest<HTMLElement>('[data-expr]')
            if (chip && !disabled) setEditing({ chip, expr: chip.dataset.expr ?? '' })
          }}
          style={multiline ? { minHeight: `${rows * 1.25 + 1}rem` } : undefined}
          className={`${fieldClass.replace('w-full', '')} min-w-0 flex-1 whitespace-pre-wrap break-words ${mono ? 'font-mono text-xs' : ''} ${multiline ? '' : 'min-h-[2.375rem]'}
            empty:before:pointer-events-none empty:before:text-gray-400 empty:before:content-[attr(data-placeholder)]
            ${invalid ? '!border-red-400' : ''} ${disabled ? 'cursor-not-allowed opacity-60' : ''}`} />
        {!disabled && (
          <button type="button" onMouseDown={e => { e.preventDefault(); e.stopPropagation() }} onClick={() => { remember(); setPicker(v => !v) }}
            aria-label="Insert data from earlier steps" title="Insert data from earlier steps"
            className="grid h-[2.375rem] w-8 shrink-0 place-items-center rounded-lg border border-gray-300 text-gray-500 hover:border-brand-400 hover:text-brand-600 dark:border-gray-700">
            <Braces className="h-4 w-4" />
          </button>
        )}
      </div>
      {picker && <DataPicker sources={context.sources} onPick={pick} onClose={() => setPicker(false)} />}
      {editing && (
        <ChipEditor expr={editing.expr} sources={context.sources}
          onClose={() => setEditing(null)}
          onRemove={() => { editing.chip.remove(); setEditing(null); emit() }}
          onSave={expr => {
            const next = chipFor(expr, context.graph)
            editing.chip.replaceWith(next)
            setEditing(null)
            emit()
          }} />
      )}
    </div>
  )
}

/** Whether {{ was just typed; if so it is taken out, since the picker inserts the chip instead. */
function openedBraces(): boolean {
  const sel = window.getSelection()
  const node = sel?.rangeCount ? sel.getRangeAt(0).startContainer : null
  const offset = sel?.rangeCount ? sel.getRangeAt(0).startOffset : 0
  if (!node || node.nodeType !== Node.TEXT_NODE || offset < 2) return false
  const text = node as Text
  if (text.data.slice(offset - 2, offset) !== '{{') return false
  // A placeholder typed out in full, {{ … }}, stays text until the field is opened again.
  if (text.data.slice(offset).includes('}}')) return false
  text.deleteData(offset - 2, 2)
  const range = document.createRange()
  range.setStart(text, offset - 2)
  range.collapse(true)
  sel!.removeAllRanges()
  sel!.addRange(range)
  return true
}

/** Whether the field sits in a disabled fieldset, which a contentEditable box does not notice by itself. */
function useDisabled(ref: React.RefObject<HTMLElement>): boolean {
  const [disabled, setDisabled] = useState(false)
  useEffect(() => { setDisabled(!!ref.current?.closest('fieldset:disabled')) }, [ref])
  return disabled
}

function endOf(el: HTMLElement): Range {
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  return range
}

function draw(el: HTMLElement, segments: Segment[], graph: CanvasGraph) {
  el.replaceChildren(...segments.map(s => (s.kind === 'text' ? document.createTextNode(s.text) : chipFor(s.expr, graph))))
}

/** The box's content as text and chips. Line breaks the browser adds as elements count as new lines. */
function read(el: Node): Segment[] {
  const out: Segment[] = []
  const text = (t: string) => {
    const last = out[out.length - 1]
    if (last?.kind === 'text') last.text += t
    else out.push({ kind: 'text', text: t })
  }
  const walk = (node: Node) => {
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) { text((child as Text).data.replace(/​/g, '')); continue }
      if (!(child instanceof HTMLElement)) continue
      if (child.dataset.expr) { out.push({ kind: 'ref', expr: child.dataset.expr }); continue }
      if (child.tagName === 'BR') { text('\n'); continue }
      if ((child.tagName === 'DIV' || child.tagName === 'P') && out.length) text('\n')
      walk(child)
    }
  }
  walk(el)
  return out
}

function chipFor(expr: string, graph: CanvasGraph): HTMLElement {
  const { source, field, formula } = chipLabel(expr, graph)
  const chip = document.createElement('span')
  chip.contentEditable = 'false'
  chip.dataset.expr = expr
  chip.title = expr
  chip.className = `${CHIP} ${formula ? FORMULA_TONE : CHIP_TONE}`
  if (source) {
    const from = document.createElement('span')
    from.className = 'truncate opacity-70'
    from.textContent = source
    const sep = document.createElement('span')
    sep.className = 'opacity-50'
    sep.textContent = '›'
    chip.append(from, sep)
  } else {
    const fx = document.createElement('span')
    fx.className = 'italic opacity-70'
    fx.textContent = 'ƒ'
    chip.append(fx)
  }
  const name = document.createElement('span')
  name.className = 'truncate'
  name.textContent = field
  chip.append(name)
  return chip
}

/** Lists what earlier steps give, grouped by step, to insert as a chip. */
function DataPicker({ sources, onPick, onClose }: { sources: DataSource[]; onPick: (expr: string) => void; onClose: () => void }) {
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

/** Changes what a chip points at, or puts it through a function. */
function ChipEditor({ expr: initial, sources, onSave, onRemove, onClose }: {
  expr: string; sources: DataSource[]; onSave: (expr: string) => void; onRemove: () => void; onClose: () => void
}) {
  const [expr, setExpr] = useState(initial)
  const [picking, setPicking] = useState(false)
  const ref = useOutsideClose(onClose)
  return (
    <div ref={ref} role="dialog" aria-label="Edit mapped value" onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }}
      className="absolute left-0 right-0 top-full z-30 mt-1 flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-2 shadow-xl dark:border-gray-700 dark:bg-gray-900">
      <label className="flex flex-col gap-1">
        <span className="text-[11px] font-medium text-gray-600 dark:text-gray-400">Formula</span>
        <textarea autoFocus rows={2} value={expr} onChange={e => setExpr(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && expr.trim()) { e.preventDefault(); onSave(expr.trim()) } }}
          className={`${fieldClass} font-mono text-xs`} />
      </label>
      <div className="flex flex-wrap gap-1">
        {FUNCTIONS.map(f => (
          <button key={f.name} type="button" title={`${f.template.replace(/\bx\b/, 'value')} · ${f.desc}`}
            onClick={() => setExpr(applyFunction(f.template, expr.trim() || 'x'))}
            className="rounded-md border border-gray-200 px-1.5 py-0.5 text-[11px] text-gray-700 hover:border-violet-400 hover:text-violet-700 dark:border-gray-700 dark:text-gray-300">{f.name}</button>
        ))}
      </div>
      <button type="button" onClick={() => setPicking(v => !v)} className="self-start text-[11px] text-brand-600 hover:underline dark:text-brand-300">
        {picking ? 'Hide earlier steps' : 'Point at a different value…'}
      </button>
      {picking && (
        <div className="relative h-72">
          <DataPicker sources={sources} onPick={e => { setExpr(e); setPicking(false) }} onClose={() => setPicking(false)} />
        </div>
      )}
      <div className="flex items-center justify-between">
        <button type="button" onClick={onRemove} className="inline-flex items-center gap-1 text-[11px] text-red-600 hover:underline"><Trash2 className="h-3 w-3" /> Remove</button>
        <div className="flex gap-1.5">
          <button type="button" onClick={onClose} className="rounded-md px-2 py-1 text-xs text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800">Cancel</button>
          <button type="button" disabled={!expr.trim()} onClick={() => onSave(expr.trim())}
            className="rounded-md bg-brand-600 px-2 py-1 text-xs font-medium text-white hover:bg-brand-700 disabled:opacity-50">Apply</button>
        </div>
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
