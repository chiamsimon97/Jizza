import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock, Trash2 } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { Can } from '@/auth/Can'
import { PERMISSIONS } from '@/lib/permissions'
import { guardrailsApi } from '@/api/guardrails'
import { ACTION_LABELS, ACTION_TYPES, MODE_BADGE, MODE_LABELS, tokenFor } from '@/components/guardrails/labels'
import type { ActionType, GuardrailMode } from '@/types'

const KEY = ['guardrails', 'organization'] as const

/**
 * Locks an organization puts on its connectors. They bind every member's agents whenever they use
 * one of the organization's connectors, and nobody's own guardrails can loosen them.
 */
export function OrganizationGuardrailsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: KEY, queryFn: guardrailsApi.organizationLocks })
  const refresh = () => qc.invalidateQueries({ queryKey: KEY })
  const lock = useMutation({ mutationFn: guardrailsApi.lockForOrganization, onSuccess: refresh })
  const unlock = useMutation({ mutationFn: guardrailsApi.unlockForOrganization, onSuccess: refresh })
  const [type, setType] = useState<ActionType>('MONEY')
  const [mode, setMode] = useState<GuardrailMode>('ASK')

  const label = (ref?: string) => {
    const t = ref?.startsWith('*:') ? ref.slice(2).toUpperCase() as ActionType : null
    return t && ACTION_LABELS[t] ? ACTION_LABELS[t].label : ref === '*' ? 'Everything' : ref ?? ''
  }

  const select = 'rounded-lg border border-gray-300 bg-white px-2 py-2 text-sm dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100'

  return (
    <div className="page">
      <PageHeader icon={Lock} title="Guardrails"
        description="Locks on what any member's agents may do with this organization's connectors." />

      <Can permission={PERMISSIONS.GUARDRAILS_LOCK}>
        <Card padding="md" className="mb-6">
          <p className="mb-3 text-sm font-medium text-gray-900 dark:text-gray-100">Add a lock</p>
          <div className="flex flex-wrap items-center gap-2">
            <select aria-label="Kind of action" className={select} value={type} onChange={e => setType(e.target.value as ActionType)}>
              {ACTION_TYPES.filter(t => t !== 'READ' && t !== 'DRAFT').map(t => <option key={t} value={t}>{ACTION_LABELS[t].label}</option>)}
            </select>
            <select aria-label="Mode" className={select} value={mode} onChange={e => setMode(e.target.value as GuardrailMode)}>
              <option value="ASK">{MODE_LABELS.ASK}</option>
              <option value="DENY">{MODE_LABELS.DENY}</option>
            </select>
            <Button size="sm" loading={lock.isPending} onClick={() => lock.mutate({ toolRef: tokenFor(type), rule: mode })}>Lock</Button>
          </div>
          {lock.error && <p className="mt-2 text-sm text-red-500">{lock.error.message}</p>}
        </Card>
      </Can>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Lock} title="No locks yet" description="Members' own guardrails apply as they set them." />
      )}
      {data && data.length > 0 && (
        <div className="space-y-2">
          {data.map(rule => (
            <Card key={rule.id} padding="sm">
              <div className="flex items-center justify-between gap-3">
                <p className="text-sm text-gray-900 dark:text-gray-100">{label(rule.toolRef)}</p>
                <div className="flex items-center gap-2">
                  <Badge variant={MODE_BADGE[rule.rule]}>{MODE_LABELS[rule.rule]}</Badge>
                  <Can permission={PERMISSIONS.GUARDRAILS_LOCK}>
                    <Button size="sm" variant="ghost" aria-label="Remove lock" disabled={unlock.isPending}
                      onClick={() => rule.id && unlock.mutate(rule.id)}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </Can>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
