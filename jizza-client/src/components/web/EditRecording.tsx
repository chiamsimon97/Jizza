import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CheckCircle2, Pencil } from 'lucide-react'
import { webApi, type LinkedUpdate, type LinkedWorkflow } from '@/api/web'
import type { WorkflowDto } from '@/types'
import { moduleInputs } from '@/components/workflow/moduleContract'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'

/** Turns a default value into text for a test value box. */
function asText(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') return value
  if (Array.isArray(value) && value.length === 1 && typeof value[0] === 'string') return value[0]
  return JSON.stringify(value)
}

/**
 * Edit recording, first step: the browser to edit a saved recording in, and the test values its
 * steps run with (each input's default to start with). Opens the recording there to step through.
 */
export function EditRecordingStart({ workflow, onClose, onStarted }: {
  workflow: WorkflowDto
  onClose: () => void
  onStarted: (recordingId: string) => void
}) {
  const inputs = moduleInputs(workflow.graph)
  const [browserId, setBrowserId] = useState('')
  const [values, setValues] = useState<Record<string, string>>(
    () => Object.fromEntries(inputs.map(f => [f.name, asText(f.default)])))
  const browsers = useQuery({ queryKey: ['web-browsers'], queryFn: webApi.browsers })
  const online = (browsers.data ?? []).filter(b => b.online)
  const offline = (browsers.data ?? []).filter(b => !b.online)
  const start = useMutation({
    mutationFn: () => webApi.editSavedRecording({ workflowId: workflow.id, deviceId: browserId, testValues: values }),
    onSuccess: r => onStarted(r.id),
  })

  return (
    <Dialog open size="lg" title={`Edit "${workflow.name}"`} onClose={onClose}
      description="It opens in your browser with its steps in the side panel. Run them one at a time with Next, add new actions where you are, and put steps that only sometimes appear in a condition. Test runs skip guardrails.">
      <form className="space-y-3 text-sm" onSubmit={e => { e.preventDefault(); start.mutate() }}>
        {start.isError && (
          <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-red-700 dark:bg-red-500/10 dark:text-red-300">
            {start.error instanceof Error ? start.error.message : 'Could not open the recording'}
          </p>
        )}
        <label className="block">Browser agent
          <select aria-label="Browser agent" value={browserId} onChange={e => setBrowserId(e.target.value)}
            className="mt-1 w-full rounded-lg border border-gray-300 bg-transparent px-2 py-2 dark:border-gray-600">
            <option value="">Choose a browser</option>
            {online.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
            {offline.map(b => <option key={b.id} value={b.id} disabled>{b.name}: open it to edit there</option>)}
          </select>
        </label>
        {inputs.length > 0 && (
          <fieldset className="space-y-2">
            <legend className="text-xs font-semibold text-gray-700 dark:text-gray-200">Test values</legend>
            <p className="text-xs text-gray-500">The steps run with these while you edit. Saving keeps each input's own default.</p>
            {inputs.map(f => (
              <label key={f.name} className="block">
                <span className="text-xs text-gray-600 dark:text-gray-300">{f.name}{f.description ? `: ${f.description}` : ''}</span>
                <input aria-label={`Test value for ${f.name}`} value={values[f.name] ?? ''}
                  onChange={e => setValues(v => ({ ...v, [f.name]: e.target.value }))}
                  className="mt-0.5 w-full rounded-lg border border-gray-300 bg-transparent px-3 py-1.5 dark:border-gray-600" />
              </label>
            ))}
          </fieldset>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
          <Button type="submit" size="sm" disabled={!browserId} loading={start.isPending}><Pencil className="h-4 w-4" />Edit in browser</Button>
        </div>
      </form>
    </Dialog>
  )
}

/**
 * After an edited recording is saved: the workflows that run it as a step. They always run its
 * newest version; updating one fits its step's inputs to the recording's (adds new ones, drops gone ones).
 */
export function LinkedWorkflowsDialog({ recordingId, linked, onClose }: {
  recordingId: string
  linked: LinkedWorkflow[]
  onClose: () => void
}) {
  const needsUpdate = (l: LinkedWorkflow) => l.missingInputs.length > 0 || l.unknownInputs.length > 0
  const [chosen, setChosen] = useState<Set<string>>(
    () => new Set(linked.filter(l => l.canUpdate && needsUpdate(l)).map(l => l.workflowId)))
  const [results, setResults] = useState<LinkedUpdate[] | null>(null)
  const update = useMutation({
    mutationFn: () => webApi.updateLinkedWorkflows(recordingId, [...chosen]),
    onSuccess: setResults,
  })
  const toggle = (id: string) => setChosen(s => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  return (
    <Dialog open size="lg" title="Update the workflows that use it?" onClose={onClose}
      description="These workflows run this recording as a step, and run its new version from now on. Update them where its inputs changed.">
      {update.isError && (
        <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-300">
          {update.error instanceof Error ? update.error.message : 'Could not update them'}
        </p>
      )}
      <ul className="space-y-2 text-sm" aria-label="Linked workflows">
        {linked.map(l => {
          const result = results?.find(r => r.workflowId === l.workflowId)
          return (
            <li key={l.workflowId} className="rounded-lg border border-gray-200 p-2.5 dark:border-gray-700">
              <label className="flex items-start gap-2">
                <input type="checkbox" aria-label={`Update ${l.name}`} className="mt-1"
                  disabled={!l.canUpdate || !!results} checked={chosen.has(l.workflowId)} onChange={() => toggle(l.workflowId)} />
                <span className="min-w-0 flex-1">
                  <span className="font-medium">{l.name}</span>
                  <span className="block text-xs text-gray-500">In {l.stepLabels.join(', ')}</span>
                  {l.missingInputs.length > 0 && <span className="block text-xs text-gray-600 dark:text-gray-300">New inputs: {l.missingInputs.join(', ')}</span>}
                  {l.unknownInputs.length > 0 && <span className="block text-xs text-gray-600 dark:text-gray-300">No longer used: {l.unknownInputs.join(', ')}</span>}
                  {!needsUpdate(l) && <span className="block text-xs text-gray-500">Its inputs are the same; nothing to change.</span>}
                  {l.note && <span className="block text-xs text-amber-700 dark:text-amber-300">{l.note}</span>}
                  {result && (
                    <span className="mt-1 flex items-center gap-1 text-xs text-gray-700 dark:text-gray-200">
                      {result.updated && <CheckCircle2 className="h-3.5 w-3.5 text-green-600" />}
                      {result.updated ? `Updated (version ${result.version})` : result.note ?? 'Not changed'}
                    </span>
                  )}
                </span>
              </label>
            </li>
          )
        })}
      </ul>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" size="sm" onClick={onClose}>{results ? 'Done' : 'Not now'}</Button>
        {!results && <Button size="sm" disabled={chosen.size === 0} loading={update.isPending} onClick={() => update.mutate()}>Update {chosen.size || ''}</Button>}
      </div>
    </Dialog>
  )
}
