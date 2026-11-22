import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, Clock, FlaskConical, LogIn, MonitorSmartphone, Plug, Server, Wrench } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { connectorHubApi } from '@/api/connectorHub'
import { servicesApi } from '@/api/services'
import { mcpApi } from '@/api/mcp'
import { devicesApi } from '@/api/devices'
import { agentsApi } from '@/api/agents'
import type { AgentDto, ConnectorCard, ConnectorHealth } from '@/types'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import { errorText } from './shared'

export const HUB_KEY = ['connector-hub']

/** Every service, MCP server and device as one list. */
export function useConnectorCards() {
  return useQuery({ queryKey: HUB_KEY, queryFn: () => connectorHubApi.list() })
}

/** The agents a connector can be given to: Jizza first, then the specialists. */
export function useLiveAgents(): AgentDto[] {
  const { data } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  return (data ?? []).filter(a => a.status !== 'archived')
    .sort((a, b) => Number(b.orchestrator) - Number(a.orchestrator))
}

const HEALTH: Record<ConnectorHealth, { label: string; variant: 'success' | 'warning' | 'danger' | 'default' }> = {
  ready: { label: 'Ready', variant: 'success' },
  needs_sign_in: { label: 'Needs sign-in', variant: 'warning' },
  failing: { label: 'Failing', variant: 'danger' },
  not_set_up: { label: 'Not set up yet', variant: 'default' },
  off: { label: 'Off', variant: 'default' },
  offline: { label: 'Offline', variant: 'default' },
}

