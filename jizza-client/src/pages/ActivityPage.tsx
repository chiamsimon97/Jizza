import { useQuery } from '@tanstack/react-query'
import { Activity } from 'lucide-react'
import { activityApi } from '@/api/activity'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function ActivityPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['activity'],
    queryFn: () => activityApi.recent(),
  })

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <Activity className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Activity Log</h1>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Activity} title="No activity" description="Security-relevant events are recorded here." />
      )}

      {data && data.length > 0 && (
        <div className="space-y-2">
          {data.map(entry => (
            <Card key={entry.id} padding="md">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <Badge variant="default">{entry.action}</Badge>
                    <span className="text-xs text-gray-400">{new Date(entry.createdAt).toLocaleString()}</span>
                  </div>
                  {entry.actorName && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">{entry.actorType}: {entry.actorName}</p>
                  )}
                  {entry.target && (
                    <p className="text-xs text-gray-500 dark:text-gray-400">target: {entry.target}</p>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
