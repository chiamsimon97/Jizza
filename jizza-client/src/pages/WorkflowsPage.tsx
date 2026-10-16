import { useQuery } from '@tanstack/react-query'
import { Workflow, Plus } from 'lucide-react'
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

export function WorkflowsPage() {
  const openForm = useFormStore(s => s.open)
  const { data, isLoading } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => workflowsApi.list(),
  })

  // A saved workflow opens in the same builder, with its graph read back into steps.
  const openWorkflow = (wf: WorkflowDto) => {
    const steps = stepsFromGraph(wf.graph)
    openForm('new_workflow', { id: wf.id, name: wf.name, description: wf.description, status: wf.status, ...(steps ? { steps } : {}) })
  }

  return (
    <div className="page">
      <WorkflowForm />
      <PageHeader icon={Workflow} title="Workflows" description="Multi-step automations and their runs." actions={<Button size="sm" onClick={() => openForm('new_workflow')}><Plus className="h-4 w-4" />New workflow</Button>} />

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
                <Badge variant={wf.status === 'ACTIVE' ? 'success' : 'default'}>{wf.status}</Badge>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
