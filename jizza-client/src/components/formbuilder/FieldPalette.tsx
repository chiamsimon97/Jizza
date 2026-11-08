import { useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, GripVertical, Search, Workflow as WorkflowIcon } from 'lucide-react'
import { NODE_META } from '@/components/workflow/canvasModel'
import { DRAG_FIELD, DRAG_STEP, FIELD_SPECS, type FieldSpec, type FieldType } from './formModel'
import { fillable, inputsOfStep, stepLabel, stepsOf, type GraphStep } from './stepFields'
import type { WorkflowDto } from '@/types'

export type PaletteTab = 'basic' | 'advanced' | 'workflow'

interface Props {
  tab: PaletteTab
  onTab: (tab: PaletteTab) => void
  workflows: WorkflowDto[] | undefined
  /** The workflow to open first, such as the one the form was started from. */
  focusWorkflowId?: string
  onAddField: (type: FieldType) => void
  onAddStep: (workflow: WorkflowDto, step: GraphStep) => void
}

const TABS: Array<{ id: PaletteTab; label: string }> = [
  { id: 'basic', label: 'Basic' }, { id: 'advanced', label: 'Advanced' }, { id: 'workflow', label: 'Workflow' },
]

/**
 * The builder's left panel: Basic and Advanced fields to drag onto the form, and the person's
 * workflows with their steps. Dropping a step makes a field for each of its settings that takes
 * data. A click adds the item at the end, for keyboards.
 */
