import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { GitBranch } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { tasksApi } from '@/api/tasks'
import { proceduresApi } from '@/api/procedures'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function ProceduresPage() {
  const qc = useQueryClient()
  const { data: tasks, isLoading } = useQuery({
    queryKey: ['tasks'],
    queryFn: () => tasksApi.list(),
  })

  const pendingTasks = tasks?.filter(t => t.boundWorkflowId) ?? []

  const approve = useMutation({
    mutationFn: (taskId: string) => proceduresApi.approve(taskId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tasks'] }),
  })
  const reject = useMutation({
    mutationFn: (taskId: string) => proceduresApi.reject(taskId, 'Rejected by user'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tasks'] }),
  })

  return (
    <div className="page">
      <PageHeader icon={GitBranch} title="Procedures" description="Review workflow changes proposed for your tasks." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && pendingTasks.length === 0 && (
        <EmptyState icon={GitBranch} title="No pending procedures" description="Tasks with proposed workflow changes appear here for review." />
      )}

      {pendingTasks.length > 0 && (
        <div className="stagger space-y-2">
          {pendingTasks.map(task => (
            <Card key={task.id} padding="md">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant="warning">Pending review</Badge>
                    {task.nextRunAt && (
                      <span className="text-xs text-gray-400">next: {new Date(task.nextRunAt).toLocaleString()}</span>
                    )}
                  </div>
                  <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{task.title}</p>
                  <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">workflow: {task.boundWorkflowId}</p>
                </div>
                <div className="flex gap-2 shrink-0">
                  <Button size="sm" onClick={() => approve.mutate(task.id)}>Approve</Button>
                  <Button size="sm" variant="danger" onClick={() => reject.mutate(task.id)}>Reject</Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
