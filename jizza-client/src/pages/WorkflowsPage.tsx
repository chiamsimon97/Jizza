import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { History, Workflow, Plus, Shield, Sparkles } from 'lucide-react'
import { WorkflowPlanner } from '@/jizza/WorkflowPlanner'
import { Button } from '@/components/ui/Button'
import { PageHeader } from '@/components/ui/PageHeader'
import { WorkflowForm, stepsFromGraph } from '@/components/forms/WorkflowForm'
import type { WorkflowDto } from '@/types'
import { useFormStore } from '@/jizza/formStore'
import { workflowsApi } from '@/api/workflows'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { WorkflowGuardrails } from '@/components/guardrails/WorkflowGuardrails'

export function WorkflowsPage() {
  const openForm = useFormStore(s => s.open)
  const [guarding, setGuarding] = useState<string | null>(null)
  const [planning, setPlanning] = useState(false)
  const { data, isLoading } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => workflowsApi.list(),
  })

  // A saved workflow opens in the same builder, with its graph read back into steps.
  const openWorkflow = (wf: WorkflowDto) => {
    const steps = stepsFromGraph(wf.graph)
    openForm('new_workflow', { id: wf.id, name: wf.name, description: wf.description, status: wf.status, graph: wf.graph, ...(steps ? { steps } : {}) })
  }

  // The run history links here with ?open=<id> to jump straight into that workflow's builder.
  const [params, setParams] = useSearchParams()
  const openId = params.get('open')
  useEffect(() => {
    const wf = openId ? data?.find(w => w.id === openId) : undefined
    if (!wf) return
    openWorkflow(wf)
    setParams(prev => { const next = new URLSearchParams(prev); next.delete('open'); return next }, { replace: true })
  }, [openId, data])

  return (
    <div className="page">
      <WorkflowForm />
      <WorkflowPlanner open={planning} onClose={() => setPlanning(false)} />
      <PageHeader icon={Workflow} title="Workflows" description="Multi-step automations and their runs." actions={<>
        <Link to="/runs" className="inline-flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800"><History className="h-4 w-4" />Run history</Link>
        <Button size="sm" variant="secondary" onClick={() => openForm('new_workflow')}><Plus className="h-4 w-4" />New workflow</Button>
        <Button size="sm" onClick={() => setPlanning(true)}><Sparkles className="h-4 w-4" />Plan with Jizza</Button>
      </>} />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Workflow} title="No workflows" description="Saved workflows appear here." />
      )}

      {data && data.length > 0 && (
        <div className="stagger space-y-2">
          {data.map(wf => (
            <Card key={wf.id} padding="md"
              role="button" tabIndex={0} aria-label={`Open ${wf.name}`}
              interactive className="cursor-pointer"
              onClick={() => openWorkflow(wf)}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openWorkflow(wf) } }}>
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{wf.name}</p>
                  {wf.description && (
                    <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{wf.description}</p>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Link to={`/runs?wf=${wf.id}`} onClick={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()}
                    className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800">
                    <History className="h-4 w-4" />Runs
                  </Link>
                  <Button size="sm" variant="ghost" aria-expanded={guarding === wf.id}
                    onClick={e => { e.stopPropagation(); setGuarding(guarding === wf.id ? null : wf.id) }}
                    onKeyDown={e => e.stopPropagation()}>
                    <Shield className="h-4 w-4" />Guardrails
                  </Button>
                  <Badge variant={wf.status === 'ACTIVE' ? 'success' : 'default'}>{wf.status}</Badge>
                </div>
              </div>
              {guarding === wf.id && (
                // Setting a step's mode here must not also open the workflow behind it.
                <div className="mt-3 border-t border-gray-100 pt-3 dark:border-gray-800"
                  onClick={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()}>
                  <WorkflowGuardrails workflowId={wf.id} />
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
