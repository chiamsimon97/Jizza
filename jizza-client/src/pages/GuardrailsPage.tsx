import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock, Moon, Search, Shield, SlidersHorizontal, Trash2 } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { LoadError } from '@/components/ui/EmptyState'
import { guardrailsApi, type RuleInput } from '@/api/guardrails'
import { ModeSelect } from '@/components/guardrails/ModeSelect'
import { LimitsDialog } from '@/components/guardrails/LimitsDialog'
import {
  ACTION_LABELS, ACTION_TYPES, MODE_BADGE, MODE_LABELS, describeLimits, describeScope, tokenFor,
} from '@/components/guardrails/labels'
import type {
  ActionType, AgentGuardrails, AutonomyRuleDto, GuardrailDecision, GuardrailLimits, GuardrailMode, GuardrailsOverview,
} from '@/types'

const OVERVIEW_KEY = ['guardrails'] as const

/** The agent's own rule for one kind of action, outside any workflow, if it has one. */
function agentRule(rules: AutonomyRuleDto[], agentId: string, type: ActionType) {
  return rules.find(r => r.agentId === agentId && !r.workflowId && r.toolRef === tokenFor(type))
}

export function GuardrailsPage() {
  const qc = useQueryClient()
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: OVERVIEW_KEY, queryFn: guardrailsApi.overview })
  const refresh = () => qc.invalidateQueries({ queryKey: OVERVIEW_KEY })

  const saveRule = useMutation({ mutationFn: (rule: RuleInput) => guardrailsApi.saveRule(rule), onSuccess: refresh })
  const deleteRule = useMutation({ mutationFn: (id: string) => guardrailsApi.deleteRule(id), onSuccess: refresh })
  const setPaused = useMutation({ mutationFn: (paused: boolean) => guardrailsApi.setPaused(paused), onSuccess: refresh })

  const [editing, setEditing] = useState<{ agent: AgentGuardrails; type: ActionType } | null>(null)

  const names = useMemo(() => new Map(data?.agents.map(a => [a.id, a.name]) ?? []), [data])
  const agentName = (id: string) => names.get(id) ?? 'An agent'

  const changeMode = (agent: AgentGuardrails, type: ActionType, mode: GuardrailMode) => {
    const existing = agentRule(data?.rules ?? [], agent.id, type)
    saveRule.mutate({
      id: existing?.id, agentId: agent.id, toolRef: tokenFor(type), rule: mode,
      limits: (existing?.limits as Record<string, unknown> | null) ?? null,
      precedence: existing?.precedence ?? 10, locked: existing?.locked ?? false,
    })
  }

  const saveLimits = (limits: GuardrailLimits | null) => {
    if (!editing) return
    const { agent, type } = editing
    const existing = agentRule(data?.rules ?? [], agent.id, type)
    saveRule.mutate({
      id: existing?.id, agentId: agent.id, toolRef: tokenFor(type),
      rule: existing?.rule ?? agent.modes[type].rule, limits: limits as Record<string, unknown> | null,
      precedence: existing?.precedence ?? 10, locked: existing?.locked ?? false,
    }, { onSuccess: () => setEditing(null) })
  }

  return (
    <div className="page">
      <PageHeader icon={Shield} title="Guardrails"
        description="Decide what each agent may do on its own, what it asks you first, and what it never does." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {isError && <LoadError what="your guardrails" onRetry={() => void refetch()} />}

      {data && (
        <div className="space-y-6">
          <PauseCard paused={data.paused} busy={setPaused.isPending} onToggle={() => setPaused.mutate(!data.paused)} />

          <p className="text-sm text-gray-500 dark:text-gray-400">{data.defaultRule}</p>

          {saveRule.error && <p className="text-sm text-red-500">{saveRule.error.message}</p>}

          <section className="space-y-3">
            {data.agents.map(agent => (
              <AgentCard key={agent.id} agent={agent} rules={data.rules} busy={saveRule.isPending}
                onMode={(type, mode) => changeMode(agent, type, mode)}
                onLimits={type => setEditing({ agent, type })} />
            ))}
          </section>

          <RulesList rules={data.rules} agentName={agentName}
            onDelete={id => deleteRule.mutate(id)} deleting={deleteRule.isPending} />

          <WhatIfCard overview={data} />

          <QuietHoursCard overview={data} onSaved={refresh} />
        </div>
      )}

      <LimitsDialog
        open={editing != null}
        title={editing ? `${editing.agent.name}: ${ACTION_LABELS[editing.type].label.toLowerCase()}` : ''}
        limits={editing ? agentRule(data?.rules ?? [], editing.agent.id, editing.type)?.limits : null}
        onClose={() => setEditing(null)}
        onSave={saveLimits}
        saving={saveRule.isPending}
      />
    </div>
  )
}

