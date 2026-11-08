import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ClipboardList } from 'lucide-react'
import { Link } from 'react-router-dom'
import { formsApi } from '@/api/forms'
import { workflowsApi } from '@/api/workflows'
import { Switch } from '@/components/ui/Switch'
import type { WorkflowDto } from '@/types'

/**
 * A saved workflow's triggers: whether it is on, and the form whose submissions start it. Off,
 * nothing starts it unattended; the person can still run it by hand to test it.
 */
export function WorkflowTriggerControls({ workflowId, compact }: { workflowId: string; compact?: boolean }) {
  const qc = useQueryClient()
  const { data: workflows } = useQuery({ queryKey: ['workflows'], queryFn: () => workflowsApi.list() })
  const { data: forms } = useQuery({ queryKey: ['forms'], queryFn: () => formsApi.list() })
  const wf = workflows?.find(w => w.id === workflowId)
  const refresh = (saved: WorkflowDto) => {
    qc.setQueryData<WorkflowDto[]>(['workflows'], list => list?.map(w => (w.id === saved.id ? { ...w, ...saved } : w)))
    void qc.invalidateQueries({ queryKey: ['forms'] })
  }
  const toggle = useMutation({ mutationFn: (on: boolean) => workflowsApi.setEnabled(workflowId, on), onSuccess: refresh })
  const pickForm = useMutation({ mutationFn: (formId: string | null) => workflowsApi.setFormTrigger(workflowId, formId), onSuccess: refresh })
  if (!wf) return null
  const form = forms?.find(f => f.id === wf.formId)
  const error = (toggle.error ?? pickForm.error) as Error | null
  return (
    <div className={`flex flex-wrap items-center gap-x-3 gap-y-1 ${compact ? '' : 'text-sm'}`} onClick={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()}>
      <span className="inline-flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
        <Switch checked={!!wf.enabled} disabled={toggle.isPending} label={wf.enabled ? `Turn ${wf.name} off` : `Turn ${wf.name} on`} onChange={on => toggle.mutate(on)} />
        {wf.enabled ? 'On' : 'Off'}
      </span>
      <label className="inline-flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
        <ClipboardList className="h-3.5 w-3.5" />
        <span className="sr-only sm:not-sr-only">Starts when</span>
        <select aria-label="Form that starts this workflow" disabled={pickForm.isPending}
          className="max-w-[12rem] rounded-md border border-gray-300 bg-white px-1.5 py-1 text-xs dark:border-gray-700 dark:bg-gray-900"
          value={wf.formId ?? ''} onChange={e => pickForm.mutate(e.target.value || null)}>
          <option value="">no form is sent</option>
          {forms?.map(f => <option key={f.id} value={f.id}>“{f.name}” is sent</option>)}
        </select>
      </label>
      {form && <Link to={`/forms/${form.id}`} className="text-xs text-brand-600 hover:underline">Open form</Link>}
      {form && !wf.enabled && <span className="text-[11px] text-amber-700 dark:text-amber-300">Turn it on so the form can start it.</span>}
      {error && <span className="text-[11px] text-red-600">{error.message}</span>}
    </div>
  )
}
