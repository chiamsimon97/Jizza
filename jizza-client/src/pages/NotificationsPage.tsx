import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
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
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <Bell className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Notifications</h1>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Bell} title="No notifications" description="You're all caught up." />
      )}

      {data && data.length > 0 && (
        <div className="space-y-2">
          {data.map(n => (
            <Card key={n.id} padding="md" className={n.status === 'READ' ? 'opacity-60' : ''}>
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant={n.status === 'READ' ? 'default' : 'info'}>{n.kind}</Badge>
                    <span className="text-xs text-gray-400">{new Date(n.createdAt).toLocaleString()}</span>
                  </div>
                  {n.sourceAgentId && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">from agent: {n.sourceAgentId}</p>
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
