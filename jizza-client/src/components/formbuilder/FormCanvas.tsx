import { useState } from 'react'
import { Check, Copy, GripVertical, MousePointerClick, Sparkles, Trash2, Workflow as WorkflowIcon, X } from 'lucide-react'
import { FieldBlock } from './FieldInput'
import { labelOf } from './stepFields'
import { DRAG_FIELD, DRAG_MOVE, DRAG_STEP, SPEC_BY_TYPE, type FieldType, type FormField } from './formModel'

interface Props {
  fields: FormField[]
  selected: string | null
  problems: Map<string, string>
  onSelect: (id: string | null) => void
  onInsertField: (type: FieldType, index: number) => void
  onInsertStep: (workflowId: string, nodeId: string, index: number) => void
  onMove: (id: string, index: number) => void
  onMoveWithin: (layoutId: string, id: string, index: number) => void
  onRemove: (id: string) => void
  onDuplicate: (id: string) => void
  onAccept: (id: string) => void
  onAcceptAll: (layoutId: string) => void
}

type Drop = { layout?: string; index: number } | null

/**
 * The form as it is being built. Fields from the palette drop between fields; a workflow step
 * drops as a step layout whose fields wait to be accepted. Fields are moved by their handle;
 * a step layout's fields move within it.
 */
export function FormCanvas(p: Props) {
  const [drop, setDrop] = useState<Drop>(null)
  const kinds = (e: React.DragEvent) => Array.from(e.dataTransfer.types)

  const overItem = (e: React.DragEvent, index: number, layout?: string) => {
    const types = kinds(e)
    if (!types.includes(DRAG_FIELD) && !types.includes(DRAG_STEP) && !types.includes(DRAG_MOVE)) return
    if (layout && !types.includes(DRAG_MOVE)) return // new things land on the top level
    e.preventDefault()
    e.stopPropagation()
    const r = e.currentTarget.getBoundingClientRect()
    const at = e.clientY > r.top + r.height / 2 ? index + 1 : index
    if (drop?.index !== at || drop?.layout !== layout) setDrop({ layout, index: at })
  }

  const finish = (e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    const target = drop ?? { index: p.fields.length }
    setDrop(null)
    const type = e.dataTransfer.getData(DRAG_FIELD)
    const step = e.dataTransfer.getData(DRAG_STEP)
    const move = e.dataTransfer.getData(DRAG_MOVE)
    if (move) {
      const { id, layout } = JSON.parse(move) as { id: string; layout?: string }
      if (layout && layout === target.layout) p.onMoveWithin(layout, id, target.index)
      else if (!layout && !target.layout) p.onMove(id, target.index)
      return
    }
    if (target.layout) return
    if (type) p.onInsertField(type as FieldType, target.index)
    else if (step) {
      const { workflowId, nodeId } = JSON.parse(step) as { workflowId: string; nodeId: string }
      p.onInsertStep(workflowId, nodeId, target.index)
    }
  }

  const line = (layout: string | undefined, index: number) =>
    drop && drop.layout === layout && drop.index === index
      ? <div aria-hidden className="my-1 h-1 rounded-full bg-brand-500/70" /> : null

  return (
    <div className="min-h-[60vh] rounded-xl"
      onDragOver={e => {
        const types = kinds(e)
        if (types.includes(DRAG_FIELD) || types.includes(DRAG_STEP) || types.includes(DRAG_MOVE)) {
          e.preventDefault()
          if (!drop) setDrop({ index: p.fields.length })
        }
      }}
      onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setDrop(null) }}
      onDrop={finish}
      onClick={() => p.onSelect(null)}>
      {p.fields.length === 0 && (
        <div className={`grid min-h-[40vh] place-items-center rounded-xl border-2 border-dashed p-8 text-center ${drop ? 'border-brand-400 bg-brand-50/50 dark:bg-brand-500/10' : 'border-gray-300 dark:border-gray-700'}`}>
          <div>
            <MousePointerClick className="mx-auto h-8 w-8 text-gray-400" />
            <p className="mt-2 text-sm font-medium text-gray-700 dark:text-gray-200">Drag fields here</p>
            <p className="mt-1 text-xs text-gray-500">Pick from Basic or Advanced, or drop a step from the Workflow tab to make its fields for you.</p>
          </div>
        </div>
      )}
      {p.fields.map((f, i) => (
        <div key={f.id}>
          {line(undefined, i)}
          {f.type === 'step_layout'
            ? <StepLayout field={f} {...p} index={i} drop={drop} line={line} onOver={overItem} />
            : <FieldCard field={f} index={i} {...p} onOver={overItem} />}
        </div>
      ))}
      {line(undefined, p.fields.length)}
    </div>
  )
}

