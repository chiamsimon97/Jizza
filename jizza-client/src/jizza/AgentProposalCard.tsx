import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Bot, Check, Pencil, ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { Button } from '@/components/ui/Button'
import { ACTION_LABELS } from '@/components/guardrails/labels'
import { CREATED_PROFILE, errorText } from '@/components/agents/agentParts'
import type { ActionType, AgentProposal, GuardrailMode } from '@/types'

const SHOWN: ActionType[] = ['SEND', 'CREATE', 'UPDATE', 'DELETE', 'MONEY']

/**
 * A new agent, or a team, Jizza drafted in chat. Nothing exists until the user presses Create, or
 * opens it in the form to change it first; creating goes through the same checks as the form.
 */
export function AgentProposalCard({ proposal }: { proposal: AgentProposal }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [created, setCreated] = useState<string[] | null>(null)
  const [error, setError] = useState('')
  const team = proposal.agents.length > 1

  const create = useMutation({
    mutationFn: () => agentsApi.createTeam({ definition: { agents: proposal.agents }, parentAgentId: proposal.parentAgentId }),
    onSuccess: agents => {
      void qc.invalidateQueries({ queryKey: ['agents'] })
      setCreated(agents.map(a => a.id))
    },
    onError: (e: unknown) => setError(errorText(e, 'Could not create it')),
  })

  return (
    <div className="rounded-2xl border border-brand-300 bg-white p-4 shadow-sm animate-fade-in-up dark:border-brand-700 dark:bg-gray-900">
      <div className="flex items-start gap-2.5">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300">
          <Bot className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{team ? `A team of ${proposal.agents.length}` : proposal.agents[0].name}</p>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{proposal.summary ?? `Reports to ${proposal.parentAgentName}`}</p>
        </div>
      </div>

      <ul className="mt-3 flex flex-col gap-3">
        {proposal.agents.map((a, i) => {
          const stops = { ...CREATED_PROFILE, ...(a.stops ?? {}) } as Record<ActionType, GuardrailMode>
          return (
            <li key={a.ref ?? i} className={a.reportsTo ? 'ml-5 border-l border-gray-200 pl-3 dark:border-gray-700' : ''}>
              <div className="flex items-center gap-2">
                <AgentLogo agentKey={`proposal-${i}`} avatar={a.avatar} size={28} />
                <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{a.name}</span>
              </div>
              <p className="mt-0.5 text-xs text-gray-600 dark:text-gray-400">{a.description}</p>
              <div className="mt-1.5 flex flex-wrap gap-1">
                {SHOWN.map(t => <Stop key={t} type={t} mode={stops[t]} locked={a.locks?.includes(t)} />)}
              </div>
            </li>
          )
        })}
      </ul>

      {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
      {created ? (
        <p className="mt-3 flex items-center gap-1.5 text-sm text-emerald-700 dark:text-emerald-300">
          <Check className="h-4 w-4" /> Created.{' '}
          <button type="button" className="underline" onClick={() => navigate(`/agents/${created[0]}`)}>Open {proposal.agents[0].name}</button>
        </p>
      ) : (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" loading={create.isPending} onClick={() => create.mutate()}><Check className="h-3.5 w-3.5" /> Create</Button>
          {!team && (
            <Button size="sm" variant="secondary" onClick={() => navigate('/agents/new', { state: { blueprint: proposal.agents[0], parentAgentId: proposal.parentAgentId } })}>
              <Pencil className="h-3.5 w-3.5" /> Change it first
            </Button>
          )}
        </div>
      )}
      <p className="mt-2 text-[11px] text-gray-400">Tools are added on its page afterwards. Nothing exists until you create it.</p>
    </div>
  )
}

function Stop({ type, mode, locked }: { type: ActionType; mode: GuardrailMode; locked?: boolean }) {
  const [Icon, tone, word] = mode === 'ALLOW'
    ? [ShieldCheck, 'text-emerald-700 bg-emerald-50 dark:text-emerald-300 dark:bg-emerald-500/10', 'on its own']
    : mode === 'DENY'
      ? [ShieldX, 'text-red-700 bg-red-50 dark:text-red-300 dark:bg-red-500/10', 'never']
      : [ShieldAlert, 'text-amber-700 bg-amber-50 dark:text-amber-300 dark:bg-amber-500/10', 'asks you']
  return (
    <span className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] ${tone}`}>
      <Icon className="h-3 w-3" />{ACTION_LABELS[type].label}: {word}{locked ? ' (locked)' : ''}
    </span>
  )
}
