import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Hand, Pencil, ShieldAlert, ShieldCheck, ShieldX, Workflow } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { workflowsApi } from '@/api/workflows'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { Button } from '@/components/ui/Button'
import { toGraph } from '@/components/forms/WorkflowForm'
import type { WorkflowPlan, WorkflowPlanStep } from '@/types'
import { useAssistantStore } from './assistantStore'

const APPROVAL = {
  auto: { label: 'Runs on its own', icon: ShieldCheck, tone: 'text-emerald-700 bg-emerald-50 ring-emerald-500/20 dark:text-emerald-300 dark:bg-emerald-500/10' },
  ask: { label: 'Asks you first', icon: ShieldAlert, tone: 'text-amber-700 bg-amber-50 ring-amber-500/25 dark:text-amber-300 dark:bg-amber-500/10' },
  never: { label: 'Not allowed for this agent', icon: ShieldX, tone: 'text-red-700 bg-red-50 ring-red-500/20 dark:text-red-300 dark:bg-red-500/10' },
} as const

/**
 * A workflow Jizza planned, shown in the conversation for the user to approve. Nothing is saved
 * until they press Approve; then it is saved as a draft, which still has to be run or approved
 * before it does anything unattended.
 */
export function WorkflowPlanCard({ plan, onRevise }: { plan: WorkflowPlan; onRevise?: () => void }) {
  const savedId = useAssistantStore(s => s.savedPlans[plan.planId])
  const planSaved = useAssistantStore(s => s.planSaved)
  const qc = useQueryClient()
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list(), staleTime: 300_000 })
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: () => workflowsApi.save({
      name: plan.name,
      description: plan.description,
      graph: toGraph(plan.steps.map(s => ({ kind: s.kind, agentId: s.agentId ?? '', instruction: s.instruction }))),
    }),
    onSuccess: (wf) => {
      void qc.invalidateQueries({ queryKey: ['workflows'] })
      planSaved(plan.planId, wf.id, wf.name ?? plan.name)
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not save the workflow'),
  })

  const stops = plan.steps.filter(s => s.kind === 'ask' || (s.changesThings && s.approval !== 'auto')).length

  return (
    <div className="rounded-2xl border border-brand-300 bg-white p-4 shadow-sm animate-fade-in-up dark:border-brand-700 dark:bg-gray-900">
      <div className="flex items-start gap-2.5">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300">
          <Workflow className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{plan.name}</p>
          {plan.description && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{plan.description}</p>}
        </div>
      </div>

      <ol className="mt-3 flex flex-col gap-2">
        {plan.steps.map((step, i) => (
          <PlanStep key={i} index={i} step={step} agentKey={agents?.find(a => a.id === step.agentId)?.key ?? step.agentKey} />
        ))}
      </ol>

      {plan.assumptions && plan.assumptions.length > 0 && (
        <div className="mt-3 rounded-lg bg-gray-50 px-3 py-2 dark:bg-gray-950">
          <p className="text-xs font-medium text-gray-700 dark:text-gray-300">Jizza assumed</p>
          <ul className="mt-1 list-disc pl-4 text-xs text-gray-600 dark:text-gray-400">
            {plan.assumptions.map((a, i) => <li key={i}>{a}</li>)}
          </ul>
        </div>
      )}

      <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
        {stops === 0 ? 'Once running, this workflow will not need to stop for you.' : `This workflow will stop for you at ${stops} ${stops === 1 ? 'step' : 'steps'}.`}
        {' '}Approving saves it as a draft; nothing runs until you run it.
      </p>

      {savedId ? (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-emerald-700 dark:text-emerald-300">
          <Check className="h-4 w-4" /> Saved as a draft workflow
        </p>
      ) : (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" loading={save.isPending} onClick={() => { setError(''); save.mutate() }}>
            <Check className="h-3.5 w-3.5" /> Approve and save
          </Button>
          {onRevise && (
            <Button size="sm" variant="secondary" disabled={save.isPending} onClick={onRevise}>
              <Pencil className="h-3.5 w-3.5" /> Change something
            </Button>
          )}
        </div>
      )}
      {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
    </div>
  )
}

function PlanStep({ index, step, agentKey }: { index: number; step: WorkflowPlanStep; agentKey?: string }) {
  const approval = step.changesThings ? APPROVAL[step.approval ?? 'ask'] : null
  return (
    <li className="flex gap-2.5 rounded-xl border border-gray-200 bg-gray-50 p-2.5 dark:border-gray-800 dark:bg-gray-950">
      <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-brand-500 text-[11px] font-semibold text-white">{index + 1}</span>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 text-xs font-medium text-gray-800 dark:text-gray-200">
          {step.kind === 'ask'
            ? <><Hand className="h-3.5 w-3.5" /> Ask you</>
            : <><AgentLogo agentKey={agentKey ?? 'other'} size={18} /> {step.agentName ?? 'Agent'}</>}
        </p>
        <p className="mt-0.5 text-sm text-gray-900 dark:text-gray-100">{step.instruction}</p>
        {step.why && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{step.why}</p>}
        {approval && (
          <span className={`mt-1.5 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ${approval.tone}`}>
            <approval.icon className="h-3 w-3" /> Changes something · {approval.label}
          </span>
        )}
      </div>
    </li>
  )
}
