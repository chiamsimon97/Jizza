import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Bot, FlaskConical, KeyRound, LogIn, LogOut, Plus, Power, RefreshCw, Server, Trash2, Wrench } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { ACTION_LABELS, ACTION_TYPES, MODE_BADGE, MODE_LABELS } from '@/components/guardrails/labels'
import { mcpApi } from '@/api/mcp'
import { agentsApi } from '@/api/agents'
import type { ActionType, AgentDto, McpAuthType, McpServer, McpServerInput, McpTool, ModeLine, ToolRunResult } from '@/types'
import { Empty, JsonBlock, SectionHeader, Table, errorText } from './shared'
import { Can } from '@/auth/Can'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'

export const MCP_AUTH_LABELS: Record<McpAuthType, string> = {
  NONE: 'No sign-in, or the server signs you in itself',
  BEARER: 'Bearer token',
  HEADER: 'API key in a header',
  OAUTH: 'OAuth (sign in with the service)',
}

/** Servers people commonly add, filled in with one click. */
const PRESETS: Array<{ name: string; url: string; authType: McpAuthType; agentKeys: string[]; note: string }> = [
  { name: 'Zerodha Kite', url: 'https://mcp.kite.trade/mcp', authType: 'NONE', agentKeys: ['jizza', 'finance'],
    note: 'Ask Jizza or Kai to log in to Kite first: its login tool gives you a link to sign in with Zerodha.' },
]

/** The agents a server can be given to: Jizza first, then the specialists. */
function useLiveAgents() {
  const { data } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  return (data ?? []).filter(a => a.status !== 'archived')
    .sort((a, b) => Number(b.orchestrator) - Number(a.orchestrator))
}

/** One chip per agent; picked ones get the server's tools. */
function AgentChips({ agents, keys, disabled, onChange }: { agents: AgentDto[]; keys: string[]; disabled?: boolean; onChange: (key: string, on: boolean) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {agents.map(a => {
        const on = keys.includes(a.key)
        return (
          <label key={a.id} className={`flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1 text-sm ${on ? 'border-brand-400 bg-brand-50 text-brand-800 dark:bg-brand-500/10 dark:text-brand-200' : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'}`}>
            <input type="checkbox" className="sr-only" disabled={disabled} checked={on} onChange={e => onChange(a.key, e.target.checked)} />
            {a.name}{a.orchestrator ? ' (chat)' : ''}
          </label>
        )
      })}
    </div>
  )
}

/** The names of the agents a server is given to, for a card or a list. */
export function agentNames(server: McpServer, agents: AgentDto[]): string {
  if (server.agentKeys.includes('*')) return 'Every agent'
  const names = server.agentKeys.map(k => agents.find(a => a.key === k)?.name).filter(Boolean)
  return names.length ? names.join(', ') : 'No agent yet'
}

export function McpStatusBadge({ server }: { server: McpServer }) {
  if (!server.enabled) return <Badge>Off</Badge>
  switch (server.status) {
    case 'connected': return <Badge variant="success">Connected</Badge>
    case 'needs_auth': return <Badge variant="warning">Sign in needed</Badge>
    case 'error': return <Badge variant="danger">Can't reach it</Badge>
    default: return <Badge>Not checked yet</Badge>
  }
}

