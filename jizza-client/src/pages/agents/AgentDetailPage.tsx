import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArrowLeft, Brain, History, Lock, Play, RotateCcw, Sparkles, Trash2 } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { mcpApi } from '@/api/mcp'
import { usePermission } from '@/auth/usePermission'
import { PERMISSIONS } from '@/lib/permissions'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Dialog } from '@/components/ui/Dialog'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { Input } from '@/components/ui/Input'
import { ACTION_LABELS, MODE_BADGE, MODE_LABELS } from '@/components/guardrails/labels'
import { AgentAvatar, AgentBadges, COLOURS, SHARED_DOMAINS, Section, TeamTrailList, errorText } from '@/components/agents/agentParts'
import { ManagerSelect } from './NewAgentPage'
import type { AgentDto, AgentTrial, McpServer } from '@/types'

const TABS = ['Overview', 'Instructions', 'Tools', 'Guardrails', 'Try it', 'Team'] as const
type Tab = (typeof TABS)[number]

/** One agent: what it's for, how it works, what it can use and do, a trial, and where it sits in the team. */
export function AgentDetailPage() {
  const { agentId = '' } = useParams()
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('Overview')
  const { data: agent, isLoading } = useQuery({ queryKey: ['agent', agentId], queryFn: () => agentsApi.get(agentId) })
  const { data: agents } = useQuery({ queryKey: ['agents', 'all'], queryFn: () => agentsApi.list('all') })

  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (!agent) return <div className="page"><p className="text-sm text-gray-500">This agent doesn't exist, or isn't yours.</p></div>

  const manager = agents?.find(a => a.id === agent.parentAgentId)
  return (
    <div className="page">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate('/agents')}><ArrowLeft className="h-4 w-4" /> Agents</Button>
        <AgentAvatar agent={agent} size={48} />
        <div className="min-w-0 flex-1">
          <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold text-gray-900 dark:text-gray-100">{agent.name} <AgentBadges agent={agent} /></h1>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {agent.orchestrator ? 'Your assistant' : agent.description ?? agent.domain}
            {manager && !agent.orchestrator ? ` · Reports to ${manager.name}` : ''}
          </p>
        </div>
        <Button size="sm" variant="ghost" onClick={() => navigate(`/memory/${agent.id}`)}><Brain className="h-3.5 w-3.5" /> Memory</Button>
      </div>

      <nav className="mb-4 flex flex-wrap gap-1 border-b border-gray-200 dark:border-gray-800" aria-label="Agent sections">
        {TABS.filter(t => !(agent.orchestrator && (t === 'Try it' || t === 'Team'))).map(t => (
          <button key={t} type="button" onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-3 py-2 text-sm ${tab === t ? 'border-brand-500 font-medium text-brand-600 dark:text-brand-300' : 'border-transparent text-gray-500 hover:text-gray-800 dark:hover:text-gray-200'}`}>
            {t}
          </button>
        ))}
      </nav>

      {tab === 'Overview' && <Overview agent={agent} />}
      {tab === 'Instructions' && <Instructions agent={agent} />}
      {tab === 'Tools' && <Tools agent={agent} />}
      {tab === 'Guardrails' && <Modes agent={agent} />}
      {tab === 'Try it' && <TryIt agent={agent} />}
      {tab === 'Team' && <Team agent={agent} agents={agents ?? []} />}
    </div>
  )
}

function useAgentSaved(agentId: string) {
  const qc = useQueryClient()
  return () => {
    void qc.invalidateQueries({ queryKey: ['agent', agentId] })
    void qc.invalidateQueries({ queryKey: ['agents'] })
  }
}

// ── Overview ──────────────────────────────────────────────────────────────────

function Overview({ agent }: { agent: AgentDto }) {
  const saved = useAgentSaved(agent.id)
  const navigate = useNavigate()
  const { data: spend } = useQuery({ queryKey: ['agent-spend', agent.id], queryFn: () => agentsApi.spend(agent.id) })
  const { data: updates } = useQuery({ queryKey: ['agent-templates', 'updates'], queryFn: () => agentsApi.templateUpdates(), enabled: !!agent.originTemplateId })
  const update = updates?.find(u => u.agentId === agent.id)
  const [error, setError] = useState('')
  const [archiving, setArchiving] = useState(false)
  const run = (fn: () => Promise<unknown>) => fn().then(saved).catch(e => setError(errorText(e, 'That did not work')))
  const status = agent.status ?? 'active'

  return (
    <div className="flex flex-col gap-4">
      {update && (
        <Section title="Update available" hint={`${update.templateName} is now on version ${update.to}; this agent is on ${update.from}. Instructions and tools update; rules only ever get stricter.`}>
          <Button size="sm" onClick={() => run(() => agentsApi.applyTemplateUpdate(agent.id))}>Apply the update</Button>
        </Section>
      )}
      <Section title="Status">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {status === 'draft' && <><span className="text-gray-600 dark:text-gray-400">A draft isn't on anyone's team yet.</span>
            <Button size="sm" onClick={() => run(() => agentsApi.activate(agent.id))}>Activate</Button></>}
          {status === 'active' && !agent.orchestrator && <>
            <span className="text-gray-600 dark:text-gray-400">{agent.enabled ? 'Ready to work.' : 'Paused: its workflows and tasks don\'t run.'}</span>
            <Button size="sm" variant="secondary" onClick={() => run(() => agentsApi.patch(agent.id, { enabled: !agent.enabled }))}>{agent.enabled ? 'Pause' : 'Resume'}</Button>
          </>}
          {status === 'archived' && <>
            <span className="text-gray-600 dark:text-gray-400">Archived. Its memory and history are kept.</span>
            <Button size="sm" onClick={() => run(() => agentsApi.restore(agent.id))}><RotateCcw className="h-3.5 w-3.5" /> Restore</Button>
            <Button size="sm" variant="danger" onClick={() => {
              if (window.confirm(`Delete ${agent.name} for good, with its private memory? This can't be undone.`)) {
                agentsApi.remove(agent.id).then(() => { saved(); navigate('/agents') }).catch(e => setError(errorText(e, 'Could not delete it')))
              }
            }}><Trash2 className="h-3.5 w-3.5" /> Delete for good</Button>
          </>}
          {agent.custom && status !== 'archived' && <Button size="sm" variant="ghost" onClick={() => setArchiving(true)}><Archive className="h-3.5 w-3.5" /> Archive</Button>}
        </div>
        {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      </Section>
      {spend && (
        <Section title="Spending this month">
          <p className="text-sm text-gray-700 dark:text-gray-300">
            {spend.spentThisMonth.toFixed(2)}{spend.monthlyCostLimit != null ? ` of its ${spend.monthlyCostLimit.toFixed(2)} limit` : ' (no limit set)'}
          </p>
          {spend.monthlyCostLimit != null && (
            <div className="mt-2 h-1.5 w-full max-w-sm overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800">
              <div className="h-full bg-brand-500" style={{ width: `${Math.min(100, (spend.spentThisMonth / Math.max(spend.monthlyCostLimit, 0.0001)) * 100)}%` }} />
            </div>
          )}
        </Section>
      )}
      {archiving && <ArchiveDialog agent={agent} onClose={() => setArchiving(false)} onDone={() => { setArchiving(false); saved() }} />}
    </div>
  )
}