function PauseCard({ paused, busy, onToggle }: { paused: boolean; busy: boolean; onToggle: () => void }) {
  return (
    <Card padding="md">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-gray-900 dark:text-gray-100">Pause all agents</p>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
            Running work stops at its next step and nothing new starts until you resume.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant={paused ? 'danger' : 'success'}>{paused ? 'Paused' : 'Running'}</Badge>
          <Button size="sm" variant={paused ? 'secondary' : 'danger'} onClick={onToggle} loading={busy}>
            {paused ? 'Resume' : 'Pause'}
          </Button>
        </div>
      </div>
    </Card>
  )
}

interface AgentCardProps {
  agent: AgentGuardrails
  rules: AutonomyRuleDto[]
  busy: boolean
  onMode: (type: ActionType, mode: GuardrailMode) => void
  onLimits: (type: ActionType) => void
}

function AgentCard({ agent, rules, busy, onMode, onLimits }: AgentCardProps) {
  return (
    <Card padding="md">
      <div className="mb-3 flex items-center gap-2">
        <p className="font-medium text-gray-900 dark:text-gray-100">{agent.name}</p>
        {agent.orchestrator && <Badge variant="info">Jizza</Badge>}
        {!agent.enabled && <Badge>Off</Badge>}
      </div>
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {ACTION_TYPES.map(type => {
          const cell = agent.modes[type]
          const own = agentRule(rules, agent.id, type)
          const limits = describeLimits(own?.limits)
          const looser = cell.rule === 'ALLOW' || cell.rule === 'JIZZA'
          return (
            <div key={type} className="flex flex-col gap-2 py-2.5 sm:flex-row sm:items-center sm:justify-between">
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 text-sm text-gray-800 dark:text-gray-200">
                  {ACTION_LABELS[type].label}
                  {cell.locked && <Lock aria-label="Locked" className="h-3.5 w-3.5 text-gray-400" />}
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {limits.length > 0 ? limits.join(' · ') : ACTION_LABELS[type].hint}
                </p>
                {cell.rule === 'JIZZA' && (type === 'MONEY' || type === 'DELETE') && (
                  <p className="text-xs text-amber-600 dark:text-amber-400">
                    Jizza decides this only with a per-call limit or condition and a budget; until then you are asked.
                  </p>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <ModeSelect label={`${agent.name}: ${ACTION_LABELS[type].label}`} value={cell.rule} disabled={busy}
                  exclude={agent.orchestrator ? ['JIZZA'] : []} onChange={mode => onMode(type, mode)} />
                <Button size="sm" variant="ghost" aria-label={`Limits for ${ACTION_LABELS[type].label}`}
                  title="Limits and conditions" disabled={busy || !looser} onClick={() => onLimits(type)}>
                  <SlidersHorizontal className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </Card>
  )
}

function RulesList({ rules, agentName, onDelete, deleting }: {
  rules: AutonomyRuleDto[]; agentName: (id: string) => string; onDelete: (id: string) => void; deleting: boolean
}) {
  if (rules.length === 0) return null
  return (
    <section>
      <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">All rules</h2>
      <div className="space-y-2">
        {rules.map(rule => (
          <Card key={rule.id} padding="sm">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm text-gray-900 dark:text-gray-100">{describeScope(rule, agentName)}</p>
                {describeLimits(rule.limits).length > 0 && (
                  <p className="text-xs text-gray-500 dark:text-gray-400">{describeLimits(rule.limits).join(' · ')}</p>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {rule.locked && <Badge>Locked</Badge>}
                <Badge variant={MODE_BADGE[rule.rule]}>{MODE_LABELS[rule.rule]}</Badge>
                <Button size="sm" variant="ghost" aria-label="Remove rule" disabled={deleting}
                  onClick={() => rule.id && onDelete(rule.id)}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </Card>
        ))}
      </div>
    </section>
  )
}

function WhatIfCard({ overview }: { overview: GuardrailsOverview }) {
  const [agentId, setAgentId] = useState(overview.agents.find(a => !a.orchestrator)?.id ?? overview.agents[0]?.id ?? '')
  const [toolRef, setToolRef] = useState('')
  const [args, setArgs] = useState('{}')
  const [error, setError] = useState<string | null>(null)
  const check = useMutation({ mutationFn: guardrailsApi.simulate })

  const run = () => {
    setError(null)
    let parsed: unknown
    try {
      parsed = args.trim() ? JSON.parse(args) : {}
    } catch {
      setError('The arguments need to be JSON, such as {"amount": 300}')
      return
    }
    check.mutate({ agentId, toolRef: toolRef.trim(), args: parsed })
  }

  const result: GuardrailDecision | undefined = check.data
  return (
    <Card padding="md">
      <div className="mb-3 flex items-center gap-2">
        <Search className="h-4 w-4 text-gray-400" />
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">What would happen?</p>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm font-medium text-gray-700 dark:text-gray-300">
          Agent
          <select className="rounded-lg border border-gray-300 bg-white px-2 py-2 text-sm font-normal dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            value={agentId} onChange={e => setAgentId(e.target.value)}>
            {overview.agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </label>
        <Input label="Tool" value={toolRef} onChange={e => setToolRef(e.target.value)} placeholder="broker_place_order" />
      </div>
      <label className="mt-2 flex flex-col gap-1 text-sm font-medium text-gray-700 dark:text-gray-300">
        Arguments
        <textarea rows={3} value={args} onChange={e => setArgs(e.target.value)}
          className="rounded-lg border border-gray-300 bg-white px-3 py-2 font-mono text-xs font-normal dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100" />
      </label>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button size="sm" onClick={run} loading={check.isPending} disabled={!agentId || !toolRef.trim()}>Check</Button>
        {error && <span className="text-sm text-red-500">{error}</span>}
        {check.error && <span className="text-sm text-red-500">{check.error.message}</span>}
        {result && (
          <span className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
            <Badge variant={MODE_BADGE[result.rule]}>{MODE_LABELS[result.rule]}</Badge>
            {result.reason}
          </span>
        )}
      </div>
    </Card>
  )
}

function QuietHoursCard({ overview, onSaved }: { overview: GuardrailsOverview; onSaved: () => void }) {
  const zone = overview.quietHours?.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC'
  const [start, setStart] = useState(overview.quietHours?.start?.slice(0, 5) ?? '')
  const [end, setEnd] = useState(overview.quietHours?.end?.slice(0, 5) ?? '')
  const save = useMutation({
    mutationFn: () => guardrailsApi.saveQuietHours({ start: start || null, end: end || null, timeZone: zone }),
    onSuccess: onSaved,
  })
  return (
    <Card padding="md">
      <div className="mb-1 flex items-center gap-2">
        <Moon className="h-4 w-4 text-gray-400" />
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">Quiet hours</p>
      </div>
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">
        During these hours only urgent approvals, such as an order before the market closes, reach you outside the app. The rest wait until you open Jizza. Times are in {zone}.
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <Input label="From" type="time" value={start} onChange={e => setStart(e.target.value)} />
        <Input label="Until" type="time" value={end} onChange={e => setEnd(e.target.value)} />
        <Button size="sm" onClick={() => save.mutate()} loading={save.isPending}>Save</Button>
        {save.error && <span className="text-sm text-red-500">{save.error.message}</span>}
      </div>
    </Card>
  )
}