/** The MCP servers on the Connectors page, with a way to add one. */
export function McpServersSection() {
  const navigate = useNavigate()
  const [adding, setAdding] = useState(false)
  const { data, isLoading } = useQuery({ queryKey: ['mcp-servers'], queryFn: () => mcpApi.list() })
  const canCreate = usePermission(PERMISSIONS.CONNECTOR_CREATE)
  const agents = useLiveAgents()

  return (
    <section className="mt-10">
      <SectionHeader title="MCP servers"
        description="Add any remote MCP server, such as Zerodha Kite, by its URL. Its tools become available to the agents you choose, in chat and as workflow steps, under your guardrails."
        action={<Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" variant="secondary" onClick={() => setAdding(true)}><Plus className="h-4 w-4" />Add MCP server</Button></Can>} />

      {isLoading && <div className="flex justify-center py-8"><Spinner /></div>}
      {!isLoading && (!data || data.length === 0) && (
        <Empty>
          No MCP servers yet.{canCreate && <> <button type="button" className="text-brand-600 hover:underline dark:text-brand-300" onClick={() => setAdding(true)}>Add one</button>, such as Zerodha Kite at https://mcp.kite.trade/mcp.</>}
        </Empty>
      )}
      {data && data.length > 0 && (
        <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {data.map(server => (
            <Card key={server.id} interactive padding="lg" className="cursor-pointer" onClick={() => navigate(`/connectors/mcp/${server.id}`)}>
              <div className="flex items-start gap-3">
                <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-200">
                  <Server className="h-5 w-5" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold text-gray-900 dark:text-gray-100">{server.name}</p>
                  <p className="truncate text-xs text-gray-500 dark:text-gray-400">{server.url}</p>
                </div>
                <McpStatusBadge server={server} />
              </div>
              <div className="mt-4 flex flex-wrap gap-3 text-xs text-gray-500 dark:text-gray-400">
                <span className="inline-flex items-center gap-1"><Wrench className="h-3.5 w-3.5" />{server.tools.filter(t => t.enabled).length} of {server.tools.length} tools on</span>
                <span className="inline-flex items-center gap-1"><KeyRound className="h-3.5 w-3.5" />{server.authType === 'NONE' ? 'No sign-in' : server.authType === 'OAUTH' ? 'OAuth' : 'Token'}</span>
                <span className="inline-flex items-center gap-1"><Bot className="h-3.5 w-3.5" />{agentNames(server, agents)}</span>
              </div>
            </Card>
          ))}
        </div>
      )}

      <NewMcpServerForm open={adding} onClose={() => setAdding(false)} onCreated={s => navigate(`/connectors/mcp/${s.id}`)} />
    </section>
  )
}

function AuthFields({ value, onChange, editing }: { value: McpServerInput; onChange: (v: McpServerInput) => void; editing?: McpServer }) {
  const auth = value.authType ?? 'NONE'
  const keep = editing?.secretPresent && editing.authType === auth ? 'Leave empty to keep the current one' : undefined
  return <>
    <Field label="How it signs in">
      <select className={fieldClass} value={auth} onChange={e => onChange({ ...value, authType: e.target.value as McpAuthType })}>
        {(Object.keys(MCP_AUTH_LABELS) as McpAuthType[]).map(k => <option key={k} value={k}>{MCP_AUTH_LABELS[k]}</option>)}
      </select>
    </Field>
    {auth === 'HEADER' && (
      <Input label="Header name" value={value.headerName ?? editing?.headerName ?? ''} placeholder="X-API-Key"
        onChange={e => onChange({ ...value, headerName: e.target.value })} />
    )}
    {(auth === 'BEARER' || auth === 'HEADER') && (
      <Input label={auth === 'BEARER' ? 'Token' : 'Key'} type="password" autoComplete="off" value={value.secret ?? ''}
        hint={keep ?? 'Stored encrypted. It is sent only to this server.'} onChange={e => onChange({ ...value, secret: e.target.value })} />
    )}
    {auth === 'OAUTH' && <>
      <p className="text-xs text-gray-500 dark:text-gray-400">Jizza finds the server's sign-in page and registers itself. Fill these in only if the service gave you an app of your own.</p>
      <Input label="Client id (optional)" value={value.oauthClientId ?? ''} onChange={e => onChange({ ...value, oauthClientId: e.target.value })} />
      <Input label="Client secret (optional)" type="password" autoComplete="off" value={value.oauthClientSecret ?? ''} onChange={e => onChange({ ...value, oauthClientSecret: e.target.value })} />
      <Input label="Scopes (optional)" value={value.oauthScopes ?? ''} placeholder="read write" onChange={e => onChange({ ...value, oauthScopes: e.target.value })} />
    </>}
  </>
}

function NewMcpServerForm({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (s: McpServer) => void }) {
  const qc = useQueryClient()
  const [value, setValue] = useState<McpServerInput>({ authType: 'NONE' })
  const [note, setNote] = useState<string | null>(null)
  const agents = useLiveAgents()
  const keys = value.agentKeys ?? ['jizza']
  const create = useMutation({
    mutationFn: () => mcpApi.create({ ...value, agentKeys: keys.length ? keys : ['jizza'] }),
    onSuccess: async created => {
      await qc.invalidateQueries({ queryKey: ['mcp-servers'] })
      setValue({ authType: 'NONE' })
      setNote(null)
      onClose()
      onCreated(created)
    },
  })
  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate()
  }
  return (
    <SlideOver open={open} title="Add an MCP server" description="A remote server that speaks the Model Context Protocol, the same kind Claude connects to." onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button type="submit" form="new-mcp-server" loading={create.isPending}>Add server</Button>
      </>}>
      <form id="new-mcp-server" onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-2">
          {PRESETS.map(p => (
            <button key={p.name} type="button" onClick={() => {
              setValue({ name: p.name, url: p.url, authType: p.authType, agentKeys: p.agentKeys.filter(k => k === 'jizza' || agents.some(a => a.key === k)) })
              setNote(p.note)
            }}
              className="rounded-full border border-gray-200 px-3 py-1 text-xs text-gray-700 hover:border-brand-400 hover:text-brand-700 dark:border-gray-700 dark:text-gray-300">
              {p.name}
            </button>
          ))}
        </div>
        <Input label="Name" required value={value.name ?? ''} onChange={e => setValue({ ...value, name: e.target.value })} placeholder="Zerodha Kite"
          hint="Its tools reach agents as mcp_<name>__<tool>." />
        <Input label="Server URL" required value={value.url ?? ''} onChange={e => setValue({ ...value, url: e.target.value })} placeholder="https://mcp.example.com/mcp"
          hint="The server's streamable HTTP endpoint. It must be https." />
        <AuthFields value={value} onChange={setValue} />
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Who can use it</span>
          <AgentChips agents={agents} keys={keys}
            onChange={(key, on) => setValue({ ...value, agentKeys: on ? [...keys.filter(k => k !== key), key] : keys.filter(k => k !== key) })} />
          <span className="text-xs text-gray-400">Its tools are offered to these agents in chat, each under its own guardrails. Jizza has it unless you choose otherwise.</span>
        </div>
        {note && <p className="rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-800 dark:bg-brand-500/10 dark:text-brand-200">{note}</p>}
        <p className="text-xs text-gray-500 dark:text-gray-400">Jizza suggests what each of its tools does from its name (looks things up, places orders…), so each agent's guardrails apply at once: a lookup runs under its rule for looking things up. You can change any of them.</p>
        {create.error && <p className="text-sm text-red-500">{errorText(create.error)}</p>}
      </form>
    </SlideOver>
  )
}