function ArchiveDialog({ agent, onClose, onDone }: { agent: AgentDto; onClose: () => void; onDone: () => void }) {
  const { data: dependents } = useQuery({ queryKey: ['agent-dependents', agent.id], queryFn: () => agentsApi.dependents(agent.id) })
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const reports = agents?.filter(a => a.parentAgentId === agent.id) ?? []
  const [reportsChoice, setReportsChoice] = useState<'move_up' | 'pause'>('move_up')
  const [reassignTo, setReassignTo] = useState('')
  const [error, setError] = useState('')
  const archive = useMutation({
    mutationFn: () => agentsApi.archive(agent.id, { reports: reportsChoice, reassignTo: reassignTo || null }),
    onSuccess: onDone,
    onError: (e: unknown) => setError(errorText(e, 'Could not archive it')),
  })
  const owned = dependents?.filter(d => d.ownedByAgent) ?? []
  return (
    <Dialog open title={`Archive ${agent.name}`} description="It leaves the team. Its memory, history and audit are kept, and you can restore it." onClose={onClose}>
      <div className="flex flex-col gap-3 text-sm">
        {reports.length > 0 && (
          <Field label={`Its ${reports.length} report${reports.length === 1 ? '' : 's'}`}>
            <select className={fieldClass} value={reportsChoice} onChange={e => setReportsChoice(e.target.value as 'move_up' | 'pause')}>
              <option value="move_up">Move up to its manager</option>
              <option value="pause">Pause them where they are</option>
            </select>
          </Field>
        )}
        {dependents && dependents.length > 0 && (
          <div>
            <p className="font-medium text-gray-800 dark:text-gray-200">Still uses this agent</p>
            <ul className="mt-1 list-disc pl-5 text-gray-600 dark:text-gray-400">
              {dependents.map(d => <li key={`${d.kind}:${d.id}`}>{d.kind === 'workflow' ? 'Workflow' : 'Task'}: {d.name}{d.ownedByAgent ? ' (owned by it)' : ' (a step uses it)'}</li>)}
            </ul>
            {owned.length > 0 && (
              <Field label="Hand what it owns to" hint="Leave empty to leave them paused until you reassign them. Steps inside workflows show a warning until you change them.">
                <select className={fieldClass} value={reassignTo} onChange={e => setReassignTo(e.target.value)}>
                  <option value="">Nobody for now</option>
                  {agents?.filter(a => a.id !== agent.id && a.enabled && !a.orchestrator).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
              </Field>
            )}
          </div>
        )}
        {error && <p className="text-red-600">{error}</p>}
        <div className="flex gap-2">
          <Button variant="danger" loading={archive.isPending} onClick={() => archive.mutate()}><Archive className="h-4 w-4" /> Archive</Button>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
        </div>
      </div>
    </Dialog>
  )
}

// ── Instructions and history ──────────────────────────────────────────────────

function Instructions({ agent }: { agent: AgentDto }) {
  const saved = useAgentSaved(agent.id)
  const editable = !!agent.custom && agent.status !== 'archived'
  const [name, setName] = useState(agent.name)
  const [description, setDescription] = useState(agent.description ?? '')
  const [instructions, setInstructions] = useState(agent.systemPrompt ?? '')
  const [modelTier, setModelTier] = useState(agent.modelTier ?? 'standard')
  const [colour, setColour] = useState(agent.avatar?.colour ?? 'purple')
  const [domains, setDomains] = useState<string[]>(agent.memoryDomains ?? [])
  const [limit, setLimit] = useState(agent.monthlyCostLimit != null ? String(agent.monthlyCostLimit) : '')
  const [suggestion, setSuggestion] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [ok, setOk] = useState('')
  const { data: revisions, refetch } = useQuery({ queryKey: ['agent-revisions', agent.id], queryFn: () => agentsApi.revisions(agent.id), enabled: !!agent.custom })

  useEffect(() => { setInstructions(agent.systemPrompt ?? ''); setName(agent.name); setDescription(agent.description ?? '') }, [agent])

  const save = useMutation({
    mutationFn: () => agentsApi.patch(agent.id, {
      name, description, instructions, modelTier, avatar: { style: 'robot', colour }, memoryDomains: domains,
      ...(limit.trim() === '' ? { clearMonthlyCostLimit: true } : { monthlyCostLimit: Number(limit) }),
    }),
    onSuccess: () => { saved(); void refetch(); setOk('Saved. It takes effect on its next run.'); setError('') },
    onError: (e: unknown) => setError(errorText(e, 'Could not save')),
  })
  const rename = useMutation({
    mutationFn: () => agentsApi.patch(agent.id, { name }),
    onSuccess: () => { saved(); setOk('Renamed.') },
    onError: (e: unknown) => setError(errorText(e, 'Could not rename')),
  })
  const improve = useMutation({
    mutationFn: () => agentsApi.improve({ name, description, instructions }),
    onSuccess: r => setSuggestion(r.instructions),
    onError: (e: unknown) => setError(errorText(e, 'Could not rewrite them')),
  })

  if (!agent.custom) {
    return (
      <Section title="Instructions" hint={`${agent.name}'s instructions come with the product. You can rename it.`}>
        <div className="flex max-w-md items-end gap-2">
          <Input label="Name" value={name} maxLength={60} onChange={e => setName(e.target.value)} />
          <Button onClick={() => rename.mutate()} loading={rename.isPending} disabled={name.trim() === agent.name}>Rename</Button>
        </div>
        <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap rounded-lg bg-gray-50 p-3 text-xs text-gray-700 dark:bg-gray-950 dark:text-gray-300">{agent.systemPrompt}</pre>
        {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
        {ok && <p className="mt-2 text-sm text-emerald-600">{ok}</p>}
      </Section>
    )
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_18rem]">
      <Section title="Who it is and how it works"
        actions={editable ? <Button size="sm" variant="secondary" loading={improve.isPending} onClick={() => improve.mutate()}><Sparkles className="h-3.5 w-3.5" /> Improve these</Button> : undefined}>
        <fieldset disabled={!editable} className="flex flex-col gap-3">
          <Input label="Name" value={name} maxLength={60} onChange={e => setName(e.target.value)} />
          <Field label="When to use it">
            <textarea className={fieldClass} rows={2} maxLength={300} value={description} onChange={e => setDescription(e.target.value)} />
          </Field>
          <Field label="Instructions" hint={`${instructions.length}/4000`}>
            <textarea className={fieldClass} rows={10} maxLength={4000} value={instructions} onChange={e => setInstructions(e.target.value)} />
          </Field>
          {suggestion && (
            <div className="rounded-lg border border-brand-200 bg-brand-50/50 p-3 dark:border-brand-800 dark:bg-brand-500/5">
              <p className="mb-1 text-xs font-medium text-brand-700 dark:text-brand-300">Jizza's rewrite</p>
              <p className="whitespace-pre-wrap text-sm">{suggestion}</p>
              <div className="mt-2 flex gap-2">
                <Button size="sm" onClick={() => { setInstructions(suggestion); setSuggestion(null) }}>Use this</Button>
                <Button size="sm" variant="ghost" onClick={() => setSuggestion(null)}>Keep mine</Button>
              </div>
            </div>
          )}
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="Model">
              <select className={fieldClass} value={modelTier} onChange={e => setModelTier(e.target.value as 'standard' | 'deep')}>
                <option value="standard">Standard</option><option value="deep">Deep</option>
              </select>
            </Field>
            <Field label="Colour">
              <select className={fieldClass} value={colour} onChange={e => setColour(e.target.value)}>
                {COLOURS.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </Field>
            <Input label="Monthly limit" type="number" min={0} step="0.01" value={limit} onChange={e => setLimit(e.target.value)} />
          </div>
          <div>
            <p className="text-sm font-medium text-gray-700 dark:text-gray-300">Shared memory it can read</p>
            {SHARED_DOMAINS.map(d => (
              <label key={d.id} className="mr-4 inline-flex items-center gap-1.5 text-sm">
                <input type="checkbox" checked={domains.includes(d.id)} onChange={() => setDomains(domains.includes(d.id) ? domains.filter(x => x !== d.id) : [...domains, d.id])} /> {d.label}
              </label>
            ))}
          </div>
        </fieldset>
        {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
        {ok && <p className="mt-2 text-sm text-emerald-600">{ok}</p>}
        {editable && <Button className="mt-3" onClick={() => save.mutate()} loading={save.isPending}>Save</Button>}
      </Section>
      <Section title="History" hint="Each change to its name, use case, instructions or model.">
        <ol className="flex flex-col gap-2">
          {revisions?.map(r => (
            <li key={r.id} className="flex items-center gap-2 text-xs">
              <History className="h-3.5 w-3.5 text-gray-400" />
              <span className="flex-1 text-gray-700 dark:text-gray-300">#{r.revision} · {new Date(r.createdAt).toLocaleString()}</span>
              {editable && r.revision !== revisions[0]?.revision && (
                <button type="button" className="text-brand-600 hover:underline"
                  onClick={() => agentsApi.restoreRevision(agent.id, r.revision).then(() => { saved(); void refetch() }).catch(e => setError(errorText(e, 'Could not restore')))}>
                  Restore
                </button>
              )}
            </li>
          ))}
        </ol>
      </Section>
    </div>
  )
}

// ── Tools ─────────────────────────────────────────────────────────────────────

function Tools({ agent }: { agent: AgentDto }) {
  const qc = useQueryClient()
  const { data: grants } = useQuery({ queryKey: ['agent-grants', agent.id], queryFn: () => agentsApi.grants(agent.id) })
  const { data: options } = useQuery({ queryKey: ['agent-tool-options'], queryFn: () => agentsApi.toolOptions() })
  const [error, setError] = useState('')
  const refresh = () => void qc.invalidateQueries({ queryKey: ['agent-grants', agent.id] })
  const held = (type: string, ref: string) => grants?.some(g => g.type === type && g.ref === ref)
  const named = (grants ?? []).map(g => ({ g, o: options?.find(o => o.type === g.type && o.ref === g.ref) }))
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Section title="What it can use" hint="Removing or adding a tool asks for your password.">
        {named.length === 0 && <p className="text-sm text-gray-500">No tools yet. It can still think, write and keep its own notes.</p>}
        <ul className="flex flex-col gap-1">
          {named.map(({ g, o }) => (
            <li key={`${g.type}:${g.ref}`} className="flex items-center gap-2 text-sm">
              <span className="flex-1 text-gray-800 dark:text-gray-200">{o?.name ?? 'A tool you no longer have access to'}<span className="text-xs text-gray-400"> · {o?.source}</span></span>
              {o?.actionType && <Badge>{ACTION_LABELS[o.actionType].label}</Badge>}
              <button type="button" className="text-xs text-red-600 hover:underline"
                onClick={() => agentsApi.revoke(agent.id, g.type, g.ref).then(refresh).catch(e => setError(errorText(e, 'Could not remove it')))}>Remove</button>
            </li>
          ))}
        </ul>
        {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      </Section>
      <McpServersFor agent={agent} />
      <Section title="Add a tool">
        <ul className="flex max-h-96 flex-col gap-1 overflow-y-auto">
          {(options ?? []).filter(o => !held(o.type, o.ref)).map(o => (
            <li key={`${o.type}:${o.ref}`} className="flex items-center gap-2 text-sm">
              <span className="flex-1 truncate text-gray-800 dark:text-gray-200">{o.name}<span className="text-xs text-gray-400"> · {o.source}</span></span>
              {o.actionType && <Badge variant={o.actionType === 'MONEY' || o.actionType === 'DELETE' ? 'danger' : 'default'}>{ACTION_LABELS[o.actionType].label}</Badge>}
              <Button size="sm" variant="secondary" onClick={() => agentsApi.grant(agent.id, o.type, o.ref).then(refresh).catch(e => setError(errorText(e, 'Could not add it')))}>Add</Button>
            </li>
          ))}
        </ul>
      </Section>
    </div>
  )
}

/** The MCP servers this agent may use; the same choice as "Who can use it" on each server. */
function McpServersFor({ agent }: { agent: AgentDto }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: servers } = useQuery({ queryKey: ['mcp-servers'], queryFn: () => mcpApi.list() })
  const canModify = usePermission(PERMISSIONS.CONNECTOR_MODIFY)
  const save = useMutation({
    mutationFn: ({ server, on }: { server: McpServer; on: boolean }) => mcpApi.update(server.id, {
      agentKeys: on ? [...server.agentKeys.filter(k => k !== agent.key), agent.key] : server.agentKeys.filter(k => k !== agent.key),
    }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['mcp-servers'] }),
  })
  if (!servers?.length) return null
  return (
    <Section title="MCP servers" hint={`Their tools reach ${agent.name} under its guardrails: a tool that looks things up runs under its rule for looking things up.`}>
      <ul className="flex flex-col gap-2">
        {servers.map(server => {
          const every = server.agentKeys.includes('*')
          const on = every || server.agentKeys.includes(agent.key)
          return (
            <li key={server.id} className="flex items-center gap-2 text-sm">
              <input type="checkbox" aria-label={`${agent.name} uses ${server.name}`} checked={on}
                disabled={!canModify || every || save.isPending} onChange={e => save.mutate({ server, on: e.target.checked })} />
              <button type="button" className="flex-1 truncate text-left text-gray-800 hover:underline dark:text-gray-200" onClick={() => navigate(`/connectors/mcp/${server.id}`)}>
                {server.name}<span className="text-xs text-gray-400"> · {server.tools.filter(t => t.enabled).length} tools{every ? ' · every agent' : ''}</span>
              </button>
            </li>
          )
        })}
      </ul>
      {save.error && <p className="mt-2 text-sm text-red-600">{errorText(save.error, 'Could not change it')}</p>}
    </Section>
  )
}

// ── Guardrails ────────────────────────────────────────────────────────────────

function Modes({ agent }: { agent: AgentDto }) {
  const navigate = useNavigate()
  const { data: lines } = useQuery({ queryKey: ['agent-modes', agent.id], queryFn: () => agentsApi.modes(agent.id) })
  return (
    <Section title="What it may do on its own" hint="As the guardrails judge it today, outside any workflow. A lock on a manager caps everyone below it."
      actions={<Button size="sm" variant="secondary" onClick={() => navigate('/guardrails')}>Change on Guardrails</Button>}>
      <ul className="flex flex-col gap-2">
        {lines?.map(l => (
          <li key={l.actionType} className="flex flex-wrap items-center gap-2 text-sm">
            <span className="min-w-[10rem] flex-1 text-gray-800 dark:text-gray-200">{ACTION_LABELS[l.actionType].label}</span>
            <Badge variant={MODE_BADGE[l.rule]}>{MODE_LABELS[l.rule]}</Badge>
            {l.ceiling && <span className="inline-flex items-center gap-1 text-xs text-gray-500"><Lock className="h-3 w-3" /> at most {MODE_LABELS[l.ceiling]} (locked on {l.ceilingFrom})</span>}
          </li>
        ))}
      </ul>
    </Section>
  )
}

// ── Try it ────────────────────────────────────────────────────────────────────

function TryIt({ agent }: { agent: AgentDto }) {
  const [message, setMessage] = useState('')
  const [trial, setTrial] = useState<AgentTrial | null>(null)
  const run = useMutation({
    mutationFn: () => agentsApi.tryOut(agent.id, message),
    onSuccess: setTrial,
  })
  return (
    <Section title={`Try ${agent.name}`} hint="It runs for real for looking things up. Anything that would change something is not done; you see what it would have done instead. This is a test of the agent, not a conversation.">
      <div className="flex flex-col gap-2">
        <textarea aria-label="Sample request" className={fieldClass} rows={3} value={message} maxLength={2000}
          placeholder="e.g. Find me a hotel in Lisbon under £150 for next weekend" onChange={e => setMessage(e.target.value)} />
        <div><Button onClick={() => run.mutate()} loading={run.isPending} disabled={!message.trim()}><Play className="h-4 w-4" /> Try it</Button></div>
      </div>
      {run.isError && <p className="mt-2 text-sm text-red-600">{errorText(run.error, 'The trial did not run')}</p>}
      {trial && (
        <div className="mt-4 flex flex-col gap-3 text-sm">
          {trial.error && <p className="text-red-600">{trial.error}</p>}
          {trial.answer && (
            <div className="rounded-lg bg-gray-50 p-3 dark:bg-gray-950">
              <p className="mb-1 text-xs font-medium text-gray-500">{agent.name}'s answer</p>
              <p className="whitespace-pre-wrap text-gray-800 dark:text-gray-200">{trial.answer}</p>
            </div>
          )}
          {trial.wouldHaveDone.length > 0 && (
            <div>
              <p className="text-xs font-medium text-gray-500">Not done, because this was a trial</p>
              <ul className="mt-1 flex flex-col gap-1">
                {trial.wouldHaveDone.map((h, i) => (
                  <li key={i} className="text-gray-700 dark:text-gray-300">
                    {h.agentName} would {h.wouldDo === 'allow' ? 'go ahead and' : h.wouldDo === 'deny' ? 'not be allowed to' : 'ask you before it can'} {h.tool.replace(/_/g, ' ')}
                    <span className="text-xs text-gray-400"> · {h.reason}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {trial.team.length > 0 && (
            <div>
              <p className="text-xs font-medium text-gray-500">Who it handed work to</p>
              <TeamTrailList steps={trial.team} />
            </div>
          )}
          <p className="text-xs text-gray-400">Tools used: {trial.toolsUsed.length ? trial.toolsUsed.join(', ') : 'none'} · {trial.tokens.toLocaleString()} tokens</p>
        </div>
      )}
    </Section>
  )
}

// ── Team ──────────────────────────────────────────────────────────────────────

function Team({ agent, agents }: { agent: AgentDto; agents: AgentDto[] }) {
  const saved = useAgentSaved(agent.id)
  const navigate = useNavigate()
  const [manager, setManager] = useState(agent.parentAgentId ?? '')
  const [error, setError] = useState('')
  const reports = agents.filter(a => a.parentAgentId === agent.id && a.status !== 'archived')
  const below = new Set<string>()
  const walk = (id: string) => agents.filter(a => a.parentAgentId === id).forEach(a => { if (!below.has(a.id)) { below.add(a.id); walk(a.id) } })
  walk(agent.id)
  const move = useMutation({
    mutationFn: () => agentsApi.setManager(agent.id, manager),
    onSuccess: () => { saved(); setError('') },
    onError: (e: unknown) => setError(errorText(e, 'Could not move it')),
  })
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Section title="Reports to" hint={agent.custom ? 'Moving it re-checks its guardrails against its new manager’s locks.' : `${agent.name} always reports to Jizza.`}>
        {agent.custom ? <>
          <ManagerSelect agents={agents.filter(a => a.status !== 'archived')} value={manager} onChange={setManager} exclude={[agent.id, ...below]} />
          {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
          <Button className="mt-3" size="sm" disabled={manager === agent.parentAgentId} loading={move.isPending} onClick={() => move.mutate()}>Move</Button>
        </> : <p className="text-sm text-gray-600">Jizza</p>}
      </Section>
      <Section title="Its reports" actions={<Button size="sm" variant="secondary" onClick={() => navigate('/agents/new', { state: { parentAgentId: agent.id } })}>Add a report</Button>}>
        {reports.length === 0 ? <p className="text-sm text-gray-500">Nobody reports to {agent.name}. Add one to let it hand work on.</p> : (
          <ul className="flex flex-col gap-2">
            {reports.map(r => (
              <li key={r.id}>
                <button type="button" onClick={() => navigate(`/agents/${r.id}`)} className="flex items-center gap-2 text-left text-sm hover:underline">
                  <AgentAvatar agent={r} size={24} /> {r.name} <AgentBadges agent={r} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}
