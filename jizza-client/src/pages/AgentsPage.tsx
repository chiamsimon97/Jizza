import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bot, Brain } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { agentsApi } from '@/api/agents'
import { tasksApi } from '@/api/tasks'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { AgentLogo, AGENT_ACTIVITY, type AgentState } from '@/components/brand/AgentLogo'
import { useAssistantStore } from '@/jizza/assistantStore'
import type { AgentDto, TaskDto } from '@/types'

export function AgentsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['agents'],
    queryFn: () => agentsApi.list(),
  })
  // What is running right now drives each agent's logo.
  const { data: tasks } = useQuery({
    queryKey: ['tasks'],
    queryFn: () => tasksApi.list(),
    refetchInterval: 15_000,
  })
  const patch = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      agentsApi.patch(id, { enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['agents'] }),
  })

  return (
    <div className="page">
      <PageHeader icon={Bot} title="Agents" description="Your team. Each logo shows what that agent is doing right now." />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {!isLoading && (!data || data.length === 0) && (
        <EmptyState icon={Bot} title="No agents configured" description="Agents appear here once the system is set up." />
      )}

      {data && data.length > 0 && (
        <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {[...data].sort((a, b) => Number(b.isOrchestrator) - Number(a.isOrchestrator)).map(agent => (
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
  const busy = running.length > 0 || (agent.isOrchestrator && jizzaThinking)
  const state: AgentState = !agent.enabled ? 'off' : busy || hovered ? 'working' : 'idle'
  const activity = AGENT_ACTIVITY[agent.key]
  const line = !agent.enabled
    ? 'Paused'
    : running.length > 0
      ? `Working on: ${running[0].title}`
      : busy ? activity?.working ?? 'Working' : activity?.idle ?? agent.domain ?? 'Ready'

  return (
    <Card padding="lg" interactive onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
      className={`flex flex-col items-center text-center ${agent.isOrchestrator ? 'ring-1 ring-brand-500/30' : ''}`}>
      <AgentLogo agentKey={agent.key} state={state} size={72} />
      <p className="mt-4 text-base font-semibold text-gray-900 dark:text-gray-100">{agent.name}</p>
      <p className="text-xs uppercase tracking-wider text-gray-400 dark:text-gray-500">
        {agent.isOrchestrator ? 'Your assistant' : agent.domain ?? agent.key}
      </p>
      <p className={`mt-3 min-h-[2.5rem] text-sm ${busy && agent.enabled ? 'text-brand-600 dark:text-brand-300' : 'text-gray-500 dark:text-gray-400'}`}>
        {line}
      </p>
      <div className="mt-4 flex items-center gap-2">
        <Badge variant={!agent.enabled ? 'default' : busy ? 'info' : 'success'}>
          {!agent.enabled ? 'Paused' : busy ? 'Working' : 'Ready'}
        </Badge>
        <Button size="sm" variant="ghost" onClick={() => navigate(`/memory/${agent.id}`)}>
          <Brain className="h-3.5 w-3.5" /> Memory
        </Button>
        {!agent.isOrchestrator && (
          <Button size="sm" variant="secondary" onClick={onToggle}>
            {agent.enabled ? 'Pause' : 'Resume'}
          </Button>
        )}
      </div>
    </Card>
  )
}