/** One MCP server: sign-in, the agents that may use it, and its tools. */
export function McpServerPage() {
  const { mcpId = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const queryKey = ['mcp-server', mcpId]
  const { data: server, isLoading, error } = useQuery({ queryKey, queryFn: () => mcpApi.get(mcpId) })
  const [editing, setEditing] = useState(false)
  const [testing, setTesting] = useState<McpTool | null>(null)
  const [authError, setAuthError] = useState<string | null>(null)

  const put = (s: McpServer) => {
    qc.setQueryData(queryKey, s)
    qc.invalidateQueries({ queryKey: ['mcp-servers'] })
  }
  const refresh = useMutation({ mutationFn: () => mcpApi.refresh(mcpId), onSuccess: put })
  const toggle = useMutation({ mutationFn: () => mcpApi.update(mcpId, { enabled: !server?.enabled }), onSuccess: put })
  const signOut = useMutation({ mutationFn: () => mcpApi.signOut(mcpId), onSuccess: put })
  const remove = useMutation({
    mutationFn: () => mcpApi.remove(mcpId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['mcp-servers'] })
      navigate('/connectors', { replace: true })
    },
  })
  async function signIn() {
    setAuthError(null)
    try {
      const { url } = await mcpApi.authorize(mcpId)
      window.open(url, '_blank', 'noopener')
    } catch (e) {
      setAuthError(errorText(e))
    }
  }

  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (error || !server) return <div className="page"><p className="text-sm text-red-500">{errorText(error) || 'This MCP server was not found.'}</p></div>

  return (
    <div className="page">
      <Link to="/connectors" className="mb-4 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200">
        <ArrowLeft className="h-4 w-4" />Connectors
      </Link>
      <div className="mb-6 flex flex-wrap items-start gap-3">
        <div className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-200"><Server className="h-5 w-5" /></div>
        <div className="min-w-0 flex-1">
          <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold text-gray-900 dark:text-gray-100">{server.name}<McpStatusBadge server={server} /></h1>
          <p className="break-all text-sm text-gray-500 dark:text-gray-400">{server.url}</p>
          {server.serverName && <p className="text-xs text-gray-400">Reports itself as {server.serverName}{server.serverVersion ? ` ${server.serverVersion}` : ''}</p>}
        </div>
        <div className="flex flex-wrap gap-2">
          <Can permission={PERMISSIONS.CONNECTOR_MODIFY}>
            <Button size="sm" variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate()}><RefreshCw className="h-4 w-4" />Check tools</Button>
            {server.authType === 'OAUTH' && (server.signedIn
              ? <Button size="sm" variant="secondary" loading={signOut.isPending} onClick={() => signOut.mutate()}><LogOut className="h-4 w-4" />Sign out</Button>
              : <Button size="sm" onClick={signIn}><LogIn className="h-4 w-4" />Sign in</Button>)}
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}><KeyRound className="h-4 w-4" />Settings</Button>
            <Button size="sm" variant="ghost" loading={toggle.isPending} onClick={() => toggle.mutate()}><Power className="h-4 w-4" />{server.enabled ? 'Turn off' : 'Turn on'}</Button>
          </Can>
          <Can permission={PERMISSIONS.CONNECTOR_DELETE}>
            <Button size="sm" variant="danger" loading={remove.isPending} onClick={() => { if (confirm(`Remove ${server.name}? Workflow steps that use it will stop working.`)) remove.mutate() }}><Trash2 className="h-4 w-4" /></Button>
          </Can>
        </div>
      </div>

      {server.lastError && <p className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-300">{server.lastError}</p>}
      {server.status === 'needs_auth' && <p className="mb-4 rounded-lg bg-yellow-50 px-3 py-2 text-sm text-yellow-800 dark:bg-yellow-500/10 dark:text-yellow-200">Sign in to {server.name} so Jizza can use it. After signing in in the new tab, come back here; the tools appear on their own.</p>}
      {authError && <p className="mb-4 text-sm text-red-500">{authError}</p>}
      {[refresh.error, toggle.error, signOut.error, remove.error].filter(Boolean).map((e, i) => <p key={i} className="mb-4 text-sm text-red-500">{errorText(e)}</p>)}

      <AgentsCard server={server} onSaved={put} />
      <ToolsCard server={server} onSaved={put} onTest={setTesting} />

      <SettingsForm server={server} open={editing} onClose={() => setEditing(false)} onSaved={put} />
      <TestTool server={server} tool={testing} onClose={() => setTesting(null)} />
    </div>
  )
}

