import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bot } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function AgentsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['agents'],
    queryFn: () => agentsApi.list(),
  })
  const patch = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      agentsApi.patch(id, { enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['agents'] }),
  })

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <Bot className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Agents</h1>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Bot} title="No agents configured" description="Agents appear here once the system is set up." />
      )}

      {data && data.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {data.map(agent => (
            <Card key={agent.id} padding="md">
              <div className="flex items-start justify-between gap-2">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate">{agent.name}</p>
                  <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{agent.domain ?? agent.key}</p>
                  {agent.isOrchestrator && (
                    <p className="text-xs text-brand-500 mt-0.5">Orchestrator</p>
                  )}
                </div>
                <Badge variant={agent.enabled ? 'success' : 'default'}>
                  {agent.enabled ? 'Active' : 'Disabled'}
                </Badge>
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => patch.mutate({ id: agent.id, enabled: !agent.enabled })}
                >
                  {agent.enabled ? 'Disable' : 'Enable'}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
