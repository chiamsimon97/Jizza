import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, FlaskConical, KeyRound, ListChecks, LogIn, LogOut, Play, Plus, Power, RefreshCw, Server, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { SlideOver, Field, fieldClass } from '@/components/ui/SlideOver'
import { ACTION_LABELS, ACTION_TYPES, MODE_BADGE, MODE_LABELS } from '@/components/guardrails/labels'
import { mcpApi } from '@/api/mcp'
import { agentsApi } from '@/api/agents'
import type { ActionType, AgentDto, McpAuthType, McpCatalogEntry, McpServer, McpServerInput, McpTool, ModeLine, ToolRunResult } from '@/types'
import { Empty, SectionHeader, Table, errorText } from './shared'
import { ResultView, schemaFields } from '@/components/connectors/ResultView'
import { SchemaForm, inputFields } from '@/components/connectors/SchemaForm'
import { Can } from '@/auth/Can'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import { useAuthorization } from '@/auth/useAuthorization'
import { McpCatalogSection } from './McpCatalog'

export const MCP_AUTH_LABELS: Record<McpAuthType, string> = {
  NONE: 'No sign-in, or the server signs you in itself',
  BEARER: 'Bearer token',
  HEADER: 'API key in a header',
  OAUTH: 'OAuth (sign in with the service)',
}

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

/** A server that signs you in inside its own session (Kite) forgets it whenever Jizza restarts. */
export function needsSessionSignIn(server: McpServer): boolean {
  return server.enabled && !!server.signInTool && !server.sessionSignInAt && server.status !== 'needs_auth' && server.status !== 'error'
}

export function McpStatusBadge({ server }: { server: McpServer }) {
  if (!server.enabled) return <Badge>Off</Badge>
  if (needsSessionSignIn(server)) return <Badge variant="warning">Sign in again</Badge>
  switch (server.status) {
    case 'connected': return <Badge variant="success">Connected</Badge>
    case 'needs_auth': return <Badge variant="warning">Sign in needed</Badge>
    case 'error': return <Badge variant="danger">Can't reach it</Badge>
    default: return <Badge>Not checked yet</Badge>
  }
}

/**
 * Adding an MCP server from the Connectors hub: the ready-made catalog, or any server by its URL.
 * A new server opens on its page with its tool types up for review.
 */
export function McpServersSection({ view = 'catalog' }: { view?: 'catalog' | 'url' }) {
  const navigate = useNavigate()
  const [adding, setAdding] = useState<{ preset: McpCatalogEntry | null; redirectUri: string | null } | null>(null)
  const { data } = useQuery({ queryKey: ['mcp-servers'], queryFn: () => mcpApi.list() })
  const canCreate = usePermission(PERMISSIONS.CONNECTOR_CREATE)
  const form = adding && <NewMcpServerForm key={adding.preset?.id ?? 'custom'} open preset={adding.preset} redirectUri={adding.redirectUri}
    onClose={() => setAdding(null)} onCreated={s => navigate(`/connectors/mcp/${s.id}?review=1`)} />

  if (view === 'url') {
    return (
      <Card padding="lg" className="flex flex-col">
        <div className="flex items-start gap-3">
          <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-200"><Server className="h-5 w-5" /></div>
          <div className="min-w-0 flex-1">
            <p className="font-semibold text-gray-900 dark:text-gray-100">An MCP server by its URL</p>
            <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">Any remote server that speaks the Model Context Protocol, the same kind Claude connects to. Its tools reach the agents you choose, under your guardrails.</p>
          </div>
        </div>
        <div className="mt-4">
          <Can permission={PERMISSIONS.CONNECTOR_CREATE}><Button size="sm" onClick={() => setAdding({ preset: null, redirectUri: null })}><Plus className="h-4 w-4" />Add by URL</Button></Can>
        </div>
        {form}
      </Card>
    )
  }
  return (
    <>
      <McpCatalogSection installed={data ?? []} canInstall={canCreate}
        onInstall={(preset, redirectUri) => setAdding({ preset, redirectUri })} />
      {form}
    </>
  )
}

