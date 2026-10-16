import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { notificationsApi } from '@/api/notifications'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function NotificationsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.list(),
  })
  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })

  return (
    <div className="page">
      <PageHeader icon={Bell} title="Notifications" description="Questions, approvals and updates for you." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Bell} title="No notifications" description="You're all caught up." />
      )}

      {data && data.length > 0 && (
        <div className="stagger space-y-2">
          {data.map(n => (
            <Card key={n.id} padding="md" className={n.status === 'READ' ? 'opacity-60' : ''}>
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant={n.status === 'READ' ? 'default' : 'info'}>{n.kind}</Badge>
                    {n.priority === 'URGENT' && <Badge variant="danger">Urgent</Badge>}
                    <span className="text-xs text-gray-400">{new Date(n.createdAt).toLocaleString()}</span>
                  </div>
                  {n.body && <p className="text-sm text-gray-800 dark:text-gray-200">{n.body}</p>}
                  {typeof n.context?.reason === 'string' && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">Why: {n.context.reason}</p>
                  )}
                  {(n.sourceAgentName || n.sourceAgentId) && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">from {n.sourceAgentName ?? n.sourceAgentId}</p>
                  )}
                </div>
                {n.status === 'PENDING' && (
                  <Button size="sm" variant="ghost" onClick={() => markRead.mutate(n.id)}>
                    Mark read
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
