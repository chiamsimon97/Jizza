import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bot, Brain, LayoutGrid, Network, Plus } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { agentsApi } from '@/api/agents'
import { tasksApi } from '@/api/tasks'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { AgentLogo, AGENT_ACTIVITY, type AgentState } from '@/components/brand/AgentLogo'
import { AgentBadges } from '@/components/agents/agentParts'
import { useAssistantStore } from '@/jizza/assistantStore'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import type { AgentDto, TaskDto, TeamNode } from '@/types'

type View = 'cards' | 'team' | 'archived'

export function AgentsPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const view = (params.get('view') as View) ?? 'cards'
  const setView = (v: View) => setParams(v === 'cards' ? {} : { view: v })
  const canCreate = usePermission(PERMISSIONS.AGENT_CREATE)
  const { data, isLoading } = useQuery({
    queryKey: ['agents', view === 'archived' ? 'archived' : 'current'],
    queryFn: () => agentsApi.list(view === 'archived' ? 'archived' : undefined),
  })
  // What is running right now drives each agent's logo.
  const { data: tasks } = useQuery({
    queryKey: ['tasks'],
    queryFn: () => tasksApi.list(),
    refetchInterval: 15_000,
  })
  const { data: updates } = useQuery({ queryKey: ['agent-templates', 'updates'], queryFn: () => agentsApi.templateUpdates() })
  const patch = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      agentsApi.patch(id, { enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['agents'] }),
  })

  return (
    <div className="page">
      <PageHeader icon={Bot} title="Agents" description="Your team. Each logo shows what that agent is doing right now."
        actions={<div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-lg bg-gray-100 p-1 dark:bg-gray-800" role="tablist" aria-label="View">
            {([['cards', 'Cards', LayoutGrid], ['team', 'Team chart', Network], ['archived', 'Archived', null]] as const).map(([v, label, Icon]) => (
              <button key={v} type="button" role="tab" aria-selected={view === v} onClick={() => setView(v)}
                className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs font-medium ${view === v ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-700 dark:text-gray-100' : 'text-gray-600 dark:text-gray-300'}`}>
                {Icon && <Icon className="h-3.5 w-3.5" />}{label}
              </button>
            ))}
          </div>
          {canCreate && <Button onClick={() => navigate('/agents/new')}><Plus className="h-4 w-4" /> New agent</Button>}
        </div>} />

      {updates && updates.length > 0 && (
        <div className="mb-4 rounded-lg border border-brand-200 bg-brand-50/50 px-3 py-2 text-sm text-brand-800 dark:border-brand-800 dark:bg-brand-500/5 dark:text-brand-200">
          Your organization updated {updates.length === 1 ? `the template ${updates[0].agentName} came from` : `templates ${updates.length} of your agents came from`}.{' '}
          <button type="button" className="underline" onClick={() => navigate(`/agents/${updates[0].agentId}`)}>Review the update</button>
        </div>
      )}

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        view === 'archived'
          ? <EmptyState icon={Bot} title="Nothing archived" description="Archived agents keep their memory and history, and can be restored." />
          : <EmptyState icon={Bot} title="No agents configured" description="Agents appear here once the system is set up." />
      )}

      {view === 'team' && <TeamChart />}

      {view !== 'team' && data && data.length > 0 && (
        <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {[...data].sort((a, b) => Number(b.orchestrator) - Number(a.orchestrator) || Number(!!a.custom) - Number(!!b.custom)).map(agent => (
            <AgentCard key={agent.id} agent={agent} tasks={tasks ?? []}
              onToggle={() => patch.mutate({ id: agent.id, enabled: !agent.enabled })} />
          ))}
        </div>
      )}
    </div>
  )
}

