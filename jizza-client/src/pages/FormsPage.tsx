import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ClipboardList, Copy, FilePlus2, Globe, Inbox, Plus, Trash2, Workflow as WorkflowIcon } from 'lucide-react'
import { formsApi, publicFormUrl, type FormDto } from '@/api/forms'
import { workflowsApi } from '@/api/workflows'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Card } from '@/components/ui/Card'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState, LoadError } from '@/components/ui/EmptyState'
import { PageHeader } from '@/components/ui/PageHeader'
import { Spinner } from '@/components/ui/Spinner'
import { fillable, stepsOf } from '@/components/formbuilder/stepFields'

/**
 * Forms: templates people fill in at a public link. Each submission can start workflows. A form
 * can start blank or from one of the person's workflows, whose steps then make its fields.
 */
export function FormsPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [creating, setCreating] = useState(false)
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['forms'], queryFn: () => formsApi.list() })
  const remove = useMutation({
    mutationFn: (id: string) => formsApi.remove(id),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['forms'] }); void qc.invalidateQueries({ queryKey: ['workflows'] }) },
  })
  return (
    <div className="page">
      <NewFormDialog open={creating} onClose={() => setCreating(false)} />
      <PageHeader icon={ClipboardList} title="Forms" description="Build forms, publish them at a link anyone can open, and start workflows with what people send."
        actions={<Button size="sm" onClick={() => setCreating(true)}><Plus className="h-4 w-4" />New form</Button>} />
      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {isError && <LoadError what="your forms" onRetry={() => void refetch()} />}
      {data && data.length === 0 && (
        <EmptyState icon={ClipboardList} title="No forms yet"
          description="Drag in fields to build a form, or start from a workflow and let Jizza make the fields its steps need."
          action={{ label: "New form", onClick: () => setCreating(true) }} />
      )}
      {data && data.length > 0 && (
        <div className="stagger space-y-2">
          {data.map(f => <FormRow key={f.id} form={f} onOpen={() => navigate(`/forms/${f.id}`)}
            onSubmissions={() => navigate(`/forms/${f.id}?view=submissions`)}
            onDelete={() => { if (window.confirm(`Delete “${f.name}” and its ${f.submissionCount} submission${f.submissionCount === 1 ? '' : 's'}? Its link stops working.`)) remove.mutate(f.id) }} />)}
        </div>
      )}
    </div>
  )
}

function FormRow({ form, onOpen, onSubmissions, onDelete }: { form: FormDto; onOpen: () => void; onSubmissions: () => void; onDelete: () => void }) {
  const [copied, setCopied] = useState(false)
  const published = form.status === 'published'
  const stop = (e: React.SyntheticEvent) => e.stopPropagation()
  return (
    <Card padding="md" interactive role="button" tabIndex={0} aria-label={`Open ${form.name}`} className="cursor-pointer"
      onClick={onOpen} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen() } }}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{form.name}</p>
          {form.description && <p className="mt-0.5 line-clamp-1 text-xs text-gray-500">{form.description}</p>}
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-500">
            <span>{form.submissionCount} submission{form.submissionCount === 1 ? '' : 's'}</span>
            {form.workflows.map(w => (
              <span key={w.id} className="inline-flex items-center gap-1"><WorkflowIcon className="h-3 w-3" />{w.name}<span className={w.enabled ? 'text-emerald-600' : 'text-gray-400'}>({w.enabled ? 'on' : 'off'})</span></span>
            ))}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5" onClick={stop} onKeyDown={stop}>
          {published && form.publicToken && (
            <Button size="sm" variant="ghost" onClick={() => { void navigator.clipboard?.writeText(publicFormUrl(form.publicToken!)); setCopied(true); setTimeout(() => setCopied(false), 1500) }}>
              <Copy className="h-4 w-4" />{copied ? 'Copied' : 'Copy link'}
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onSubmissions}><Inbox className="h-4 w-4" />Submissions</Button>
          <Button size="sm" variant="ghost" aria-label={`Delete ${form.name}`} onClick={onDelete}><Trash2 className="h-4 w-4" /></Button>
          <Badge variant={published ? 'success' : 'default'}>{published ? <span className="inline-flex items-center gap-1"><Globe className="h-3 w-3" />Published</span> : 'Draft'}</Badge>
        </div>
      </div>
    </Card>
  )
}

