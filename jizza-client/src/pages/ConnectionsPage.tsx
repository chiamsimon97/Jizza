import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plug } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { connectionsApi } from '@/api/connections'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function ConnectionsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['connections'],
    queryFn: () => connectionsApi.list(),
  })
  const revoke = useMutation({
    mutationFn: (id: string) => connectionsApi.revoke(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['connections'] }),
  })
  const testConn = useMutation({
    mutationFn: (id: string) => connectionsApi.test(id),
  })

  return (
    <div className="page">
      <PageHeader icon={Plug} title="Connections" description="Services Jizza can reach on your behalf." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Plug} title="No connections" description="Connect external services to give agents more capabilities." />
      )}

      {data && data.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {data.map(conn => (
            <Card key={conn.id} padding="md">
              <div className="flex items-start justify-between gap-2">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{conn.serviceName}</p>
                  <p className="text-xs text-gray-500 dark:text-gray-400">{conn.authScheme}</p>
                </div>
                <Badge variant={conn.status === 'ACTIVE' ? 'success' : conn.status === 'ERROR' ? 'danger' : 'default'}>
                  {conn.status}
                </Badge>
              </div>
              <div className="mt-3 flex gap-2">
                <Button size="sm" variant="secondary" onClick={() => testConn.mutate(conn.id)}>
                  Test
                </Button>
                <Button size="sm" variant="danger" onClick={() => revoke.mutate(conn.id)}>
                  Revoke
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
