import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Spinner } from '@/components/ui/Spinner'
import { Badge } from '@/components/ui/Badge'
import { agentsApi } from '@/api/agents'
import { guardrailsApi } from '@/api/guardrails'
import { ModeSelect } from './ModeSelect'
import { ACTION_LABELS, MODE_BADGE, MODE_LABELS } from './labels'
import type { GuardrailDecision, GuardrailMode, GuardrailStep, WorkflowGuardrails as Data } from '@/types'

/**
 * Who maintains a workflow, and what each step will do under each of those agents' guardrails, on
 * the version shown. The first agent picked leads: its guardrails judge every run. Setting a step
 * to Auto lets it go on without asking. A step's mode covers this version only: an edited
 * workflow is asked about again.
 */
export function WorkflowGuardrails({ workflowId }: { workflowId: string }) {
  const qc = useQueryClient()
  const key = ['guardrails', 'workflow', workflowId]
  const { data, isLoading, error } = useQuery({ queryKey: key, queryFn: () => guardrailsApi.workflow(workflowId) })
  const { data: agentList } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const agents = (agentList ?? []).filter(a => a.status !== 'archived')
    .sort((a, b) => Number(b.orchestrator) - Number(a.orchestrator))

  const save = useMutation({
    mutationFn: ({ step, agentId, mode }: { step: GuardrailStep; agentId: string; mode: GuardrailMode }) => {
      const existing = data?.rules.find(r => r.nodeId === step.nodeId && r.workflowVersion === data.version
        && (r.agentId ?? null) === agentId)
      return guardrailsApi.saveRule({
        id: existing?.id, agentId,
        // A device or web step only learns its device when the run opens the browser, so its rule
        // covers whatever this step calls.
        toolRef: step.type === 'DEVICE_ACTION' ? '*' : step.toolName ?? '*', rule: mode,
        limits: (existing?.limits as Record<string, unknown> | null) ?? null, precedence: 0,
        workflowId, nodeId: step.nodeId,
      })
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })
  const maintain = useMutation({
    mutationFn: (agentIds: string[]) => guardrailsApi.setMaintainers(workflowId, agentIds),
    onSuccess: result => qc.setQueryData(key, result),
  })

  if (isLoading) return <div className="flex justify-center py-4"><Spinner /></div>
  if (error) return <p className="text-sm text-red-500">{error.message}</p>
  if (!data) return null

  const chosen = (data.maintainers ?? []).map(m => m.id)
  const toggle = (id: string, on: boolean) =>
    maintain.mutate(on ? [...chosen, id] : chosen.filter(x => x !== id))
  const judged = data.steps.filter(s => s.toolName || s.decision)
  const agentSteps = data.steps.filter(s => !s.toolName && !s.decision)

  return (
    <div className="space-y-3">
      <Maintainers data={data} agents={agents} chosen={chosen} busy={maintain.isPending} onToggle={toggle} />
      {maintain.error && <p className="text-sm text-red-500">{maintain.error.message}</p>}

      {data.steps.length === 0 ? (
        <p className="text-xs text-gray-500 dark:text-gray-400">This workflow has no steps that act on your apps or devices.</p>
      ) : (
        <>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            Guardrails for version {data.version}. Set a step to Auto to let it go on without asking.
            If the workflow changes, its steps are asked about again.
          </p>
          {save.error && <p className="text-sm text-red-500">{save.error.message}</p>}
          {judged.map(step => (
            <div key={step.nodeId} className="flex flex-col gap-2 rounded-lg border border-gray-100 p-2.5 dark:border-gray-800">
              <div className="min-w-0">
                <p className="text-sm text-gray-800 dark:text-gray-200">{step.label || step.toolName || step.nodeId}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {step.actionType ? ACTION_LABELS[step.actionType].label : 'Unknown action'}
                  {step.type === 'DEVICE_ACTION' ? ' · on your device' : ''}
                </p>
              </div>
              {(step.perAgent?.length ? step.perAgent : [{ agentId: step.agentId ?? '', agentName: step.agentName, decision: step.decision! }])
                .filter(row => row.decision)
                .map((row, i) => (
                  <AgentRow key={row.agentId || i} name={row.agentName ?? 'Jizza'} lead={i === 0}
                    decision={row.decision} busy={save.isPending}
                    onChange={mode => save.mutate({ step, agentId: row.agentId, mode })} />
                ))}
            </div>
          ))}
          {agentSteps.map(step => (
            <div key={step.nodeId} className="rounded-lg border border-gray-100 p-2.5 dark:border-gray-800">
              <p className="text-sm text-gray-800 dark:text-gray-200">{step.label || step.nodeId}</p>
              <p className="text-xs text-gray-500 dark:text-gray-400">
                {step.agentName ?? 'An agent'} decides its tools as it runs; each is checked then
              </p>
            </div>
          ))}
        </>
      )}
    </div>
  )
}

function Maintainers({ data, agents, chosen, busy, onToggle }: {
  data: Data
  agents: { id: string; name: string; orchestrator: boolean }[]
  chosen: string[]
  busy: boolean
  onToggle: (id: string, on: boolean) => void
}) {
  const leadName = data.maintainers?.[0]?.name
  return (
    <div className="rounded-lg border border-gray-100 p-2.5 dark:border-gray-800">
      <p className="text-sm font-medium text-gray-900 dark:text-gray-100">Maintained by</p>
      <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">
        {chosen.length === 0
          ? 'No agent chosen, so Jizza (or the agent that made it) runs it under its guardrails.'
          : `${leadName ?? 'The first agent'} leads: its guardrails decide each run. The others' rules are shown so you can set them too.`}
      </p>
      <div className="flex flex-wrap gap-2" role="group" aria-label="Agents that maintain this workflow">
        {agents.map(a => {
          const index = chosen.indexOf(a.id)
          const on = index >= 0
          return (
            <label key={a.id} className={`flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1 text-sm ${on ? 'border-brand-400 bg-brand-50 text-brand-800 dark:bg-brand-500/10 dark:text-brand-200' : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'}`}>
              <input type="checkbox" className="sr-only" disabled={busy} checked={on} onChange={e => onToggle(a.id, e.target.checked)} />
              {a.name}{a.orchestrator ? ' (chat)' : ''}{index === 0 ? ' · leads' : ''}
            </label>
          )
        })}
      </div>
    </div>
  )
}

function AgentRow({ name, lead, decision, busy, onChange }: {
  name: string; lead: boolean; decision: GuardrailDecision; busy: boolean; onChange: (mode: GuardrailMode) => void
}) {
  const fixed = decision.reason.includes('no longer exists') || decision.reason.includes('no action called')
  return (
    <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <p className="text-xs text-gray-700 dark:text-gray-300">{name}{lead ? ' (leads)' : ''}</p>
        <p className="text-xs text-gray-400">{decision.reason}</p>
      </div>
      {fixed
        ? <Badge variant={MODE_BADGE[decision.rule]}>{MODE_LABELS[decision.rule]}</Badge>
        : <ModeSelect label={`${name}: step mode`} value={decision.rule} disabled={busy} onChange={onChange} />}
    </div>
  )
}