/** Blank, or from a workflow: the builder then opens on that workflow's steps. */
function NewFormDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [mode, setMode] = useState<'pick' | 'workflow'>('pick')
  const [error, setError] = useState<string | null>(null)
  const { data: workflows } = useQuery({ queryKey: ['workflows'], queryFn: () => workflowsApi.list(), enabled: open })
  const create = useMutation({
    mutationFn: async (workflowId?: string) => {
      const wf = workflows?.find(w => w.id === workflowId)
      const form = await formsApi.create({ name: wf ? `${wf.name} form` : 'Untitled form', fields: [], settings: {}, sourceWorkflowId: wf?.id })
      // The workflow it was made from starts when it is sent, unless another form already starts it.
      if (wf && !wf.formId) return formsApi.setWorkflows(form.id, [wf.id])
      return form
    },
    onSuccess: form => {
      void qc.invalidateQueries({ queryKey: ['forms'] })
      void qc.invalidateQueries({ queryKey: ['workflows'] })
      close()
      navigate(`/forms/${form.id}${form.sourceWorkflowId ? '?tab=workflow' : ''}`)
    },
    onError: e => setError((e as Error).message),
  })
  const close = () => { setMode('pick'); setError(null); onClose() }
  const usable = (workflows ?? []).filter(w => stepsOf(w.graph).some(fillable))
  const sorted = [...usable].sort((a, b) => Number(!!b.enabled) - Number(!!a.enabled) || a.name.localeCompare(b.name))
  return (
    <Dialog open={open} onClose={close} size="lg" title="New form"
      description={mode === 'pick' ? 'Start with an empty form, or let a workflow’s steps make the fields.' : 'Pick a workflow. Its steps appear on the left; drag one onto the form to make the fields it needs.'}>
      {mode === 'pick' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <button type="button" disabled={create.isPending} onClick={() => create.mutate(undefined)}
            className="rounded-xl border border-gray-200 p-4 text-left transition-colors hover:border-brand-400 hover:bg-brand-50/50 dark:border-gray-700 dark:hover:bg-brand-500/10">
            <FilePlus2 className="h-6 w-6 text-gray-600 dark:text-gray-300" />
            <p className="mt-2 text-sm font-semibold text-gray-900 dark:text-gray-100">Blank form</p>
            <p className="mt-1 text-xs text-gray-500">Drag in Basic and Advanced fields.</p>
          </button>
          <button type="button" onClick={() => setMode('workflow')}
            className="rounded-xl border border-gray-200 p-4 text-left transition-colors hover:border-brand-400 hover:bg-brand-50/50 dark:border-gray-700 dark:hover:bg-brand-500/10">
            <WorkflowIcon className="h-6 w-6 text-brand-500" />
            <p className="mt-2 text-sm font-semibold text-gray-900 dark:text-gray-100">From a workflow</p>
            <p className="mt-1 text-xs text-gray-500">Jizza makes the fields each step needs. Accept or remove them, and the form starts the workflow.</p>
          </button>
        </div>
      )}
      {mode === 'workflow' && (
        <div>
          {!workflows && <div className="flex justify-center py-8"><Spinner /></div>}
          {workflows && sorted.length === 0 && <p className="text-sm text-gray-500">None of your workflows have steps a form can fill in yet.</p>}
          <ul className="max-h-80 space-y-1.5 overflow-y-auto">
            {sorted.map(w => (
              <li key={w.id}>
                <button type="button" disabled={create.isPending} onClick={() => create.mutate(w.id)}
                  className="flex w-full items-center gap-3 rounded-lg border border-gray-200 px-3 py-2.5 text-left hover:border-brand-400 dark:border-gray-700">
                  <WorkflowIcon className="h-4 w-4 shrink-0 text-gray-500" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm text-gray-900 dark:text-gray-100">{w.name}</span>
                    <span className="block text-xs text-gray-500">{stepsOf(w.graph).filter(fillable).length} steps with fields{w.formId ? ' · already starts from a form' : ''}</span>
                  </span>
                  <Badge variant={w.enabled ? 'success' : 'default'}>{w.enabled ? 'On' : 'Off'}</Badge>
                </button>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex justify-between">
            <Button size="sm" variant="ghost" onClick={() => setMode('pick')}>Back</Button>
          </div>
        </div>
      )}
      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
    </Dialog>
  )
}
