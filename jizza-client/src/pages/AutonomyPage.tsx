import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Shield } from 'lucide-react'
import { autonomyApi } from '@/api/autonomy'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'

export function AutonomyPage() {
  const qc = useQueryClient()
  const { data: killSwitch } = useQuery({
    queryKey: ['killSwitch'],
    queryFn: () => autonomyApi.killSwitch(),
  })
  const { data: rules, isLoading } = useQuery({
    queryKey: ['autonomyRules'],
    queryFn: () => autonomyApi.rules(),
  })
  const toggleKill = useMutation({
    mutationFn: (enabled: boolean) => autonomyApi.setKillSwitch(enabled),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['killSwitch'] }),
  })

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center gap-2">
        <Shield className="h-5 w-5 text-brand-500" />
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Autonomy</h1>
      </div>

      <Card padding="md" className="mb-6">
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-gray-900 dark:text-gray-100">Kill Switch</p>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">
              Immediately suspend all autonomous agent actions.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <Badge variant={killSwitch?.enabled ? 'danger' : 'success'}>
              {killSwitch?.enabled ? 'ACTIVE — agents paused' : 'Off'}
            </Badge>
            <Button
              size="sm"
              variant={killSwitch?.enabled ? 'secondary' : 'danger'}
              onClick={() => toggleKill.mutate(!killSwitch?.enabled)}
            >
              {killSwitch?.enabled ? 'Resume' : 'Engage'}
            </Button>
          </div>
        </div>
      </Card>

      <h2 className="mb-3 text-sm font-semibold text-gray-700 dark:text-gray-300">Autonomy Rules</h2>

      {isLoading && <div className="flex justify-center py-8"><Spinner /></div>}

      {!isLoading && (!rules || rules.length === 0) && (
        <EmptyState icon={Shield} title="No rules defined" description="Add rules to control what agents can do autonomously." />
      )}

      {rules && rules.length > 0 && (
        <div className="space-y-2">
          {rules.map(rule => (
            <Card key={rule.id} padding="md">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{rule.toolRef ?? rule.agentId ?? 'Global'}</p>
                  <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">precedence: {rule.precedence}</p>
                </div>
                <Badge variant={rule.rule === 'ALLOW' ? 'success' : rule.rule === 'DENY' ? 'danger' : 'warning'}>
                  {rule.rule}
                </Badge>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