export function FieldPalette({ tab, onTab, workflows, focusWorkflowId, onAddField, onAddStep }: Props) {
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  return (
    <aside aria-label="Fields" className="flex h-full min-h-0 flex-col border-r border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-950">
      <div className="p-3 pb-2">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-gray-400" />
          <input value={query} onChange={e => setQuery(e.target.value)} aria-label="Search fields or workflow steps"
            placeholder={tab === 'workflow' ? 'Search workflows or steps' : 'Search fields'}
            className="w-full rounded-lg border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900" />
        </div>
      </div>
      <div role="tablist" aria-label="Field groups" className="grid grid-cols-3 border-b border-gray-200 dark:border-gray-800">
        {TABS.map(t => (
          <button key={t.id} role="tab" type="button" aria-selected={tab === t.id} onClick={() => onTab(t.id)}
            className={`relative py-2.5 text-sm font-medium transition-colors ${tab === t.id ? 'text-brand-600 dark:text-brand-400' : 'text-gray-700 hover:text-gray-900 dark:text-gray-300 dark:hover:text-white'}`}>
            {t.label}
            {tab === t.id && <span className="absolute inset-x-4 bottom-0 h-0.5 rounded-full bg-brand-500" />}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="min-h-0 flex-1 overflow-y-auto bg-gray-50/60 pb-6 dark:bg-gray-900/40">
        {tab === 'basic' && <Tiles title="Basic Fields" specs={FIELD_SPECS.filter(s => s.group === 'basic')} query={q} onAdd={onAddField} />}
        {tab === 'advanced' && <>
          <Tiles title="Advanced Fields" specs={FIELD_SPECS.filter(s => s.group === 'advanced')} query={q} onAdd={onAddField} />
          <Tiles title="Layout" specs={FIELD_SPECS.filter(s => s.group === 'layout')} query={q} onAdd={onAddField} />
        </>}
        {tab === 'workflow' && <WorkflowSteps workflows={workflows} query={q} focusWorkflowId={focusWorkflowId} onAddStep={onAddStep} />}
      </div>
    </aside>
  )
}

function Tiles({ title, specs, query, onAdd }: { title: string; specs: FieldSpec[]; query: string; onAdd: (t: FieldType) => void }) {
  const [open, setOpen] = useState(true)
  const shown = specs.filter(s => !query || s.label.toLowerCase().includes(query))
  if (!shown.length) return null
  return (
    <section>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}
        className="flex w-full items-center justify-between bg-gray-100/80 px-4 py-2.5 text-left text-sm font-semibold text-gray-700 dark:bg-gray-900 dark:text-gray-200">
        {title}<span className="text-gray-400">{open ? '—' : '+'}</span>
      </button>
      {open && (
        <div className="grid grid-cols-2 gap-2.5 p-3">
          {shown.map(s => (
            <button key={s.type} type="button" draggable title={`Drag onto the form, or click to add ${s.label}`}
              onDragStart={e => { e.dataTransfer.setData(DRAG_FIELD, s.type); e.dataTransfer.effectAllowed = 'copy' }}
              onClick={() => onAdd(s.type)}
              className="group flex h-[86px] flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-gray-300 bg-white text-gray-600 transition-colors hover:border-brand-400 hover:bg-brand-50/60 hover:text-brand-600 active:cursor-grabbing dark:border-gray-700 dark:bg-gray-950 dark:text-gray-300 dark:hover:bg-brand-500/10">
              <s.icon className="h-6 w-6 stroke-[1.4]" />
              <span className="text-xs text-gray-800 group-hover:text-brand-700 dark:text-gray-200">{s.label}</span>
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

function WorkflowSteps({ workflows, query, focusWorkflowId, onAddStep }: {
  workflows: WorkflowDto[] | undefined; query: string; focusWorkflowId?: string; onAddStep: (w: WorkflowDto, s: GraphStep) => void
}) {
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const sorted = useMemo(() => [...(workflows ?? [])].sort((a, b) =>
    Number(b.id === focusWorkflowId) - Number(a.id === focusWorkflowId) || Number(!!b.enabled) - Number(!!a.enabled) || a.name.localeCompare(b.name)), [workflows, focusWorkflowId])
  if (!workflows) return <p className="p-4 text-sm text-gray-500">Loading your workflows…</p>
  if (!workflows.length) return <p className="p-4 text-sm text-gray-500">You have no workflows yet. Make one under Workflows, then build a form from its steps here.</p>
  const groups = sorted.map(w => {
    const steps = stepsOf(w.graph)
    const nameHit = !query || w.name.toLowerCase().includes(query)
    return { w, steps: nameHit ? steps : steps.filter(s => stepLabel(s).toLowerCase().includes(query)) }
  }).filter(g => g.steps.length || (!query))
  return (
    <div className="space-y-3 p-3">
      <p className="px-1 text-xs text-gray-500">Drag a step onto the form. Jizza makes a field for each thing the step needs, for you to accept or remove.</p>
      {groups.map(({ w, steps }) => {
        const isOpen = open[w.id] ?? (w.id === focusWorkflowId || !!query || groups.length === 1)
        return (
          <section key={w.id} className="rounded-xl border border-dashed border-gray-300 bg-gray-100/70 p-2 dark:border-gray-700 dark:bg-gray-900">
            <button type="button" aria-expanded={isOpen} onClick={() => setOpen({ ...open, [w.id]: !isOpen })}
              className="flex w-full items-center gap-3 rounded-lg px-1 py-1 text-left">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-white shadow-sm dark:bg-gray-800"><WorkflowIcon className="h-4 w-4 text-gray-700 dark:text-gray-200" /></span>
              <span className="h-6 w-px bg-gray-300 dark:bg-gray-700" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-gray-900 dark:text-gray-100">{w.name}</span>
                <span className={`text-[11px] ${w.enabled ? 'text-emerald-600' : 'text-gray-500'}`}>{w.enabled ? 'On' : 'Off'}</span>
              </span>
              {isOpen ? <ChevronUp className="h-4 w-4 text-gray-500" /> : <ChevronDown className="h-4 w-4 text-gray-500" />}
            </button>
            {isOpen && (
              <ul className="mt-2 space-y-1.5">
                {steps.map(s => {
                  const can = fillable(s)
                  const count = can ? inputsOfStep(s).length : 0
                  return (
                    <li key={s.id}>
                      <button type="button" draggable={can} disabled={!can}
                        title={can ? 'Drag onto the form, or click to add its fields' : 'This step has nothing a form can fill in'}
                        onDragStart={e => { e.dataTransfer.setData(DRAG_STEP, JSON.stringify({ workflowId: w.id, nodeId: s.id })); e.dataTransfer.effectAllowed = 'copy' }}
                        onClick={() => onAddStep(w, s)}
                        className="flex w-full items-center gap-2 rounded-lg bg-white px-2 py-2.5 text-left shadow-sm transition-colors hover:ring-1 hover:ring-brand-300 disabled:cursor-not-allowed disabled:opacity-55 disabled:hover:ring-0 dark:bg-gray-950">
                        <GripVertical className="h-4 w-4 shrink-0 text-gray-400" />
                        <span className="h-5 w-px bg-gray-200 dark:bg-gray-800" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm text-gray-900 dark:text-gray-100">{stepLabel(s)}</span>
                          <span className="block truncate text-[11px] text-gray-500">{NODE_META[s.type]?.label ?? s.type}{can ? ` · ${count} field${count === 1 ? '' : 's'}` : ' · nothing to fill'}</span>
                        </span>
                      </button>
                    </li>
                  )
                })}
                {!steps.length && <li className="px-2 py-1 text-xs text-gray-500">No steps match.</li>}
              </ul>
            )}
          </section>
        )
      })}
    </div>
  )
}
