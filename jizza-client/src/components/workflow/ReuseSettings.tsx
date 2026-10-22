import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Repeat } from 'lucide-react'
import { workflowsApi } from '@/api/workflows'
import type { ReuseGuardrails, WorkflowDto } from '@/types'

/**
 * Whether Jizza may reuse this workflow when a later request fits it, and how far it may go: ask
 * first, run from a scheduled task, a daily limit. Changing the steps later turns scheduled runs
 * off again until they are allowed for the new steps.
 */
export function ReuseSettings({ workflow }: { workflow: WorkflowDto }) {
  const qc = useQueryClient()
  const reuse = workflow.reuse
  const [error, setError] = useState('')
  const current: ReuseGuardrails = {
    confirmBeforeRun: reuse?.guardrails?.confirmBeforeRun ?? true,
    allowScheduled: reuse?.guardrails?.allowScheduled ?? false,
    maxRunsPerDay: reuse?.guardrails?.maxRunsPerDay,
    rules: reuse?.guardrails?.rules ?? [],
  }
  const done = () => { setError(''); void qc.invalidateQueries({ queryKey: ['workflows'] }) }
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : 'Could not save that')

  const update = useMutation({
    mutationFn: (guardrails: ReuseGuardrails) => workflowsApi.setReuse(workflow.id, {
      ...(reuse ?? {}), summary: reuse?.summary ?? workflow.description ?? workflow.name, guardrails,
    }),
    onSuccess: done, onError: fail,
  })
  const forget = useMutation({ mutationFn: () => workflowsApi.forgetReuse(workflow.id), onSuccess: done, onError: fail })
  const busy = update.isPending || forget.isPending

  return (
    <div className="mb-3 rounded-lg bg-gray-50 p-3 text-xs text-gray-700 dark:bg-gray-950 dark:text-gray-300">
      <p className="flex items-center gap-1.5 text-sm font-medium text-gray-900 dark:text-gray-100">
        <Repeat className="h-3.5 w-3.5" /> Reuse by Jizza
      </p>
      {!reuse ? (
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <span className="text-gray-500 dark:text-gray-400">Jizza does not reuse this workflow.</span>
          <button type="button" disabled={busy} className="font-medium text-brand-600 hover:underline dark:text-brand-300"
            onClick={() => update.mutate(current)}>Let Jizza reuse it</button>
        </div>
      ) : (
        <div className="mt-1 flex flex-col gap-1.5">
          {reuse.summary && <p className="text-gray-500 dark:text-gray-400">Used when: {reuse.summary}</p>}
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={current.confirmBeforeRun} disabled={busy}
              onChange={e => update.mutate({ ...current, confirmBeforeRun: e.target.checked })} />
            Ask me before each run
          </label>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={current.allowScheduled} disabled={busy}
              onChange={e => update.mutate({ ...current, allowScheduled: e.target.checked })} />
            Scheduled tasks doing this job may run it on their own
          </label>
          {current.rules && current.rules.length > 0 && (
            <ul className="list-disc pl-4 text-gray-600 dark:text-gray-400">
              {current.rules.map((r, i) => <li key={i}>{r}</li>)}
            </ul>
          )}
          <button type="button" disabled={busy} className="self-start text-gray-500 hover:underline dark:text-gray-400"
            onClick={() => forget.mutate()}>Stop reusing it</button>
        </div>
      )}
      {error && <p className="mt-1 text-red-600 dark:text-red-400">{error}</p>}
    </div>
  )
}
