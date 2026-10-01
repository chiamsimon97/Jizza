import { useQuery } from '@tanstack/react-query'
import { Workflow } from 'lucide-react'
import { workflowsApi } from '@/api/workflows'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function WorkflowsPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => workflowsApi.list(),
  })

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <Workflow className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Workflows</h1>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Workflow} title="No workflows" description="Saved workflows appear here." />
      )}

      {data && data.length > 0 && (
        <div className="space-y-2">
          {data.map(wf => (
            <Card key={wf.id} padding="md">
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