function AuthFields({ value, onChange, editing, secretLabel, ownClient }: { value: McpServerInput; onChange: (v: McpServerInput) => void; editing?: McpServer; secretLabel?: string | null; ownClient?: boolean }) {
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
      <Input label={secretLabel ?? (auth === 'BEARER' ? 'Token' : 'Key')} required={!keep && !!secretLabel} type="password" autoComplete="off" value={value.secret ?? ''}
        hint={keep ?? 'Stored encrypted. It is sent only to this server.'} onChange={e => onChange({ ...value, secret: e.target.value })} />
    )}
    {auth === 'OAUTH' && <>
      <p className="text-xs text-gray-500 dark:text-gray-400">{ownClient
        ? 'This service asks for an OAuth client of your own. Paste its client id and secret here.'
        : "Jizza finds the server's sign-in page and registers itself. Fill these in only if the service gave you an app of your own."}</p>
      <Input label={ownClient ? 'Client id' : 'Client id (optional)'} required={ownClient && !editing} value={value.oauthClientId ?? ''} onChange={e => onChange({ ...value, oauthClientId: e.target.value })} />
      <Input label={ownClient ? 'Client secret' : 'Client secret (optional)'} required={ownClient && !editing} type="password" autoComplete="off" value={value.oauthClientSecret ?? ''} onChange={e => onChange({ ...value, oauthClientSecret: e.target.value })} />
      <Input label="Scopes (optional)" value={value.oauthScopes ?? ''} placeholder="read write" onChange={e => onChange({ ...value, oauthScopes: e.target.value })} />
    </>}
  </>
}

/** What the add form starts from: a catalog server's settings, or nothing for a server added by URL. */
function presetValue(preset: McpCatalogEntry | null, agents: AgentDto[]): McpServerInput {
  if (!preset) return { authType: 'NONE' }
  return {
    name: preset.name, url: preset.url, authType: preset.authType,
    headerName: preset.headerName ?? undefined, oauthScopes: preset.oauthScopes ?? undefined,
    agentKeys: preset.agentKeys.filter(k => k === 'jizza' || agents.length === 0 || agents.some(a => a.key === k)),
  }
}

