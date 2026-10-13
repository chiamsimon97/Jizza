import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ListTodo } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { tasksApi } from '@/api/tasks'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

const statusVariant = (s: string) => {
  if (s === 'COMPLETED') return 'success' as const
  if (s === 'FAILED' || s === 'CANCELLED') return 'danger' as const
  if (s === 'RUNNING') return 'info' as const
  return 'default' as const
}

export function TasksPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['tasks'],
    queryFn: () => tasksApi.list(),
  })
  const cancel = useMutation({
    mutationFn: (id: string) => tasksApi.cancel(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tasks'] }),
  })

  return (
    <div className="page">
      <PageHeader icon={ListTodo} title="Tasks" description="Scheduled and one-off work." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={ListTodo} title="No tasks" description="Tasks created by agents appear here." />
      )}

      {data && data.length > 0 && (
        <div className="stagger space-y-2">
          {data.map(task => (
            <Card key={task.id} padding="md">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant={statusVariant(task.status)}>{task.status}</Badge>
                    {task.repetitive && <Badge variant="info">Repetitive</Badge>}
                    {task.nextRunAt && (
                      <span className="text-xs text-gray-400">next: {new Date(task.nextRunAt).toLocaleString()}</span>
                    )}
                  </div>
                  <p className="text-sm text-gray-800 dark:text-gray-200">{task.title}</p>
                </div>
                {(task.status === 'SCHEDULED' || task.status === 'RUNNING') && (
                  <Button size="sm" variant="danger" onClick={() => cancel.mutate(task.id)}>
                    Cancel
                  </Button>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
