import { useState } from 'react'
import { ArrowDown, ArrowUp, CircleOff, CircleSlash, KeyRound, ListChecks, Pencil, SlidersHorizontal, Trash2 } from 'lucide-react'
import type { EditOp, RecordedStep, Recording } from '@/api/web'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Badge } from '@/components/ui/Badge'
import { ElementPreview } from './PageView'
import { conditionDepths, emptyConditions, fieldName, isCondition, isListChoice, isPassword, liveSteps, mappableFields, moveOp, reusableOps, suggestInputName } from './recordingModel'

const TYPES = ['text', 'number', 'boolean', 'date'] as const

/**
 * Where a person checks what Chrome captured before it becomes steps: rename, reorder, delete,
 * turn a typed value into an input, and choose a saved login for each password.
 */
export function RecordingReview({ recording, busy, onEdit }: {
  recording: Recording
  busy?: boolean
  onEdit: (ops: EditOp[]) => void
}) {
  const steps = liveSteps(recording.steps)
  const [renaming, setRenaming] = useState<RecordedStep | null>(null)
  const [mapping, setMapping] = useState<RecordedStep | null>(null)
  const [dragging, setDragging] = useState<number | null>(null)

  if (steps.length === 0) return <p className="py-6 text-center text-sm text-gray-500">Nothing was captured.</p>
  const reusable = reusableOps(recording.steps)
  const choices = steps.filter(isListChoice).length
  // Edit recording: conditions and their steps are indented; steps can be turned off or made optional.
  const editing = !!recording.sourceWorkflowId
  const depth = conditionDepths(steps)
  // A condition with nothing inside is left out on save: say so before, not after.
  const empty = new Set(emptyConditions(steps))

  return (
    <>
      {choices > 0 && (
        <div className="mb-3 flex items-start gap-3 rounded-xl border border-gray-200 bg-gray-50 p-3 text-xs text-gray-600 dark:border-gray-700 dark:bg-gray-800/50 dark:text-gray-300">
          <ListChecks className="mt-0.5 h-4 w-4 shrink-0" />
          <p className="flex-1">
            {choices === 1 ? 'One step chooses' : `${choices} steps choose`} from a list on the page (cards, dates, times).
            Make what is chosen an input to run this workflow for any of them; the recorded choice stays the default.
          </p>
          {reusable.length > 0 && (
            <Button size="sm" variant="secondary" disabled={busy} onClick={() => onEdit(reusable)}>Make reusable</Button>
          )}
        </div>
      )}
      {empty.size > 0 && (
        <p role="alert" className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
          {empty.size === 1 ? 'A condition has' : `${empty.size} conditions have`} no steps inside, so saving leaves {empty.size === 1 ? 'it' : 'them'} out.
          Drag the step it is for (for example the pop-up's choice) between the condition and its end, or in the browser's side panel use the condition's Take in next step.
        </p>
      )}
      <ol className="space-y-2" aria-label="Recorded steps">
        {steps.map((s, i) => (
          <li key={s.seq} draggable onDragStart={() => setDragging(i)} onDragOver={e => e.preventDefault()}
            onDrop={() => { if (dragging !== null && dragging !== i) onEdit([moveOp(steps[dragging], i)]); setDragging(null) }}
            style={depth[i] ? { marginLeft: `${depth[i] * 1.25}rem` } : undefined}
            className={`flex items-start gap-3 rounded-xl border p-2.5 ${isCondition(s) ? 'border-dashed border-brand-300 bg-brand-50/40 dark:border-brand-700 dark:bg-brand-950/20' : 'border-gray-200 dark:border-gray-700'} ${s.step.disabled ? 'opacity-60' : ''}`}>
            {s.step.meta?.preview || s.step.meta?.pagePreview
              ? <ElementPreview deviceId={recording.deviceId} preview={s.step.meta.preview} pagePreview={s.step.meta.pagePreview} label=""
                  className="h-12 w-16 shrink-0 rounded-md border border-gray-200 object-cover dark:border-gray-700" />
              : <span className="h-12 w-16 shrink-0 rounded-md bg-gray-100 dark:bg-gray-800" />}
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{i + 1}. {s.step.label ?? s.step.action}</p>
              {empty.has(s.seq) && <p className="text-xs text-amber-700 dark:text-amber-300">Nothing inside: left out when saved</p>}
              <p className="truncate text-[11px] text-gray-500">{s.step.meta?.url ?? s.step.meta?.window}</p>
              {s.step.action === 'browser.fillForm' && Array.isArray(s.step.input?.fields) && (
                <ul className="mt-1 space-y-0.5 text-xs text-gray-600 dark:text-gray-300" aria-label="Form fields">
                  {(s.step.input.fields as { label?: string; value?: unknown }[]).map((f, n) => (
                    <li key={n}>
                      {fieldName(s, `fields.${n}.value`)}: {typeof f.value === 'string' ? f.value : ''}
                      {s.step.mappings?.[`fields.${n}.value`] && <span className="ml-1 text-brand-600 dark:text-brand-300">(input: {s.step.mappings[`fields.${n}.value`].name})</span>}
                    </li>
                  ))}
                </ul>
              )}
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                {s.step.disabled && <Badge>Off</Badge>}
                {s.step.optional && <Badge>Optional</Badge>}
                {s.step.action === 'flow.keep' && <Badge>Jizza step</Badge>}
                {isListChoice(s) && <Badge>list{s.step.meta?.list?.count ? ` of ${s.step.meta.list.count}` : ''}{s.step.meta?.list?.marked ? ', marked by you' : ''}</Badge>}
                {isListChoice(s) && s.step.meta?.list?.named && (
                  <Badge>{[s.step.meta.list.withinTitle, s.step.meta.list.title].filter(Boolean).join(' › ')}</Badge>
                )}
                {isListChoice(s) && s.step.meta?.list?.dependsOn?.relation === 'after' && (
                  <Badge>after {s.step.meta.list.dependsOn.title}</Badge>
                )}
                {Object.entries(s.step.mappings ?? {}).map(([field, m]) => <Badge key={field}>input: {m.name}</Badge>)}
                {isPassword(s) && (
                  <label className="flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
                    <KeyRound className="h-3.5 w-3.5" />{recording.kind === 'desktop' ? 'Secret on the PC' : 'Saved login'}
                    <input aria-label="Saved login" defaultValue={s.step.secretRef ?? ''} placeholder={recording.kind === 'desktop' ? 'Secret name' : 'Saved login id'}
                      onBlur={e => { const v = e.target.value.trim(); if (v !== (s.step.secretRef ?? '')) onEdit([{ op: 'secret', seq: s.seq, secretRef: v || null }]) }}
                      className="w-40 rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600" />
                  </label>
                )}
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-0.5">
              <Button size="sm" variant="ghost" aria-label="Move up" disabled={busy || i === 0} onClick={() => onEdit([moveOp(s, i - 1)])}><ArrowUp className="h-4 w-4" /></Button>
              <Button size="sm" variant="ghost" aria-label="Move down" disabled={busy || i === steps.length - 1} onClick={() => onEdit([moveOp(s, i + 1)])}><ArrowDown className="h-4 w-4" /></Button>
              {editing && !isCondition(s) && (
                <>
                  <Button size="sm" variant="ghost" aria-label={s.step.disabled ? 'Turn step on' : 'Turn step off'} title={s.step.disabled ? 'Turn on' : 'Turn off: kept, but not run'}
                    disabled={busy} onClick={() => onEdit([{ op: 'update', seq: s.seq, patch: { disabled: !s.step.disabled } }])}><CircleOff className="h-4 w-4" /></Button>
                  <Button size="sm" variant="ghost" aria-label={s.step.optional ? 'Make step required' : 'Make step optional'} title={s.step.optional ? 'Make required' : 'Optional: skipped when it is not on the page'}
                    disabled={busy} onClick={() => onEdit([{ op: 'update', seq: s.seq, patch: { optional: !s.step.optional } }])}><CircleSlash className="h-4 w-4" /></Button>
                </>
              )}
              <Button size="sm" variant="ghost" aria-label="Rename step" onClick={() => setRenaming(s)}><Pencil className="h-4 w-4" /></Button>
              {mappableFields(s).length > 0 && <Button size="sm" variant="ghost" aria-label="Make this an input" onClick={() => setMapping(s)}><SlidersHorizontal className="h-4 w-4" /></Button>}
              <Button size="sm" variant="ghost" aria-label="Delete step" disabled={busy} onClick={() => onEdit([{ op: 'delete', seq: s.seq }])}><Trash2 className="h-4 w-4" /></Button>
            </div>
          </li>
        ))}
      </ol>
      {renaming && <RenameDialog step={renaming} onClose={() => setRenaming(null)}
        onSave={label => { onEdit([{ op: 'relabel', seq: renaming.seq, label }]); setRenaming(null) }} />}
      {mapping && <InputDialog step={mapping} onClose={() => setMapping(null)}
        onSave={op => { onEdit([op]); setMapping(null) }} />}
    </>
  )
}

function RenameDialog({ step, onClose, onSave }: { step: RecordedStep; onClose: () => void; onSave: (label: string) => void }) {
  const [label, setLabel] = useState(step.step.label ?? '')
  return (
    <Dialog open title="Rename step" onClose={onClose}>
      <input aria-label="Step name" autoFocus value={label} onChange={e => setLabel(e.target.value)}
        className="w-full rounded-lg border border-gray-300 bg-transparent px-3 py-2 text-sm dark:border-gray-600" />
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" disabled={!label.trim()} onClick={() => onSave(label.trim())}>Rename</Button>
      </div>
    </Dialog>
  )
}

/** Turns one typed value of a step into a named input of the saved module, keeping the value as its default. */
function InputDialog({ step, onClose, onSave }: { step: RecordedStep; onClose: () => void; onSave: (op: EditOp) => void }) {
  const fields = mappableFields(step)
  const [field, setField] = useState(fields[0].field)
  const current = fields.find(f => f.field === field)!
  const [name, setName] = useState(suggestInputName(step, field))
  const [type, setType] = useState<(typeof TYPES)[number]>('text')
  const valid = /^[A-Za-z][A-Za-z0-9_]*$/.test(name)
  return (
    <Dialog open title="Make this an input" description="The value you typed becomes the default; each run can change it." onClose={onClose}>
      <div className="space-y-3 text-sm">
        {fields.length > 1 && (
          <label className="block">Value
            <select value={field} onChange={e => { setField(e.target.value); setName(suggestInputName(step, e.target.value)) }}
              className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-1.5 dark:border-gray-600">
              {fields.map(f => <option key={f.field} value={f.field}>{fieldName(step, f.field)}: {f.value}</option>)}
            </select>
          </label>
        )}
        <label className="block">Input name
          <input aria-label="Input name" value={name} onChange={e => setName(e.target.value)}
            className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-1.5 dark:border-gray-600" />
        </label>
        <label className="block">Type
          <select aria-label="Input type" value={type} onChange={e => setType(e.target.value as (typeof TYPES)[number])}
            className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-1.5 dark:border-gray-600">
            {TYPES.map(t => <option key={t}>{t}</option>)}
          </select>
        </label>
        <p className="text-xs text-gray-500">Default: {current.value}</p>
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" disabled={!valid} onClick={() => onSave({ op: 'map', seq: step.seq, field, input: { name, type, default: current.value } })}>Make input</Button>
      </div>
    </Dialog>
  )
}
