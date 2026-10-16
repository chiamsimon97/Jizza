import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Workflow, Plus, Shield } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { PageHeader } from '@/components/ui/PageHeader'
import { WorkflowForm } from '@/components/forms/WorkflowForm'
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
  const { data, isLoading } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => workflowsApi.list(),
  })

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
            <Card key={wf.id} padding="md">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{wf.name}</p>
                  {wf.description && (
                    <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{wf.description}</p>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Button size="sm" variant="ghost" aria-expanded={guarding === wf.id}
                    onClick={() => setGuarding(guarding === wf.id ? null : wf.id)}>
                    <Shield className="h-4 w-4" />Guardrails
                  </Button>
                  <Badge variant={wf.status === 'ACTIVE' ? 'success' : 'default'}>{wf.status}</Badge>
                </div>
              </div>
              {guarding === wf.id && (
                <div className="mt-3 border-t border-gray-100 pt-3 dark:border-gray-800">
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