function AgentCard({ agent, tasks, onToggle }: { agent: AgentDto; tasks: TaskDto[]; onToggle: () => void }) {
  const [hovered, setHovered] = useState(false)
  const navigate = useNavigate()
  const jizzaThinking = useAssistantStore(s => s.thinking)
  const running = tasks.filter(t => t.agentId === agent.id && t.status === 'RUNNING')
  const busy = running.length > 0 || (agent.orchestrator && jizzaThinking)
  const status = agent.status ?? 'active'
  const off = !agent.enabled || status !== 'active'
  const state: AgentState = off ? 'off' : busy || hovered ? 'working' : 'idle'
  const activity = AGENT_ACTIVITY[agent.key]
  const line = status === 'draft' ? 'Draft: not on the team yet'
    : status === 'archived' ? 'Archived'
    : !agent.enabled ? 'Paused'
    : running.length > 0 ? `Working on: ${running[0].title}`
    : busy ? activity?.working ?? 'Working' : activity?.idle ?? agent.description ?? agent.domain ?? 'Ready'

  return (
    <Card padding="lg" interactive onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
      onClick={() => navigate(`/agents/${agent.id}`)}
      className={`flex flex-col items-center text-center ${agent.orchestrator ? 'ring-1 ring-brand-500/30' : ''}`}>
      <AgentLogo agentKey={agent.key} avatar={agent.avatar} state={state} size={72} />
      <p className="mt-4 text-base font-semibold text-gray-900 dark:text-gray-100">{agent.name}</p>
      <p className="text-xs uppercase tracking-wider text-gray-400 dark:text-gray-500">
        {agent.orchestrator ? 'Your assistant' : agent.custom ? 'Made by you' : agent.domain ?? agent.key}
      </p>
      <p className={`mt-3 line-clamp-2 min-h-[2.5rem] text-sm ${busy && !off ? 'text-brand-600 dark:text-brand-300' : 'text-gray-500 dark:text-gray-400'}`}>
        {line}
      </p>
      <div className="mt-4 flex flex-wrap items-center justify-center gap-2" onClick={e => e.stopPropagation()}>
        {status === 'active' && (
          <Badge variant={!agent.enabled ? 'default' : busy ? 'info' : 'success'}>
            {!agent.enabled ? 'Paused' : busy ? 'Working' : 'Ready'}
          </Badge>
        )}
        <AgentBadges agent={{ ...agent, enabled: true }} />
        <Button size="sm" variant="ghost" onClick={() => navigate(`/memory/${agent.id}`)}>
          <Brain className="h-3.5 w-3.5" /> Memory
        </Button>
        {!agent.orchestrator && status === 'active' && (
          <Button size="sm" variant="secondary" onClick={onToggle}>
            {agent.enabled ? 'Pause' : 'Resume'}
          </Button>
        )}
      </div>
    </Card>
  )
}

/** The org chart: who reports to whom, drawn top-down with Jizza at the head. */
function TeamChart() {
  const navigate = useNavigate()
  const { data: tree, isLoading } = useQuery({ queryKey: ['agents', 'tree'], queryFn: () => agentsApi.tree() })
  if (isLoading || !tree) return <div className="flex justify-center py-16"><Spinner /></div>
  const size = countAgents(tree) - 1
  return (
    <div className="rounded-xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
      <div className="overflow-x-auto px-4 py-8">
        <div className="org-tree mx-auto w-max min-w-full">
          <ul><OrgNode node={tree} onOpen={id => navigate(`/agents/${id}`)} /></ul>
        </div>
      </div>
      <p className="border-t border-gray-100 px-4 py-3 text-xs text-gray-400 dark:border-gray-800">
        {size} {size === 1 ? 'agent reports' : 'agents report'} up to {tree.agent.name}. Teams go at most four levels below Jizza. Move an agent from its page, under Team.
      </p>
    </div>
  )
}

function countAgents(node: TeamNode): number {
  return 1 + node.reports.reduce((n, r) => n + countAgents(r), 0)
}

function OrgNode({ node, onOpen }: { node: TeamNode; onOpen: (id: string) => void }) {
  const [open, setOpen] = useState(true)
  const a = node.agent
  const head = node.depth === 0
  const role = a.orchestrator ? 'Head of the team' : a.custom ? 'Made by you' : a.domain ?? a.key
  const team = countAgents(node) - 1
  return (
    <li>
      <div className="flex flex-col items-center">
        <button type="button" onClick={() => onOpen(a.id)} title={a.description ?? undefined}
          className={`flex flex-col items-center rounded-xl border bg-white px-3 py-3 text-center shadow-sm transition hover:-translate-y-0.5 hover:shadow-md dark:bg-gray-900 ${
            head ? 'w-44 border-brand-300 ring-2 ring-brand-500/20 dark:border-brand-700' : 'w-36 border-gray-200 dark:border-gray-700'}`}>
          <AgentLogo agentKey={a.key} avatar={a.avatar} size={head ? 56 : 40} state={a.enabled ? 'idle' : 'off'} />
          <span className={`mt-2 w-full truncate font-semibold text-gray-900 dark:text-gray-100 ${head ? 'text-base' : 'text-sm'}`}>{a.name}</span>
          <span className="w-full truncate text-[11px] uppercase tracking-wider text-gray-400 dark:text-gray-500">{role}</span>
          {!a.enabled && <span className="mt-1 text-[11px] text-gray-400">Paused</span>}
        </button>
        {team > 0 && !head && (
          <button type="button" onClick={() => setOpen(o => !o)}
            className="relative z-10 -mt-2.5 rounded-full border border-gray-200 bg-white px-2 py-0.5 text-[11px] text-gray-500 hover:text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-400 dark:hover:text-gray-200">
            {open ? 'Hide' : 'Show'} {team}
          </button>
        )}
      </div>
      {open && node.reports.length > 0 && (
        <ul>{node.reports.map(r => <OrgNode key={r.agent.id} node={r} onOpen={onOpen} />)}</ul>
      )}
    </li>
  )
}