/** Which agents may use the server. Jizza is the one you chat with; the others are your specialists. */
function AgentsCard({ server, onSaved }: { server: McpServer; onSaved: (s: McpServer) => void }) {
  const agents = useLiveAgents()
  const save = useMutation({ mutationFn: (keys: string[]) => mcpApi.update(server.id, { agentKeys: keys }), onSuccess: onSaved })
  const canModify = usePermission(PERMISSIONS.CONNECTOR_MODIFY)
  const all = server.agentKeys.includes('*')
  const set = (key: string, on: boolean) => {
    const next = on ? [...server.agentKeys.filter(k => k !== key), key] : server.agentKeys.filter(k => k !== key)
    save.mutate(next)
  }
  return (
    <Card padding="lg" className="mb-6">
      <SectionHeader title="Who can use it" description="Its tools are offered to these agents in chat, each under its own guardrails. Workflow steps can use it whichever you pick." />
      <label className="mb-3 flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
        <input type="checkbox" disabled={!canModify || save.isPending} checked={all} onChange={e => save.mutate(e.target.checked ? ['*'] : ['jizza'])} />
        Every agent
      </label>
      {!all && <AgentChips agents={agents} keys={server.agentKeys} disabled={!canModify || save.isPending} onChange={set} />}
      {save.error && <p className="mt-2 text-sm text-red-500">{errorText(save.error)}</p>}
    </Card>
  )
}

