import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Spinner } from '@/components/ui/Spinner'
import { Badge } from '@/components/ui/Badge'
import { guardrailsApi } from '@/api/guardrails'
import { ModeSelect } from './ModeSelect'
import { ACTION_LABELS, MODE_BADGE, MODE_LABELS } from './labels'
import type { GuardrailMode, GuardrailStep } from '@/types'

/**
 * What each step of a workflow will do under the guardrails, on the version shown. Changing a
 * step's mode covers this version only: an edited workflow is asked about again.
 */
export function WorkflowGuardrails({ workflowId }: { workflowId: string }) {
  const qc = useQueryClient()
  const key = ['guardrails', 'workflow', workflowId]
  const { data, isLoading, error } = useQuery({ queryKey: key, queryFn: () => guardrailsApi.workflow(workflowId) })
  const save = useMutation({
    mutationFn: ({ step, mode }: { step: GuardrailStep; mode: GuardrailMode }) => {
      const existing = data?.rules.find(r => r.nodeId === step.nodeId && r.workflowVersion === data.version)
      return guardrailsApi.saveRule({
        id: existing?.id, agentId: step.agentId ?? null, toolRef: step.toolName ?? '*', rule: mode,
        limits: (existing?.limits as Record<string, unknown> | null) ?? null, precedence: 0,
        workflowId, nodeId: step.nodeId,
      })
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })

  if (isLoading) return <div className="flex justify-center py-4"><Spinner /></div>
  if (error) return <p className="text-sm text-red-500">{error.message}</p>
  if (!data) return null
  if (data.steps.length === 0) {
    return <p className="text-xs text-gray-500 dark:text-gray-400">This workflow has no steps that act on your apps.</p>
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-gray-500 dark:text-gray-400">
        Guardrails for version {data.version}. If the workflow changes, these steps are asked about again.
      </p>
      {save.error && <p className="text-sm text-red-500">{save.error.message}</p>}
      {data.steps.map(step => (
        <div key={step.nodeId} className="flex flex-col gap-2 rounded-lg border border-gray-100 p-2.5 sm:flex-row sm:items-center sm:justify-between dark:border-gray-800">
          <div className="min-w-0">
            <p className="text-sm text-gray-800 dark:text-gray-200">{step.label || step.toolName || step.nodeId}</p>
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {step.toolName
                ? `${step.agentName ?? 'Jizza'} · ${step.actionType ? ACTION_LABELS[step.actionType].label.toLowerCase() : 'unknown action'}`
                : `${step.agentName ?? 'An agent'} decides its tools as it runs; each is checked then`}
            </p>
            {step.decision && <p className="text-xs text-gray-400">{step.decision.reason}</p>}
          </div>
          {step.toolName && step.decision && (
            step.decision.reason.includes('no longer exists')
              ? <Badge variant={MODE_BADGE[step.decision.rule]}>{MODE_LABELS[step.decision.rule]}</Badge>
              : <ModeSelect label={`Step ${step.label ?? step.nodeId}`} value={step.decision.rule} disabled={save.isPending}
                  onChange={mode => save.mutate({ step, mode })} />
          )}
        </div>
      ))}
    </div>
  )
}