const KIND: Record<ConnectorCard['kind'], { icon: LucideIcon; label: string; tint: string }> = {
  service: { icon: Plug, label: 'Service', tint: 'bg-brand-100 text-brand-700 dark:bg-brand-900/50 dark:text-brand-200' },
  mcp: { icon: Server, label: 'MCP server', tint: 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-200' },
  device: { icon: MonitorSmartphone, label: 'Device', tint: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200' },
}

export function HealthBadge({ status }: { status: ConnectorHealth }) {
  const h = HEALTH[status] ?? HEALTH.not_set_up
  return <Badge variant={h.variant}>{h.label}</Badge>
}

/** "12 tools: 9 read, 2 change things, 1 moves money", in as few words as fit. */
export function toolSummary(card: ConnectorCard): string {
  const noun = card.kind === 'service' ? 'action' : 'tool'
  if (card.toolCount === 0) return `No ${noun}s yet`
  const parts: string[] = []
  const t = card.types
  if (t.read) parts.push(`${t.read} read`)
  if (t.change) parts.push(`${t.change} change things`)
  if (t.money) parts.push(`${t.money} ${t.money === 1 ? 'moves' : 'move'} money`)
  if (t.unset) parts.push(`${t.unset} not typed yet`)
  return `${card.toolCount} ${noun}${card.toolCount === 1 ? '' : 's'}${parts.length ? `: ${parts.join(', ')}` : ''}`
}

/** Who may use a connector, by name. */
export function agentNamesOf(keys: string[], agents: AgentDto[]): string {
  if (keys.includes('*')) return 'Every agent'
  const names = keys.map(k => agents.find(a => a.key === k)?.name ?? (k === 'jizza' ? 'Jizza' : null)).filter(Boolean)
  return names.length ? names.join(', ') : 'No agent yet'
}

export function lastUsed(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'Not used yet'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return 'Used just now'
  if (s < 3600) return `Used ${Math.floor(s / 60)} min ago`
  if (s < 86400) return `Used ${Math.floor(s / 3600)} h ago`
  return `Used ${Math.floor(s / 86400)} d ago`
}

/** Reaches the connector once, the way its own page would, and says how it went in words. */
export async function testConnector(card: ConnectorCard): Promise<{ ok: boolean; message: string }> {
  switch (card.kind) {
    case 'service': {
      const r = await servicesApi.testAuth(card.id)
      return { ok: r.ok, message: r.ok ? 'It answered. Sign-in works.' : r.message || `It answered with an error (HTTP ${r.httpStatus}).` }
    }
    case 'mcp': {
      const s = await mcpApi.refresh(card.id)
      if (s.status === 'connected') return { ok: true, message: `It answered with ${s.tools.length} tools.` }
      if (s.status === 'needs_auth') return { ok: false, message: 'It wants you to sign in again.' }
      return { ok: false, message: s.lastError || "Jizza couldn't reach it." }
    }
    case 'device': {
      const c = await devicesApi.test(card.id)
      if (c.status === 'SUCCEEDED') return { ok: true, message: 'It answered.' }
      if (c.status === 'FAILED' || c.status === 'REJECTED') return { ok: false, message: c.error || 'It answered with an error.' }
      return { ok: false, message: "It didn't answer within a few seconds. Is the app running and online?" }
    }
  }
}

/** One connector, whatever its kind: health, tools, who may use it, last use, and a Test button. */
export function HubCard({ card }: { card: ConnectorCard }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const agents = useLiveAgents()
  const canModify = usePermission(PERMISSIONS.CONNECTOR_MODIFY)
  const [editing, setEditing] = useState(false)
  const kind = KIND[card.kind]
  const Icon = kind.icon

  const test = useMutation({
    mutationFn: () => testConnector(card),
    onSettled: () => { void qc.invalidateQueries({ queryKey: HUB_KEY }) },
  })
  const save = useMutation({
    mutationFn: (keys: string[]) => connectorHubApi.setAgents(card.kind, card.id, keys),
    onSuccess: updated => {
      qc.setQueryData<ConnectorCard[]>(HUB_KEY, list => list?.map(c => (c.kind === updated.kind && c.id === updated.id ? updated : c)))
      if (card.kind === 'mcp') void qc.invalidateQueries({ queryKey: ['mcp-servers'] })
    },
  })
  const all = card.agentKeys.includes('*')
  const toggle = (key: string, on: boolean) =>
    save.mutate(on ? [...card.agentKeys.filter(k => k !== key), key] : card.agentKeys.filter(k => k !== key))

  return (
    <Card padding="lg" className="flex flex-col">
      <button type="button" className="flex items-start gap-3 text-left" onClick={() => navigate(card.path)}>
        <div className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${kind.tint}`}><Icon className="h-5 w-5" /></div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-gray-900 hover:underline dark:text-gray-100">{card.name}</p>
          <p className="truncate text-xs text-gray-500 dark:text-gray-400">{kind.label}{card.detail ? ` · ${card.detail}` : ''}</p>
        </div>
        <HealthBadge status={card.status} />
      </button>

      {card.problem && <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">{card.problem}</p>}

      <ul className="mt-3 flex flex-col gap-1.5 text-xs text-gray-600 dark:text-gray-300">
        <li className="flex items-start gap-1.5"><Wrench className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />{toolSummary(card)}</li>
        <li className="flex items-start gap-1.5">
          <Bot className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />
          <span className="min-w-0 flex-1">
            <span>{agentNamesOf(card.agentKeys, agents)}</span>
            {card.partial && <span className="text-gray-400"> (some actions only)</span>}
            {canModify && (
              <button type="button" className="ml-2 font-medium text-brand-600 hover:underline dark:text-brand-300"
                onClick={() => setEditing(e => !e)} aria-expanded={editing}>
                {editing ? 'Done' : 'Change'}
              </button>
            )}
          </span>
        </li>
        <li className="flex items-start gap-1.5"><Clock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gray-400" />{lastUsed(card.lastUsedAt)}</li>
      </ul>

      {editing && (
        <div className="mt-3 rounded-xl border border-gray-200 p-3 dark:border-gray-800" role="group" aria-label={`Who can use ${card.name}`}>
          <label className="mb-2 flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
            <input type="checkbox" disabled={save.isPending} checked={all}
              onChange={e => save.mutate(e.target.checked ? ['*'] : ['jizza'])} />
            Every agent
          </label>
          {!all && (
            <div className="flex flex-wrap gap-1.5">
              {agents.map(a => {
                const on = card.agentKeys.includes(a.key)
                return (
                  <label key={a.id} className={`flex cursor-pointer items-center rounded-full border px-2.5 py-0.5 text-xs ${on ? 'border-brand-400 bg-brand-50 text-brand-800 dark:bg-brand-500/10 dark:text-brand-200' : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'}`}>
                    <input type="checkbox" className="sr-only" disabled={save.isPending} checked={on} onChange={e => toggle(a.key, e.target.checked)} />
                    {a.name}
                  </label>
                )
              })}
            </div>
          )}
          {card.kind === 'service' && <p className="mt-2 text-[11px] text-gray-400">Giving it to an agent gives every action. Choose single actions on the service's page.</p>}
          {save.error && <p className="mt-2 text-xs text-red-500">{errorText(save.error)}</p>}
        </div>
      )}

      <div className="mt-auto flex flex-wrap items-center gap-2 pt-4">
        {card.status === 'needs_sign_in' && (
          <Button size="sm" onClick={() => navigate(card.path)}><LogIn className="h-4 w-4" />Sign in again</Button>
        )}
        {canModify && card.status !== 'not_set_up' && card.status !== 'off' && (
          <Button size="sm" variant="secondary" loading={test.isPending} onClick={() => test.mutate()}><FlaskConical className="h-4 w-4" />Test</Button>
        )}
        {card.status === 'not_set_up' && (
          <Button size="sm" variant="secondary" onClick={() => navigate(card.path)}>Finish setting up</Button>
        )}
      </div>
      {test.data && (
        <p role="status" className={`mt-2 text-xs ${test.data.ok ? 'text-emerald-700 dark:text-emerald-300' : 'text-red-600 dark:text-red-400'}`}>{test.data.message}</p>
      )}
      {test.error && <p role="status" className="mt-2 text-xs text-red-600 dark:text-red-400">{errorText(test.error)}</p>}
    </Card>
  )
}

/** Cards in a grid, the ones that need you first. */
export function HubGrid({ cards }: { cards: ConnectorCard[] }) {
  const rank: Record<ConnectorHealth, number> = { needs_sign_in: 0, failing: 1, not_set_up: 2, offline: 3, ready: 4, off: 5 }
  const sorted = [...cards].sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9) || a.name.localeCompare(b.name))
  return (
    <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {sorted.map(c => <HubCard key={`${c.kind}-${c.id}`} card={c} />)}
    </div>
  )
}