function ToolsCard({ server, onSaved, onTest }: { server: McpServer; onSaved: (s: McpServer) => void; onTest: (t: McpTool) => void }) {
  const update = useMutation({
    mutationFn: ({ tool, body }: { tool: string; body: { enabled?: boolean; actionType?: ActionType } }) => mcpApi.updateTool(server.id, tool, body),
    onSuccess: onSaved,
  })
  const canModify = usePermission(PERMISSIONS.CONNECTOR_MODIFY)
  const agents = useLiveAgents()
  const given = server.agentKeys.includes('*') ? agents : agents.filter(a => server.agentKeys.includes(a.key))
  const modes = useQueries({
    queries: given.map(a => ({ queryKey: ['agent-modes', a.id], queryFn: () => agentsApi.modes(a.id) })),
  })
  const ruleFor = (i: number, type: ActionType | null | undefined): ModeLine | undefined =>
    modes[i]?.data?.find(l => l.actionType === (type ?? 'MONEY'))
  return (
    <Card padding="lg">
      <SectionHeader title="Tools"
        description="Turn off what you don't want used, and check what each tool does: each agent's guardrails go by that. Jizza suggests it from the tool's name; change any that are wrong." />
      {server.tools.some(t => t.enabled && !t.actionType) && (
        <p className="mb-4 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
          {server.tools.filter(t => t.enabled && !t.actionType).length} tools don't say what they do yet. Until you choose, each is treated as moving money, so by default Jizza won't use it.
        </p>
      )}
      {server.tools.length === 0 && <Empty>{server.status === 'needs_auth' ? 'Sign in to see the tools.' : 'No tools yet. Press Check tools to ask the server.'}</Empty>}
      {server.tools.length > 0 && (
        <Table head={['Tool', 'On', 'What it does', 'Under the guardrails', '']}>
          {server.tools.map(t => (
            <tr key={t.name} className={t.enabled ? '' : 'opacity-60'}>
              <td className="px-4 py-3 align-top">
                <p className="font-medium text-gray-900 dark:text-gray-100">{t.title || t.name}</p>
                <p className="font-mono text-[11px] text-gray-400">{t.name}</p>
                {t.description && <p className="mt-1 line-clamp-2 max-w-md text-xs text-gray-500 dark:text-gray-400">{t.description}</p>}
              </td>
              <td className="px-4 py-3 align-top">
                <input type="checkbox" aria-label={`Use ${t.name}`} disabled={!canModify || update.isPending} checked={t.enabled}
                  onChange={e => update.mutate({ tool: t.name, body: { enabled: e.target.checked } })} />
              </td>
              <td className="px-4 py-3 align-top">
                <select className={`${fieldClass} min-w-[11rem]`} aria-label={`What ${t.name} does`} disabled={!canModify || update.isPending}
                  value={t.actionType ?? ''} onChange={e => e.target.value && update.mutate({ tool: t.name, body: { actionType: e.target.value as ActionType } })}>
                  {!t.actionType && <option value="">Not set: treated as moving money</option>}
                  {ACTION_TYPES.map(a => <option key={a} value={a}>{ACTION_LABELS[a].label}</option>)}
                </select>
                {t.suggested && <p className="mt-1 text-[11px] text-gray-400">Suggested from its name.</p>}
                {t.serverHint && <p className="mt-1 text-[11px] text-gray-400">The server says it is {t.serverHint}.</p>}
              </td>
              <td className="px-4 py-3 align-top">
                <ul className="flex flex-col gap-1">
                  {given.map((a, i) => {
                    const line = ruleFor(i, t.actionType)
                    return (
                      <li key={a.id} className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
                        <span className="min-w-[4rem]">{a.name}</span>
                        {line ? <Badge variant={MODE_BADGE[line.rule]}>{MODE_LABELS[line.rule]}</Badge> : <span className="text-gray-400">…</span>}
                      </li>
                    )
                  })}
                  {given.length === 0 && <li className="text-xs text-gray-400">No agent has it</li>}
                </ul>
              </td>
              <td className="px-4 py-3 text-right align-top">
                <Can permission={PERMISSIONS.CONNECTOR_MODIFY}>
                  <Button size="sm" variant="ghost" onClick={() => onTest(t)}><FlaskConical className="h-4 w-4" />Try</Button>
                </Can>
              </td>
            </tr>
          ))}
        </Table>
      )}
      {update.error && <p className="mt-2 text-sm text-red-500">{errorText(update.error)}</p>}
    </Card>
  )
}

