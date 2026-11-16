import { useState } from 'react'
import { ArrowDown, ArrowUp, CircleOff, CircleSlash, KeyRound, ShieldAlert, ListChecks, Pencil, SlidersHorizontal, Trash2, Undo2, Wand2, X } from 'lucide-react'
import type { EditOp, RecordedStep, Recording, WaitOutcome } from '@/api/web'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Badge } from '@/components/ui/Badge'
import { ElementPreview } from './PageView'
import { WaitOutcomes } from './WaitOutcomes'
import { conditionDepths, detailsInput, detailsOf, detailsProblem, type InputDetails, dependentListChoices, emptyConditions, fieldName, isCondition, isListChoice, isPassword, isUsernameBox, liveSteps, mappableFields, movesBetween, movesFor, moveOp, pageArrangement, reusableOps, stepsUsingInput, suggestInputName, unmapOps } from './recordingModel'

/** A login saved in the browser extension, as its device lists it. */
export interface SavedLogin { id: string; name: string; description?: string | null }

const TYPES = ['text', 'number', 'boolean', 'date', 'datetime'] as const

/**
 * Where a person checks what Chrome captured before it becomes steps: rename, reorder, delete,
 * turn a typed value into an input, and choose a saved login for each password.
 */
export function RecordingReview({ recording, busy, logins, onEdit }: {
  recording: Recording
  busy?: boolean
  /** The logins saved in the browser it was recorded in (name and site only, never a password). */
  logins?: SavedLogin[]
  onEdit: (ops: EditOp[]) => void
}) {
  const steps = liveSteps(recording.steps)
  const [renaming, setRenaming] = useState<RecordedStep | null>(null)
  const [mapping, setMapping] = useState<RecordedStep | null>(null)
  const [dragging, setDragging] = useState<number | null>(null)
  // Each edit that can be undone pushes the edits that take it back; Undo sends the last of them.
  const [trail, setTrail] = useState<EditOp[][]>([])
  const [unmapping, setUnmapping] = useState<{ step: RecordedStep; field: string; name: string } | null>(null)
  const [confirming, setConfirming] = useState<{ title: string; body: string; ops: EditOp[]; undoable?: EditOp[] } | null>(null)
  const [tidy, setTidy] = useState<{ order: number[]; moved: Set<number> } | null>(null)
  const [tidyNote, setTidyNote] = useState<string | null>(null)

  const edit = (ops: EditOp[], inverse?: EditOp[]) => {
    // An edit with no way back (removing a field) also clears the trail: undo must stay honest.
    setTrail(t => (inverse && inverse.length > 0 ? [...t, inverse] : []))
    onEdit(ops)
  }
  const undoLast = () => {
    const last = trail[trail.length - 1]
    if (!last) return
    setTrail(t => t.slice(0, -1))
    onEdit(last)
  }
  const askDelete = (s: RecordedStep) => {
    const dependents = dependentListChoices(recording.steps, s)
    const inverse: EditOp[] = [{ op: 'restore', seq: s.seq }]
    if (dependents.length === 0) { edit([{ op: 'delete', seq: s.seq }], inverse); return }
    setConfirming({
      title: 'Other steps follow this list',
      body: `${dependents.map(d => `"${d.step.label ?? d.step.action}"`).join(', ')} choose${dependents.length === 1 ? 's' : ''} from a list that follows this one. Deleting it keeps them, but they will choose from whatever the page shows.`,
      ops: [{ op: 'delete', seq: s.seq }],
      undoable: inverse,
    })
  }
  const startTidy = () => {
    const plan = pageArrangement(recording.steps)
    if (plan === null) { setTidyNote('The steps are already in page order.'); return }
    if ('blocked' in plan) { setTidyNote(plan.blocked); return }
    setTidy(plan)
  }

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
            <Button size="sm" variant="secondary" disabled={busy}
              onClick={() => edit(reusable, reusable.flatMap(op =>
                op.op === 'map' && op.field ? [{ op: 'map' as const, seq: op.seq, field: op.field, input: null }] : []))}>Make reusable</Button>
          )}
        </div>
      )}
      {empty.size > 0 && (
        <p role="alert" className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
          {empty.size === 1 ? 'A condition has' : `${empty.size} conditions have`} no steps inside, so saving leaves {empty.size === 1 ? 'it' : 'them'} out.
          Drag the step it is for (for example the pop-up's choice) between the condition and its end, or in the browser's side panel use the condition's Take in next step.
        </p>
      )}
      <div className="mb-2 flex items-center justify-end gap-2">
        {tidyNote && <span className="mr-auto text-xs text-gray-500" role="status">{tidyNote}</span>}
        <Button size="sm" variant="ghost" disabled={busy || trail.length === 0} onClick={undoLast} title="Undo the last edit">
          <Undo2 className="mr-1 h-4 w-4" />Undo
        </Button>
        <Button size="sm" variant="secondary" disabled={busy} onClick={startTidy}
          title="Put steps back in the order of the pages they ran on">
          <Wand2 className="mr-1 h-4 w-4" />Tidy up
        </Button>
      </div>
      <ol className="space-y-2" aria-label="Recorded steps">
        {steps.map((s, i) => (
          <li key={s.seq} draggable onDragStart={() => setDragging(i)} onDragOver={e => e.preventDefault()}
            onDrop={() => { if (dragging !== null && dragging !== i) edit([moveOp(steps[dragging], i)], [moveOp(steps[dragging], dragging)]); setDragging(null) }}
            style={depth[i] ? { marginLeft: `${depth[i] * 1.25}rem` } : undefined}
            className={`flex items-start gap-3 rounded-xl border p-2.5 ${isCondition(s) ? 'border-dashed border-brand-300 bg-brand-50/40 dark:border-brand-700 dark:bg-brand-950/20' : 'border-gray-200 dark:border-gray-700'} ${s.step.disabled ? 'opacity-60' : ''}`}>
            {s.step.meta?.preview || s.step.meta?.pagePreview
              ? <ElementPreview deviceId={recording.deviceId} preview={s.step.meta.preview} pagePreview={s.step.meta.pagePreview} label=""
                  className="h-12 w-16 shrink-0 rounded-md border border-gray-200 object-cover dark:border-gray-700" />
              : <span className="h-12 w-16 shrink-0 rounded-md bg-gray-100 dark:bg-gray-800" />}
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100" title={s.step.label ?? s.step.action}>{i + 1}. {s.step.label ?? s.step.action}</p>
              {empty.has(s.seq) && <p className="text-xs text-amber-700 dark:text-amber-300">Nothing inside: left out when saved</p>}
              <p className="truncate text-[11px] text-gray-500">{s.step.meta?.url ?? s.step.meta?.window}</p>
              {s.step.action === 'browser.fillForm' && Array.isArray(s.step.input?.fields) && (
                <ul className="mt-1 space-y-0.5 text-xs text-gray-600 dark:text-gray-300" aria-label="Form fields">
                  {(s.step.input.fields as { label?: string; value?: unknown }[]).map((f, n) => (
                    <li key={n}>
                      {fieldName(s, `fields.${n}.value`)}: {typeof f.value === 'string' ? f.value : ''}
                      {s.step.mappings?.[`fields.${n}.value`] && <span className="ml-1 text-brand-600 dark:text-brand-300">(input: {s.step.mappings[`fields.${n}.value`].name})</span>}
                      <button aria-label={`Remove field ${fieldName(s, `fields.${n}.value`)}`} disabled={busy}
                        title="Remove this field from the step" className="ml-1 inline-flex align-middle opacity-50 hover:opacity-100"
                        onClick={() => setConfirming({
                          title: 'Remove this field?',
                          body: `"${fieldName(s, `fields.${n}.value`)}" is removed from the step, with its input if it has one. This cannot be undone.`,
                          ops: [{ op: 'removeField', seq: s.seq, toIndex: n }],
                        })}><X className="h-3 w-3" /></button>
                    </li>
                  ))}
                </ul>
              )}
              {s.step.action === 'browser.waitFor' && Array.isArray(s.step.input?.outcomes) && (
                <WaitOutcomes outcomes={s.step.input.outcomes as WaitOutcome[]} busy={busy}
                  onChange={next => edit([{ op: 'update', seq: s.seq, patch: { outcomes: next } }],
                    [{ op: 'update', seq: s.seq, patch: { outcomes: s.step.input!.outcomes as WaitOutcome[] } }])} />
              )}
              {s.step.action === 'browser.fillRows' && Array.isArray(s.step.input?.rows) && (
                <p className="mt-1 text-xs text-gray-600 dark:text-gray-300" data-testid="rows-summary">
                  {(s.step.input.rows as unknown[]).length} row{(s.step.input.rows as unknown[]).length === 1 ? '' : 's'} of{' '}
                  {(Array.isArray(s.step.input.columns) ? s.step.input.columns as { label?: string; key?: string; name?: string }[] : [])
                    .map((c, n) => (
                      <span key={n} className="mr-1 inline-flex items-center">
                        {c.label ?? c.key ?? c.name}
                        <button aria-label={`Remove column ${c.label ?? c.key ?? c.name}`} disabled={busy}
                          title="Remove this column from every row" className="ml-0.5 inline-flex align-middle opacity-50 hover:opacity-100"
                          onClick={() => setConfirming({
                            title: 'Remove this column?',
                            body: `"${c.label ?? c.key ?? c.name}" is removed from the step and from every row. This cannot be undone.`,
                            ops: [{ op: 'removeField', seq: s.seq, toIndex: n }],
                          })}><X className="h-3 w-3" /></button>
                      </span>
                    ))}
                  {s.step.mappings?.rows && <span className="ml-1 text-brand-600 dark:text-brand-300">(input: {s.step.mappings.rows.name})</span>}
                </p>
              )}
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                {s.step.disabled && <Badge>Off</Badge>}
                {s.step.optional && <Badge>Optional</Badge>}
                {s.step.meta?.irreversible && <Badge>Can't be undone</Badge>}
                {s.step.action === 'flow.keep' && <Badge>Jizza step</Badge>}
                {s.step.action === 'browser.clickOneOf' && <Badge>one of {s.step.meta?.list?.count ?? 'several'} buttons{s.step.meta?.list?.title ? `: ${s.step.meta.list.title}` : ''}</Badge>}
                {s.step.action === 'browser.selectOption' && (
                  <Badge>dropdown, {s.step.input?.mode === 'dynamic'
                    ? 'items read on each run'
                    : Array.isArray(s.step.input?.options) ? `${(s.step.input.options as unknown[]).length} items kept` : 'items read on each run'}</Badge>
                )}
                {s.step.action === 'browser.setDate' && typeof s.step.input?.format === 'string' && <Badge>date as {s.step.input.format}</Badge>}
                {isListChoice(s) && <Badge>list{s.step.meta?.list?.count ? ` of ${s.step.meta.list.count}` : ''}{s.step.meta?.list?.marked ? ', marked by you' : ''}</Badge>}
                {isListChoice(s) && s.step.meta?.list?.named && (
                  <Badge>{[s.step.meta.list.withinTitle, s.step.meta.list.title].filter(Boolean).join(' › ')}</Badge>
                )}
                {isListChoice(s) && s.step.meta?.list?.dependsOn?.relation === 'after' && (
                  <Badge>after {s.step.meta.list.dependsOn.title}</Badge>
                )}
                {Object.entries(s.step.mappings ?? {}).map(([field, m]) => (
                  <Badge key={field}>input: {m.name}
                    <button aria-label={`Stop "${m.name}" being an input`} disabled={busy}
                      title="Stop being an input: the recorded value is used as is"
                      className="ml-1 inline-flex align-middle opacity-60 hover:opacity-100"
                      onClick={() => {
                        const elsewhere = stepsUsingInput(recording.steps, m.name).filter(o => o.seq !== s.seq)
                        if (elsewhere.length > 0) setUnmapping({ step: s, field, name: m.name })
                        else edit(unmapOps([{ step: s, field }]), [{ op: 'map', seq: s.seq, field, input: { ...m } }])
                      }}><X className="h-3 w-3" /></button>
                  </Badge>
                ))}
                {isPassword(s) && (
                  <label className="flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
                    <KeyRound className="h-3.5 w-3.5" />{recording.kind === 'desktop' ? 'Secret on the PC' : 'Saved login'}
                    {recording.kind !== 'desktop' && logins && logins.length > 0 ? (
                      // Chosen by name: nobody should have to know a saved login's id.
                      <select aria-label="Saved login" value={s.step.secretRef ?? ''} disabled={busy}
                        onChange={e => onEdit([{ op: 'secret', seq: s.seq, secretRef: e.target.value || null }])}
                        className="max-w-[14rem] rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600">
                        <option value="">Choose…</option>
                        {s.step.secretRef && !logins.some(l => l.id === s.step.secretRef) && <option value={s.step.secretRef}>{s.step.secretRef}</option>}
                        {logins.map(l => <option key={l.id} value={l.id}>{l.name}{l.description ? ` (${l.description})` : ''}</option>)}
                      </select>
                    ) : (
                      <input aria-label="Saved login" defaultValue={s.step.secretRef ?? ''} placeholder={recording.kind === 'desktop' ? 'Secret name' : 'Saved login id'}
                        onBlur={e => { const v = e.target.value.trim(); if (v !== (s.step.secretRef ?? '')) onEdit([{ op: 'secret', seq: s.seq, secretRef: v || null }]) }}
                        className="w-40 rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600" />
                    )}
                  </label>
                )}
                {isUsernameBox(s) && recording.kind !== 'desktop' && logins && logins.length > 0 && (
                  // A username box can type the username of a saved login instead of what was typed.
                  <label className="flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
                    <KeyRound className="h-3.5 w-3.5" />Username from
                    <select aria-label="Username from saved login" value={s.step.secretRef ?? ''} disabled={busy}
                      onChange={e => onEdit([{ op: 'secret', seq: s.seq, secretRef: e.target.value || null }])}
                      className="max-w-[14rem] rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600">
                      <option value="">What was typed</option>
                      {s.step.secretRef && !logins.some(l => l.id === s.step.secretRef) && <option value={s.step.secretRef}>{s.step.secretRef}</option>}
                      {logins.map(l => <option key={l.id} value={l.id}>{l.name}{l.description ? ` (${l.description})` : ''}</option>)}
                    </select>
                  </label>
                )}
                {isPassword(s) && recording.kind !== 'desktop' && logins && logins.length === 0 && (
                  <span className="text-xs text-amber-700 dark:text-amber-300">No saved logins in this browser yet: add one in the extension's Settings.</span>
                )}
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-0.5">
              <Button size="sm" variant="ghost" aria-label="Move up" disabled={busy || i === 0} onClick={() => edit([moveOp(s, i - 1)], [moveOp(s, i)])}><ArrowUp className="h-4 w-4" /></Button>
              <Button size="sm" variant="ghost" aria-label="Move down" disabled={busy || i === steps.length - 1} onClick={() => edit([moveOp(s, i + 1)], [moveOp(s, i)])}><ArrowDown className="h-4 w-4" /></Button>
              {editing && !isCondition(s) && (
                <>
                  <Button size="sm" variant="ghost" aria-label={s.step.disabled ? 'Turn step on' : 'Turn step off'} title={s.step.disabled ? 'Turn on' : 'Turn off: kept, but not run'}
                    disabled={busy} onClick={() => onEdit([{ op: 'update', seq: s.seq, patch: { disabled: !s.step.disabled } }])}><CircleOff className="h-4 w-4" /></Button>
                  <Button size="sm" variant="ghost" aria-label={s.step.optional ? 'Make step required' : 'Make step optional'} title={s.step.optional ? 'Make required' : 'Optional: skipped when it is not on the page'}
                    disabled={busy} onClick={() => onEdit([{ op: 'update', seq: s.seq, patch: { optional: !s.step.optional } }])}><CircleSlash className="h-4 w-4" /></Button>
                </>
              )}
              {!isCondition(s) && s.step.action !== 'flow.keep' && (
                <Button size="sm" variant="ghost" aria-label={s.step.meta?.irreversible ? "Mark as one that can be redone" : "Mark as can't be undone"}
                  title={s.step.meta?.irreversible ? "Can't be undone (pay, order, book): never sent twice. Click to unmark" : "Can't be undone (pay, order, book): a run never sends it twice, and asks you to check before trying again"}
                  disabled={busy} onClick={() => edit([{ op: 'update', seq: s.seq, patch: { irreversible: !s.step.meta?.irreversible } }],
                    [{ op: 'update', seq: s.seq, patch: { irreversible: !!s.step.meta?.irreversible } }])}>
                  <ShieldAlert className={`h-4 w-4 ${s.step.meta?.irreversible ? 'text-amber-600' : ''}`} />
                </Button>
              )}
              <Button size="sm" variant="ghost" aria-label="Rename step" onClick={() => setRenaming(s)}><Pencil className="h-4 w-4" /></Button>
              {mappableFields(s).length > 0 && <Button size="sm" variant="ghost" aria-label="Make this an input" onClick={() => setMapping(s)}><SlidersHorizontal className="h-4 w-4" /></Button>}
              <Button size="sm" variant="ghost" aria-label="Delete step" disabled={busy} onClick={() => askDelete(s)}><Trash2 className="h-4 w-4" /></Button>
            </div>
          </li>
        ))}
      </ol>
      {renaming && <RenameDialog step={renaming} onClose={() => setRenaming(null)}
        onSave={label => {
          edit([{ op: 'relabel', seq: renaming.seq, label }],
            renaming.step.label ? [{ op: 'relabel', seq: renaming.seq, label: renaming.step.label }] : undefined)
          setRenaming(null)
        }} />}
      {mapping && <InputDialog step={mapping} onClose={() => setMapping(null)}
        onSave={op => {
          const before = op.field ? mapping.step.mappings?.[op.field] : undefined
          edit([op], op.field ? [{ op: 'map', seq: mapping.seq, field: op.field,
            input: before ? { ...before } : null }] : undefined)
          setMapping(null)
        }} />}
      {unmapping && (
        <Dialog open title={`Stop "${unmapping.name}" being an input?`} onClose={() => setUnmapping(null)}>
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {stepsUsingInput(recording.steps, unmapping.name).length} steps use this input. The recorded value stays on each.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setUnmapping(null)}>Cancel</Button>
            <Button variant="secondary" size="sm" onClick={() => {
              const target = { step: unmapping.step, field: unmapping.field }
              const m = unmapping.step.step.mappings?.[unmapping.field]
              edit(unmapOps([target]), m ? [{ op: 'map', seq: unmapping.step.seq, field: unmapping.field, input: { ...m } }] : undefined)
              setUnmapping(null)
            }}>Only this step</Button>
            <Button size="sm" onClick={() => {
              const targets = stepsUsingInput(recording.steps, unmapping.name).flatMap(step =>
                Object.entries(step.step.mappings ?? {})
                  .filter(([, m]) => m.name === unmapping.name)
                  .map(([field]) => ({ step, field })))
              const inverse = targets.flatMap(({ step, field }) => {
                const m = step.step.mappings?.[field]
                return m ? [{ op: 'map' as const, seq: step.seq, field, input: { ...m } }] : []
              })
              edit(unmapOps(targets), inverse)
              setUnmapping(null)
            }}>Every step</Button>
          </div>
        </Dialog>
      )}
      {confirming && (
        <Dialog open title={confirming.title} onClose={() => setConfirming(null)}>
          <p className="text-sm text-gray-600 dark:text-gray-300">{confirming.body}</p>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setConfirming(null)}>Cancel</Button>
            <Button size="sm" onClick={() => { edit(confirming.ops, confirming.undoable); setConfirming(null) }}>
              {confirming.ops[0]?.op === 'delete' ? 'Delete' : 'Remove'}
            </Button>
          </div>
        </Dialog>
      )}
      {tidy && (
        <Dialog open title="Tidy up the order?" description="Steps grouped by the page they ran on, pages in the order you first visited them." onClose={() => setTidy(null)}>
          <ol className="max-h-64 space-y-1 overflow-y-auto text-sm" aria-label="New order">
            {tidy.order.map((seq, i) => {
              const s = steps.find(x => x.seq === seq)
              return (
                <li key={seq} className={tidy.moved.has(seq) ? 'font-medium text-brand-700 dark:text-brand-300' : 'text-gray-600 dark:text-gray-300'}>
                  {i + 1}. {s?.step.label ?? s?.step.action}{tidy.moved.has(seq) ? ' (moved)' : ''}
                </li>
              )
            })}
          </ol>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setTidy(null)}>Cancel</Button>
            <Button size="sm" onClick={() => {
              const before = steps.map(s => s.seq)
              edit(movesFor(recording.steps, tidy.order), movesBetween(tidy.order, before))
              setTidy(null)
            }}>Apply new order</Button>
          </div>
        </Dialog>
      )}
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
  const existing = step.step.mappings?.[field]
  const [name, setName] = useState(existing?.name ?? suggestInputName(step, field))
  // A date the site writes its own way keeps that format with the input.
  const format = typeof step.step.input?.format === 'string' && step.step.input.format ? step.step.input.format : undefined
  const dated = step.step.action === 'browser.setDate' || step.step.action === 'browser.pickDate'
  const [type, setType] = useState<(typeof TYPES)[number]>(
    (TYPES as readonly string[]).includes(existing?.type ?? '') ? existing!.type as (typeof TYPES)[number]
      : dated ? (format && /[Hh]/.test(format) ? 'datetime' : 'date') : 'text')
  const [details, setDetails] = useState<InputDetails>(() => detailsOf(existing))
  const [more, setMore] = useState(Boolean(existing && (existing.label || existing.group || existing.options || existing.pattern)))
  const valid = /^[A-Za-z][A-Za-z0-9_]*$/.test(name)
  const problem = detailsProblem(details)
  const box = 'mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-1.5 dark:border-gray-600'
  const set = (patch: Partial<InputDetails>) => setDetails(d => ({ ...d, ...patch }))
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
          <input aria-label="Input name" value={name} onChange={e => setName(e.target.value)} className={box} />
        </label>
        <label className="block">Type
          <select aria-label="Input type" value={type} onChange={e => setType(e.target.value as (typeof TYPES)[number])}
            className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-1.5 dark:border-gray-600">
            {TYPES.map(t => <option key={t}>{t}</option>)}
          </select>
        </label>
        <p className="text-xs text-gray-500">Default: {current.value}</p>
        {format && (type === 'date' || type === 'datetime') && (
          <p className="text-xs text-gray-500">The site writes it as {format}. Any date given is written that way.</p>
        )}
        <button type="button" className="text-xs font-medium text-indigo-600 hover:underline dark:text-indigo-400" aria-expanded={more}
          onClick={() => setMore(m => !m)}>{more ? 'Fewer details' : 'More details: label, section, when to ask, accepted values'}</button>
        {more && (
          <div className="space-y-3 rounded-lg border border-gray-200 p-3 dark:border-gray-700">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block">Label on the run form
                <input aria-label="Input label" value={details.label} onChange={e => set({ label: e.target.value })} className={box} />
              </label>
              <label className="block">Section
                <input aria-label="Input section" value={details.group} placeholder="The page it was typed on" onChange={e => set({ group: e.target.value })} className={box} />
              </label>
            </div>
            <label className="block">When to ask
              <select aria-label="When to ask" value={details.ask} onChange={e => set({ ask: e.target.value as InputDetails['ask'] })}
                className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-1.5 dark:border-gray-600">
                <option value="">Before the run (default)</option>
                <option value="atStep">During the run, on this step's card</option>
                <option value="ifMissing">Only if the run wasn't given it</option>
              </select>
            </label>
            <label className="block">Accepted values (one per line; empty accepts anything)
              <textarea aria-label="Accepted values" rows={3} value={details.options} onChange={e => set({ options: e.target.value })} className={box} />
            </label>
            {details.options.trim() && (
              <label className="block">Also accept (one per line, such as: other words = accepted value)
                <textarea aria-label="Also accept" rows={2} value={details.synonyms} onChange={e => set({ synonyms: e.target.value })} className={box} />
              </label>
            )}
            {type === 'number' && (
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block">Smallest
                  <input aria-label="Smallest value" inputMode="decimal" value={details.min} onChange={e => set({ min: e.target.value })} className={box} />
                </label>
                <label className="block">Largest
                  <input aria-label="Largest value" inputMode="decimal" value={details.max} onChange={e => set({ max: e.target.value })} className={box} />
                </label>
              </div>
            )}
            {type === 'text' && !details.options.trim() && (
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block">Pattern (regular expression)
                  <input aria-label="Pattern" value={details.pattern} onChange={e => set({ pattern: e.target.value })} className={box} />
                </label>
                <label className="block">Shown when it doesn't match
                  <input aria-label="Pattern hint" value={details.patternHint} placeholder="such as 10 digits" onChange={e => set({ patternHint: e.target.value })} className={box} />
                </label>
              </div>
            )}
            {problem && <p className="text-xs text-red-500">{problem}</p>}
          </div>
        )}
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" disabled={!valid || problem !== null} onClick={() => onSave({ op: 'map', seq: step.seq, field, input: {
          ...(existing?.required ? { required: true } : {}),
          name, type, default: current.value,
          ...(format && (type === 'date' || type === 'datetime') ? { format } : {}),
          ...detailsInput(details, type),
        } })}>{existing ? 'Save input' : 'Make input'}</Button>
      </div>
    </Dialog>
  )
}
