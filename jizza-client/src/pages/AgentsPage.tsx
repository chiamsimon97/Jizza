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

/** The org chart: who reports to whom, starting at Jizza. */
function TeamChart() {
  const navigate = useNavigate()
  const { data: tree, isLoading } = useQuery({ queryKey: ['agents', 'tree'], queryFn: () => agentsApi.tree() })
  if (isLoading || !tree) return <div className="flex justify-center py-16"><Spinner /></div>
  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
      <TreeNode node={tree} onOpen={id => navigate(`/agents/${id}`)} />
      <p className="mt-4 text-xs text-gray-400">Teams go at most four levels below Jizza. Move an agent from its page, under Team.</p>
    </div>
  )
}

function TreeNode({ node, onOpen }: { node: TeamNode; onOpen: (id: string) => void }) {
  const a = node.agent
  return (
    <div className={node.depth > 0 ? 'ml-6 border-l border-gray-200 pl-4 dark:border-gray-700' : ''}>
      <button type="button" onClick={() => onOpen(a.id)}
        className="my-1 flex items-center gap-2 rounded-lg px-2 py-1 text-left hover:bg-gray-50 dark:hover:bg-gray-800">
        <AgentLogo agentKey={a.key} avatar={a.avatar} size={28} state={a.enabled ? 'idle' : 'off'} />
        <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{a.name}</span>
        <AgentBadges agent={a} />
        {a.description && !a.orchestrator && <span className="hidden max-w-xs truncate text-xs text-gray-500 sm:inline">{a.description}</span>}
      </button>
      {node.reports.map(r => <TreeNode key={r.agent.id} node={r} onOpen={onOpen} />)}
    </div>
  )
}