function SettingsForm({ server, open, onClose, onSaved }: { server: McpServer; open: boolean; onClose: () => void; onSaved: (s: McpServer) => void }) {
  const [value, setValue] = useState<McpServerInput>({})
  const current: McpServerInput = { name: server.name, url: server.url, authType: server.authType, ...value }
  const save = useMutation({
    mutationFn: () => mcpApi.update(server.id, value),
    onSuccess: s => { onSaved(s); setValue({}); onClose() },
  })
  return (
    <SlideOver open={open} title={`${server.name} settings`} onClose={() => { setValue({}); onClose() }}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button type="submit" form="mcp-settings" loading={save.isPending}>Save</Button>
      </>}>
      <form id="mcp-settings" onSubmit={e => { e.preventDefault(); save.mutate() }} className="flex flex-col gap-4">
        <Input label="Name" value={current.name ?? ''} onChange={e => setValue({ ...value, name: e.target.value })}
          hint={`Its tools keep the name mcp_${server.slug}__…, so workflows keep working.`} />
        <Input label="Server URL" value={current.url ?? ''} onChange={e => setValue({ ...value, url: e.target.value })} />
        <AuthFields value={current} onChange={v => setValue({ ...value, ...diff(current, v) })} editing={server} />
        <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={value.redactArguments ?? server.redactArguments} onChange={e => setValue({ ...value, redactArguments: e.target.checked })} />
          Keep what is sent to it out of the activity log
        </label>
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </form>
    </SlideOver>
  )
}

function diff(before: McpServerInput, after: McpServerInput): McpServerInput {
  const out: McpServerInput = {}
  for (const k of Object.keys(after) as Array<keyof McpServerInput>) {
    if (after[k] !== before[k]) (out as Record<string, unknown>)[k] = after[k]
  }
  return out
}

/** Runs one tool by hand with the arguments you type, to see what it gives back. */
function TestTool({ server, tool, onClose }: { server: McpServer; tool: McpTool | null; onClose: () => void }) {
  const [args, setArgs] = useState('{}')
  const [result, setResult] = useState<ToolRunResult | null>(null)
  const run = useMutation({
    mutationFn: () => mcpApi.testTool(server.id, tool!.name, JSON.parse(args || '{}')),
    onSuccess: setResult,
  })
  const close = () => { setArgs('{}'); setResult(null); onClose() }
  const properties = (tool?.inputSchema?.properties ?? {}) as Record<string, { description?: string; type?: string }>
  return (
    <SlideOver open={tool != null} title={`Try ${tool?.title || tool?.name || ''}`} description="This really runs the tool, as you." onClose={close} wide
      footer={<>
        <Button variant="ghost" onClick={close}>Close</Button>
        <Button loading={run.isPending} onClick={() => run.mutate()}>Run</Button>
      </>}>
      <div className="flex flex-col gap-4">
        {Object.keys(properties).length > 0 && (
          <ul className="text-xs text-gray-500 dark:text-gray-400">
            {Object.entries(properties).map(([k, p]) => <li key={k}><code className="text-gray-800 dark:text-gray-200">{k}</code>{p.type ? ` (${p.type})` : ''}{p.description ? `: ${p.description}` : ''}</li>)}
          </ul>
        )}
        <Field label="Arguments (JSON)">
          <textarea className={`${fieldClass} font-mono`} rows={6} value={args} onChange={e => setArgs(e.target.value)} />
        </Field>
        {run.error && <p className="text-sm text-red-500">{errorText(run.error)}</p>}
        {result && (result.ok ? <JsonBlock value={result.output} /> : <p className="text-sm text-red-500">{result.error}</p>)}
      </div>
    </SlideOver>
  )
}