function NewMcpServerForm({ open, onClose, onCreated, preset, redirectUri }: {
  open: boolean; onClose: () => void; onCreated: (s: McpServer) => void
  preset: McpCatalogEntry | null; redirectUri: string | null
}) {
  const qc = useQueryClient()
  const agents = useLiveAgents()
  const { data: snapshot } = useAuthorization()
  const [value, setValue] = useState<McpServerInput>(() => presetValue(preset, agents))
  const keys = (value.agentKeys ?? ['jizza']).filter(k => k === 'jizza' || agents.length === 0 || agents.some(a => a.key === k))
  const create = useMutation({
    mutationFn: () => mcpApi.create({ ...value, agentKeys: keys.length ? keys : ['jizza'] }),
    onSuccess: async created => {
      await qc.invalidateQueries({ queryKey: ['mcp-servers'] })
      onClose()
      onCreated(created)
    },
  })
  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate()
  }
  const account = snapshot?.activeWorkspace?.name
  return (
    <SlideOver open={open} title={preset ? `Install ${preset.name}` : 'Add an MCP server'}
      description={preset ? `${preset.description} From ${preset.publisher}.` : 'A remote server that speaks the Model Context Protocol, the same kind Claude connects to.'} onClose={onClose}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button type="submit" form="new-mcp-server" loading={create.isPending}>{preset ? 'Install' : 'Add server'}</Button>
      </>}>
      <form id="new-mcp-server" onSubmit={submit} className="flex flex-col gap-4">
        {account && <p className="text-xs text-gray-500 dark:text-gray-400">Installs into <span className="font-medium text-gray-700 dark:text-gray-200">{account}</span>. Switch account first to install it somewhere else.</p>}
        {preset?.note && <p className="rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-800 dark:bg-brand-500/10 dark:text-brand-200">{preset.note}</p>}
        {preset?.ownClient && redirectUri && (
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Jizza's sign-in address (redirect URI)</span>
            <code className="break-all rounded-lg bg-gray-100 px-3 py-2 text-xs text-gray-800 dark:bg-gray-800 dark:text-gray-200">{redirectUri}</code>
          </div>
        )}
        {preset?.docsUrl && <a href={preset.docsUrl} target="_blank" rel="noreferrer" className="text-xs text-brand-600 hover:underline dark:text-brand-300">{preset.publisher}'s setup guide</a>}
        <Input label="Name" required value={value.name ?? ''} onChange={e => setValue({ ...value, name: e.target.value })} placeholder="Zerodha Kite"
          hint="Its tools reach agents as mcp_<name>__<tool>." />
        <Input label="Server URL" required value={value.url ?? ''} onChange={e => setValue({ ...value, url: e.target.value })} placeholder="https://mcp.example.com/mcp"
          hint="The server's streamable HTTP endpoint. It must be https." />
        <AuthFields value={value} onChange={setValue} secretLabel={preset?.secretLabel} ownClient={preset?.ownClient} />
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Who can use it</span>
          <AgentChips agents={agents} keys={keys}
            onChange={(key, on) => setValue({ ...value, agentKeys: on ? [...keys.filter(k => k !== key), key] : keys.filter(k => k !== key) })} />
          <span className="text-xs text-gray-400">Its tools are offered to these agents in chat, each under its own guardrails. Jizza has it unless you choose otherwise.</span>
        </div>
        {value.authType === 'OAUTH' && <p className="text-xs text-gray-500 dark:text-gray-400">After you install it, press Sign in on its page to connect your {preset?.publisher ?? 'service'} account.</p>}
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
  const [params, setParams] = useSearchParams()
  const [reviewing, setReviewing] = useState(false)
  const toReview = server ? reviewable(server).length : 0
  const askedToReview = params.get('review') === '1'
  // A server just added opens with its tool types up for review, once they are known.
  useEffect(() => {
    if (!askedToReview || toReview === 0) return
    setReviewing(true)
    setParams(p => { p.delete('review'); return p }, { replace: true })
  }, [askedToReview, toReview, setParams])

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
            {server.signInTool && server.authType === 'NONE' && (
              <Button size="sm" variant={needsSessionSignIn(server) ? 'primary' : 'secondary'}
                onClick={() => setTesting(server.tools.find(t => t.name === server.signInTool) ?? null)}>
                <LogIn className="h-4 w-4" />Sign in
              </Button>
            )}
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
      {needsSessionSignIn(server) && <p className="mb-4 rounded-lg bg-yellow-50 px-3 py-2 text-sm text-yellow-800 dark:bg-yellow-500/10 dark:text-yellow-200">{server.name} signs you in inside its own session, and that session ended when Jizza last restarted. Press Sign in, then open the link it gives you.</p>}
      {authError && <p className="mb-4 text-sm text-red-500">{authError}</p>}
      {[refresh.error, toggle.error, signOut.error, remove.error].filter(Boolean).map((e, i) => <p key={i} className="mb-4 text-sm text-red-500">{errorText(e)}</p>)}

      <AgentsCard server={server} onSaved={put} />
      <ToolsCard server={server} onSaved={put} onTest={setTesting} onReview={() => setReviewing(true)} />

      <SettingsForm server={server} open={editing} onClose={() => setEditing(false)} onSaved={put} />
      <TestTool server={server} tool={testing} onClose={() => setTesting(null)} />
      <ToolTypesReview server={server} open={reviewing} onClose={() => setReviewing(false)} onSaved={put} />
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

function ToolsCard({ server, onSaved, onTest, onReview }: { server: McpServer; onSaved: (s: McpServer) => void; onTest: (t: McpTool) => void; onReview: () => void }) {
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
      {reviewable(server).length > 0 && (
        <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg bg-brand-50 px-3 py-2 text-sm text-brand-800 dark:bg-brand-500/10 dark:text-brand-200">
          <span className="min-w-0 flex-1">{reviewable(server).length} tools have a type Jizza guessed or none at all. Check them in one go.</span>
          {canModify && <Button size="sm" variant="secondary" onClick={onReview}><ListChecks className="h-4 w-4" />Review tool types</Button>}
        </div>
      )}
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
                <p className="mt-1 text-[11px] text-gray-400">{t.outputSchema ? `${schemaFields(t.outputSchema).length} output fields` : 'Try it once to learn its output fields'}</p>
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
                {t.suggested && <p className="mt-1 text-[11px] text-gray-400">Suggested from its name and description.</p>}
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
function TestTool({ server, tool: picked, onClose }: { server: McpServer; tool: McpTool | null; onClose: () => void }) {
  const qc = useQueryClient()
  const tool = picked ? server.tools.find(t => t.name === picked.name) ?? picked : null
  const memoryKey = tool ? `mcp-try:${server.id}:${tool.name}` : ''
  const [args, setArgs] = useState<Record<string, unknown>>({})
  const [text, setText] = useState('{}')
  const [asJson, setAsJson] = useState(false)
  const [loadedFor, setLoadedFor] = useState('')
  const [result, setResult] = useState<{ run: ToolRunResult; ms: number } | null>(null)
  const [copied, setCopied] = useState('')
  if (tool && loadedFor !== memoryKey) {
    // The last arguments used for this tool come back, as a test step keeps its sample in Zapier.
    let last: Record<string, unknown> = {}
    try { last = JSON.parse(localStorage.getItem(memoryKey) ?? '{}') } catch { /* none kept */ }
    setLoadedFor(memoryKey); setArgs(last); setText(JSON.stringify(last, null, 2)); setResult(null)
  }
  const { required } = inputFields(tool?.inputSchema)
  const missing = asJson ? [] : required.filter(k => args[k] === undefined || args[k] === '')
  const run = useMutation({
    mutationFn: async () => {
      const body = asJson ? JSON.parse(text || '{}') : args
      try { localStorage.setItem(memoryKey, JSON.stringify(body)) } catch { /* not kept */ }
      const started = performance.now()
      const out = await mcpApi.testTool(server.id, tool!.name, body)
      return { run: out, ms: Math.round(performance.now() - started) }
    },
    onSuccess: r => {
      setResult(r)
      // A successful run teaches the tool its output fields; show them.
      if (r.run.ok) void qc.invalidateQueries({ queryKey: ['mcp-server', server.id] })
    },
  })
  const close = () => { setResult(null); setLoadedFor(''); onClose() }
  const switchMode = () => {
    if (asJson) {
      try { setArgs(JSON.parse(text || '{}')) } catch { /* keep the form as it was */ }
    } else {
      setText(JSON.stringify(args, null, 2))
    }
    setAsJson(!asJson)
  }
  const copyPath = (path: string) => {
    void navigator.clipboard?.writeText(path)
    setCopied(path || 'the whole result')
  }
  const outputs = schemaFields(tool?.outputSchema)
  return (
    <SlideOver open={tool != null} title={`Try ${tool?.title || tool?.name || ''}`} wide
      description={tool?.description ? tool.description : 'This really runs the tool, as you.'} onClose={close}
      footer={<>
        {missing.length > 0 && <span className="mr-auto text-xs text-gray-500">Fill in {missing.join(', ')}</span>}
        <Button variant="ghost" onClick={close}>Close</Button>
        <Button loading={run.isPending} disabled={missing.length > 0} onClick={() => run.mutate()}><Play className="h-4 w-4" />Run</Button>
      </>}>
      <div className="flex flex-col gap-5">
        <section>
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Arguments</h3>
            <button type="button" className="text-xs text-brand-600 hover:underline dark:text-brand-300" onClick={switchMode}>
              {asJson ? 'Use the form' : 'Edit as JSON'}
            </button>
          </div>
          {asJson
            ? <textarea aria-label="Arguments as JSON" className={`${fieldClass} font-mono`} rows={6} value={text} onChange={e => setText(e.target.value)} />
            : <SchemaForm schema={tool?.inputSchema} value={args} onChange={setArgs} />}
          <p className="mt-2 text-xs text-gray-400">This really runs the tool, as you. The arguments you last used are kept on this device.</p>
        </section>
        {run.error && <p className="text-sm text-red-500">{errorText(run.error)}</p>}
        {result && (
          <section>
            <div className="mb-2 flex items-center gap-2">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Result</h3>
              <Badge variant={result.run.ok ? 'success' : 'danger'}>{result.run.ok ? 'Worked' : 'Failed'}</Badge>
              <span className="text-xs text-gray-400">{result.ms} ms</span>
              {copied && <span className="ml-auto text-xs text-gray-500">Copied {copied}</span>}
            </div>
            {result.run.ok
              ? <ResultView value={result.run.output} onPath={copyPath} />
              : <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-500/10 dark:text-red-200">{result.run.error}</p>}
          </section>
        )}
        <section>
          <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Output fields</h3>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">
            {tool?.outputSource === 'server' ? 'The server says what this tool returns. '
              : tool?.outputSource === 'learnt' ? 'Learnt from its last successful run. Only the field names and types are kept, never the values. '
              : 'Run the tool once to learn what it returns. '}
            Workflow steps after this tool can map these fields.
          </p>
          {outputs.length > 0 && (
            <ul className="flex max-h-56 flex-wrap gap-1.5 overflow-y-auto">
              {outputs.map(f => (
                <li key={f.path}>
                  <button type="button" onClick={() => copyPath(f.path)} title="Copy the path"
                    className="rounded-full border border-violet-200 bg-violet-50 px-2 py-0.5 font-mono text-[11px] text-violet-800 hover:border-violet-400 dark:border-violet-800 dark:bg-violet-900/30 dark:text-violet-200">
                    {f.path}<span className="ml-1 text-violet-400">{f.type}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </SlideOver>
  )
}

/** The tools whose type is only Jizza's guess, or missing: the ones a review is for. */
export function reviewable(server: McpServer): McpTool[] {
  return server.tools.filter(t => t.enabled && (t.suggested || !t.actionType))
}

type Group = 'read' | 'change' | 'money' | 'unset'

export function groupOf(type: ActionType | null | undefined): Group {
  if (!type) return 'unset'
  if (type === 'MONEY') return 'money'
  return type === 'READ' || type === 'DRAFT' ? 'read' : 'change'
}

const GROUPS: { id: Group; title: (n: number) => string }[] = [
  { id: 'read', title: n => `${n === 1 ? 'This one looks' : `These ${n} look`} like ${n === 1 ? 'a read' : 'reads'}` },
  { id: 'change', title: n => `${n === 1 ? 'This one changes' : `These ${n} change`} things` },
  { id: 'money', title: n => `${n === 1 ? 'This one moves' : `These ${n} move`} money` },
  { id: 'unset', title: n => `Jizza couldn't tell what ${n === 1 ? 'this one does' : `these ${n} do`}` },
]

/**
 * The review after a server is added: its tools grouped by what Jizza thinks they do, to accept in
 * one press or change any first. Each agent's guardrails go by these types.
 */
export function ToolTypesReview({ server, open, onClose, onSaved }: { server: McpServer; open: boolean; onClose: () => void; onSaved: (s: McpServer) => void }) {
  const tools = reviewable(server)
  const [chosen, setChosen] = useState<Record<string, ActionType | ''>>({})
  const typeOf = (t: McpTool): ActionType | '' => chosen[t.name] ?? t.actionType ?? ''
  const save = useMutation({
    mutationFn: () => {
      const types: Record<string, ActionType> = {}
      for (const t of tools) { const v = typeOf(t); if (v) types[t.name] = v }
      return mcpApi.updateToolTypes(server.id, types)
    },
    onSuccess: s => { onSaved(s); setChosen({}); onClose() },
  })
  const unset = tools.filter(t => !typeOf(t)).length
  return (
    <SlideOver open={open} wide title={`What ${server.name}'s tools do`}
      description="Jizza guessed from each tool's name and description. Each agent's guardrails go by these, so a read runs under its rule for looking things up and a payment under its rule for money."
      onClose={onClose}
      footer={<>
        {unset > 0 && <span className="mr-auto text-xs text-gray-500">{unset} left without a type {unset === 1 ? 'is' : 'are'} treated as moving money.</span>}
        <Button variant="ghost" onClick={onClose}>Later</Button>
        <Button loading={save.isPending} onClick={() => save.mutate()}>{Object.keys(chosen).length ? 'Save these types' : 'Accept all'}</Button>
      </>}>
      <div className="flex flex-col gap-5">
        {GROUPS.map(g => {
          // Grouped by the first guess, so a tool doesn't jump between groups while you change it.
          const inGroup = tools.filter(t => groupOf(t.actionType) === g.id)
          if (inGroup.length === 0) return null
          return (
            <section key={g.id} aria-label={g.title(inGroup.length)}>
              <h3 className="mb-2 text-sm font-semibold text-gray-900 dark:text-gray-100">{g.title(inGroup.length)}</h3>
              <ul className="flex flex-col divide-y divide-gray-100 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
                {inGroup.map(t => (
                  <li key={t.name} className="flex flex-wrap items-center gap-3 px-3 py-2">
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{t.title || t.name}</p>
                      {t.description && <p className="line-clamp-1 text-xs text-gray-500 dark:text-gray-400">{t.description}</p>}
                    </div>
                    <select className={`${fieldClass} w-48`} aria-label={`What ${t.name} does`} value={typeOf(t)}
                      onChange={e => setChosen(c => ({ ...c, [t.name]: e.target.value as ActionType | '' }))}>
                      {!typeOf(t) && <option value="">Choose…</option>}
                      {ACTION_TYPES.map(a => <option key={a} value={a}>{ACTION_LABELS[a].label}</option>)}
                    </select>
                  </li>
                ))}
              </ul>
            </section>
          )
        })}
        {save.error && <p className="text-sm text-red-500">{errorText(save.error)}</p>}
      </div>
    </SlideOver>
  )
}