function FieldCard({ field, index, layout, selected, problems, onSelect, onRemove, onDuplicate, onAccept, onOver }: Props & {
  field: FormField; index: number; layout?: string; onOver: (e: React.DragEvent, index: number, layout?: string) => void
}) {
  const active = selected === field.id
  const problem = problems.get(field.id)
  const pending = field.pending
  return (
    <div role="button" tabIndex={0} aria-label={`${field.label}, ${SPEC_BY_TYPE[field.type]?.label ?? field.type}${pending ? ', suggested' : ''}`}
      aria-pressed={active}
      onClick={e => { e.stopPropagation(); onSelect(field.id) }}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(field.id) } if (e.key === 'Delete') onRemove(field.id) }}
      onDragOver={e => onOver(e, index, layout)}
      className={`group relative mb-2 rounded-xl border bg-white p-4 pl-8 transition-shadow dark:bg-gray-950 ${
        pending ? 'border-dashed border-amber-400 bg-amber-50/40 dark:border-amber-500/60 dark:bg-amber-500/5'
          : active ? 'border-brand-500 ring-4 ring-brand-500/15' : problem ? 'border-red-300' : 'border-gray-200 hover:border-gray-300 dark:border-gray-800'}`}>
      <span draggable aria-label="Drag to move" title="Drag to move"
        onDragStart={e => { e.stopPropagation(); e.dataTransfer.setData(DRAG_MOVE, JSON.stringify({ id: field.id, layout })); e.dataTransfer.effectAllowed = 'move' }}
        className="absolute left-1.5 top-4 cursor-grab rounded p-0.5 text-gray-300 hover:text-gray-500 active:cursor-grabbing">
        <GripVertical className="h-4 w-4" />
      </span>
      <div className="pointer-events-none">
        <FieldBlock field={field} value={undefined} onChange={() => {}} preview />
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-gray-500">
        {field.name && <code className="rounded bg-gray-100 px-1.5 py-0.5 font-mono dark:bg-gray-900">{field.name}</code>}
        {field.binding && <span className="inline-flex items-center gap-1"><WorkflowIcon className="h-3 w-3" />fills {field.binding.mode === 'input' ? `the start input ${field.name}` : `“${labelOf(String(field.binding.path[field.binding.path.length - 1] === 'value' && field.binding.path.length > 2 ? field.label : field.binding.path[field.binding.path.length - 1]))}” of the step`}</span>}
        {problem && <span className="text-red-600">{problem}</span>}
      </div>
      <div className={`absolute right-2 top-2 flex gap-1 ${pending ? '' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100'} ${active ? 'opacity-100' : ''}`}>
        {pending && (
          <button type="button" aria-label={`Accept ${field.label}`} title="Accept"
            onClick={e => { e.stopPropagation(); onAccept(field.id) }}
            className="inline-flex items-center gap-1 rounded-lg bg-emerald-600 px-2 py-1 text-xs font-medium text-white hover:bg-emerald-700">
            <Check className="h-3.5 w-3.5" />Accept
          </button>
        )}
        {!pending && !layout && (
          <button type="button" aria-label={`Duplicate ${field.label}`} title="Duplicate" onClick={e => { e.stopPropagation(); onDuplicate(field.id) }}
            className="rounded-lg bg-white p-1.5 text-gray-500 shadow-sm ring-1 ring-gray-200 hover:text-gray-900 dark:bg-gray-900 dark:ring-gray-700"><Copy className="h-3.5 w-3.5" /></button>
        )}
        <button type="button" aria-label={`Remove ${field.label}`} title="Remove" onClick={e => { e.stopPropagation(); onRemove(field.id) }}
          className={pending
            ? 'inline-flex items-center gap-1 rounded-lg bg-white px-2 py-1 text-xs font-medium text-gray-700 ring-1 ring-gray-300 hover:text-red-600 dark:bg-gray-900 dark:text-gray-200 dark:ring-gray-700'
            : 'rounded-lg bg-white p-1.5 text-gray-500 shadow-sm ring-1 ring-gray-200 hover:text-red-600 dark:bg-gray-900 dark:ring-gray-700'}>
          {pending ? <><X className="h-3.5 w-3.5" />Remove</> : <Trash2 className="h-3.5 w-3.5" />}
        </button>
      </div>
    </div>
  )
}

function StepLayout(props: Props & {
  field: FormField; index: number; drop: Drop; line: (layout: string | undefined, index: number) => React.ReactNode
  onOver: (e: React.DragEvent, index: number, layout?: string) => void
}) {
  const { field, index, onAcceptAll, onRemove, onOver, line } = props
  const children = field.children ?? []
  const pending = children.filter(c => c.pending).length
  return (
    <section aria-label={`Fields from ${field.stepLabel}`} onDragOver={e => onOver(e, index)}
      className={`relative mb-3 mt-5 rounded-2xl border-2 p-3 pt-6 ${pending ? 'border-amber-300 bg-amber-50/30 dark:border-amber-500/50 dark:bg-amber-500/5' : 'border-gray-200 bg-gray-50/50 dark:border-gray-800 dark:bg-gray-900/40'}`}
      onClick={e => e.stopPropagation()}>
      <div className="absolute -top-3.5 left-4 flex max-w-[calc(100%-19rem)] items-center gap-1.5 rounded-full border border-gray-200 bg-white px-3 py-1 text-xs text-gray-700 shadow-sm dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
        <WorkflowIcon className="h-3.5 w-3.5 shrink-0 text-brand-500" />
        <span className="truncate"><span className="text-gray-500">{field.workflowName} › </span>{field.stepLabel}</span>
      </div>
      <div className="absolute -top-4 right-4 flex gap-1.5">
        {pending > 0 && (
          <button type="button" onClick={() => onAcceptAll(field.id)}
            className="inline-flex items-center gap-1.5 rounded-full bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white shadow-lg shadow-emerald-600/25 hover:bg-emerald-700">
            <Sparkles className="h-3.5 w-3.5" />Accept all {pending}
          </button>
        )}
        <button type="button" onClick={() => onRemove(field.id)} aria-label={`Remove the fields from ${field.stepLabel}`}
          className="inline-flex items-center gap-1 rounded-full border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-600 shadow-sm hover:text-red-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300">
          <Trash2 className="h-3.5 w-3.5" />Remove layout
        </button>
      </div>
      {pending > 0 && <p className="mb-2 px-1 text-xs text-amber-700 dark:text-amber-300">Jizza made these from the step. Accept the ones to keep; suggested fields are not on the published form until you do.</p>}
      {children.map((c, i) => (
        <div key={c.id}>
          {line(field.id, i)}
          <FieldCard {...props} field={c} index={i} layout={field.id} />
        </div>
      ))}
      {line(field.id, children.length)}
    </section>
  )
}
