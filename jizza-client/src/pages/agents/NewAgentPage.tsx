import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Bot, Building2, Check, Lock, MessageSquare, Sparkles, Users, Wand2 } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Card } from '@/components/ui/Card'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { Input } from '@/components/ui/Input'
import { ModeSelect } from '@/components/guardrails/ModeSelect'
import { ACTION_LABELS, ACTION_TYPES } from '@/components/guardrails/labels'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { useAssistantStore } from '@/jizza/assistantStore'
import {
  AgentDraft, COLOURS, CREATED_PROFILE, SHARED_DOMAINS, Section, describeModes, draftFrom, emptyDraft, errorText,
  groupByTeam, looser, stepUpReasons, toCreate,
} from '@/components/agents/agentParts'
import type { ActionType, AgentBlueprint, AgentDto, AgentGalleryItem, AgentToolOption, OrgAgentTemplate } from '@/types'

const STEPS = ['Who', 'How', 'What it can use', 'What it may do alone', 'Review'] as const

/** What another screen can hand this one: a blueprint to start from and who it reports to. */
export interface NewAgentState {
  blueprint?: AgentBlueprint
  parentAgentId?: string
  /** After saving, go back here with the new agent's id (the workflow builder uses this). */
  returnTo?: string
}

/**
 * Making an agent: pick a starting point (a template, an organization's template, a blank form or
 * Jizza), fill in four short steps, then review in plain words what it will do on its own.
 */
export function NewAgentPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const handed = (location.state ?? {}) as NewAgentState
  const [draft, setDraft] = useState<AgentDraft | null>(handed.blueprint ? draftFrom(handed.blueprint, handed.parentAgentId) : null)
  const [step, setStep] = useState(0)

  return (
    <div className="page">
      <PageHeader icon={Bot} title="New agent"
        description={draft ? 'Nothing is created until you press Create on the last step.' : 'Start from a template, a blank form, or let Jizza draft one with you.'}
        actions={<Button variant="ghost" onClick={() => draft ? setDraft(null) : navigate('/agents')}><ArrowLeft className="h-4 w-4" /> Back</Button>} />
      {draft
        ? <AgentWizard draft={draft} onChange={setDraft} step={step} setStep={setStep} returnTo={handed.returnTo} />
        : <Gallery onPick={(d) => { setDraft(d); setStep(0) }} parentAgentId={handed.parentAgentId} />}
    </div>
  )
}

// ── Starting points ───────────────────────────────────────────────────────────

function Gallery({ onPick, parentAgentId }: { onPick: (d: AgentDraft) => void; parentAgentId?: string }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const setDockOpen = useAssistantStore(s => s.setDockOpen)
  const send = useAssistantStore(s => s.send)
  const { data: gallery } = useQuery({ queryKey: ['agent-gallery'], queryFn: () => agentsApi.gallery(), staleTime: 3_600_000 })
  const { data: orgTemplates } = useQuery({ queryKey: ['agent-templates', 'available'], queryFn: () => agentsApi.orgTemplates() })
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const [teamFor, setTeamFor] = useState<{ item?: AgentGalleryItem; org?: OrgAgentTemplate } | null>(null)

  const singles = gallery?.filter(g => g.kind === 'agent') ?? []
  const teams = gallery?.filter(g => g.kind.startsWith('team')) ?? []

  const startWithJizza = () => {
    setDockOpen(true)
    void send('I want to make a new agent of my own. Help me work out what it should do, then draft it for me.')
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="grid gap-3 sm:grid-cols-2">
        <Card padding="lg" interactive onClick={() => onPick(emptyDraft(parentAgentId))} className="flex items-start gap-3">
          <Wand2 className="mt-0.5 h-5 w-5 text-brand-500" />
          <div>
            <p className="font-semibold text-gray-900 dark:text-gray-100">Start from scratch</p>
            <p className="text-sm text-gray-500 dark:text-gray-400">Four short steps: who it is, how it works, what it can use, and what it may do on its own.</p>
          </div>
        </Card>
        <Card padding="lg" interactive onClick={startWithJizza} className="flex items-start gap-3">
          <MessageSquare className="mt-0.5 h-5 w-5 text-brand-500" />
          <div>
            <p className="font-semibold text-gray-900 dark:text-gray-100">Create with Jizza</p>
            <p className="text-sm text-gray-500 dark:text-gray-400">Describe what you need in chat. Jizza drafts the agent and shows it to you before anything is made.</p>
          </div>
        </Card>
      </div>

      <div>
        <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">Templates</h2>
        <div className="stagger grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {singles.map(item => {
            const b = item.agents[0]
            return (
              <Card key={item.id} padding="md" interactive onClick={() => onPick(draftFrom(b, parentAgentId))} className="flex items-start gap-3">
                <AgentLogo agentKey={`template-${item.id}`} avatar={b.avatar} size={40} />
                <div className="min-w-0">
                  <p className="font-medium text-gray-900 dark:text-gray-100">{item.name}</p>
                  <p className="text-xs text-gray-500 dark:text-gray-400">{item.description}</p>
                </div>
              </Card>
            )
          })}
        </div>
      </div>

      {teams.length > 0 && (
        <div>
          <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">Teams</h2>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {teams.map(item => (
              <Card key={item.id} padding="md" interactive onClick={() => setTeamFor({ item })}>
                <div className="flex items-center gap-2">
                  <Users className="h-4 w-4 text-brand-500" />
                  <p className="font-medium text-gray-900 dark:text-gray-100">{item.name}</p>
                  <Badge>{item.agents.length} agents</Badge>
                </div>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{item.description}</p>
                <p className="mt-2 text-xs text-gray-400">{item.agents.map(a => a.name).join(' · ')}</p>
              </Card>
            ))}
          </div>
        </div>
      )}

      {orgTemplates && orgTemplates.length > 0 && (
        <div>
          <h2 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-gray-700 dark:text-gray-300">
            <Building2 className="h-4 w-4" /> From your organization
          </h2>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {orgTemplates.map(t => (
              <Card key={t.id} padding="md" interactive onClick={() => setTeamFor({ org: t })}>
                <div className="flex items-center gap-2">
                  <p className="font-medium text-gray-900 dark:text-gray-100">{t.name}</p>
                  <Badge variant="info">{t.workspaceName ?? 'Organization'}</Badge>
                </div>
                {t.description && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t.description}</p>}
                <p className="mt-2 text-xs text-gray-400">{t.agents.map(a => a.name).join(' · ')} · version {t.version}</p>
              </Card>
            ))}
          </div>
        </div>
      )}

      {teamFor && agents && (
        <AddTeam agents={agents} item={teamFor.item} org={teamFor.org} defaultParent={parentAgentId}
          onCancel={() => setTeamFor(null)}
          onDone={() => { void qc.invalidateQueries({ queryKey: ['agents'] }); navigate('/agents?view=team') }} />
      )}
    </div>
  )
}

/** Adds a whole team, or an organization's template, under a chosen manager in one go. */
function AddTeam({ agents, item, org, defaultParent, onCancel, onDone }: {
  agents: AgentDto[]; item?: AgentGalleryItem; org?: OrgAgentTemplate; defaultParent?: string
  onCancel: () => void; onDone: () => void
}) {
  const suggested = item?.kind.startsWith('team:') ? agents.find(a => a.key === item.kind.slice(5))?.id : undefined
  const jizza = agents.find(a => a.orchestrator)
  const [parent, setParent] = useState(defaultParent ?? suggested ?? jizza?.id ?? '')
  const [error, setError] = useState('')
  const add = useMutation({
    mutationFn: () => org
      ? agentsApi.installTemplate(org.id, parent || null)
      : agentsApi.createTeam({ galleryId: item!.id, parentAgentId: parent || null }),
    onSuccess: onDone,
    onError: (e: unknown) => setError(errorText(e, 'Could not add the team')),
  })
  const blueprints = org?.agents ?? item?.agents ?? []
  const locks = blueprints.flatMap(b => (b.locks ?? []).map(l => `${b.name}: ${ACTION_LABELS[l].label} is locked`))
  return (
    <Section title={`Add ${org?.name ?? item?.name}`} hint="Each agent starts from the safe profile and can be changed afterwards. Tools are yours to pick on each agent's page.">
      <ul className="mb-3 flex flex-col gap-1 text-sm">
        {blueprints.map(b => (
          <li key={b.ref ?? b.name} className={b.reportsTo ? 'pl-5 text-gray-600 dark:text-gray-400' : 'font-medium text-gray-900 dark:text-gray-100'}>
            {b.reportsTo ? '└ ' : ''}{b.name}<span className="text-xs text-gray-400"> · {b.description}</span>
          </li>
        ))}
      </ul>
      {locks.length > 0 && <p className="mb-3 flex items-center gap-1 text-xs text-amber-700 dark:text-amber-300"><Lock className="h-3 w-3" />{locks.join('; ')} for everyone below.</p>}
      {org && (
        <details className="mb-3 text-xs text-gray-500">
          <summary className="cursor-pointer">Read the instructions your organization wrote</summary>
          {blueprints.map(b => <p key={b.name} className="mt-2 whitespace-pre-wrap"><b>{b.name}:</b> {b.instructions}</p>)}
        </details>
      )}
      <Field label="Reports to">
        <ManagerSelect agents={agents} value={parent} onChange={setParent} />
      </Field>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      <div className="mt-3 flex gap-2">
        <Button onClick={() => add.mutate()} loading={add.isPending}><Check className="h-4 w-4" /> Add {blueprints.length > 1 ? 'team' : 'agent'}</Button>
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
    </Section>
  )
}

export function ManagerSelect({ agents, value, onChange, exclude = [] }: { agents: AgentDto[]; value: string; onChange: (id: string) => void; exclude?: string[] }) {
  const jizza = agents.find(a => a.orchestrator)
  const groups = groupByTeam(agents)
  return (
    <select className={fieldClass} value={value} onChange={e => onChange(e.target.value)}>
      {jizza && <option value={jizza.id}>{jizza.name} (your assistant)</option>}
      {groups.map(g => (
        <optgroup key={g.label} label={g.label}>
          {g.agents.filter(a => !exclude.includes(a.id)).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
        </optgroup>
      ))}
    </select>
  )
}

// ── The four steps and the review ─────────────────────────────────────────────

function AgentWizard({ draft, onChange, step, setStep, returnTo }: {
  draft: AgentDraft; onChange: (d: AgentDraft) => void; step: number; setStep: (n: number) => void; returnTo?: string
}) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })
  const [error, setError] = useState('')
  const set = (patch: Partial<AgentDraft>) => onChange({ ...draft, ...patch })

  useEffect(() => {
    if (!draft.parentAgentId && agents) {
      const jizza = agents.find(a => a.orchestrator)
      if (jizza) onChange({ ...draft, parentAgentId: jizza.id })
    }
  }, [agents]) // eslint-disable-line react-hooks/exhaustive-deps

  const create = useMutation({
    mutationFn: (status: 'active' | 'draft') => agentsApi.create(toCreate(draft, status)),
    onSuccess: (agent) => {
      void qc.invalidateQueries({ queryKey: ['agents'] })
      if (returnTo) navigate(returnTo, { state: { createdAgentId: agent.id } })
      else navigate(`/agents/${agent.id}`)
    },
    onError: (e: unknown) => setError(errorText(e, 'Could not create the agent')),
  })

  const problems = [
    !draft.name.trim() && 'Give it a name.',
    !draft.description.trim() && 'Say when to use it.',
    !draft.instructions.trim() && 'Tell it how to work.',
  ].filter(Boolean) as string[]

  return (
    <div className="flex flex-col gap-4">
      <ol className="flex flex-wrap gap-1.5" aria-label="Steps">
        {STEPS.map((label, i) => (
          <li key={label}>
            <button type="button" onClick={() => setStep(i)}
              className={`rounded-full px-3 py-1 text-xs font-medium ${i === step ? 'bg-brand-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-800 dark:text-gray-300'}`}>
              {i + 1}. {label}
            </button>
          </li>
        ))}
      </ol>

      {step === 0 && <WhoStep draft={draft} set={set} agents={agents ?? []} />}
      {step === 1 && <HowStep draft={draft} set={set} />}
      {step === 2 && <ToolsStep draft={draft} set={set} />}
      {step === 3 && <ModesStep draft={draft} set={set} agents={agents ?? []} />}
      {step === 4 && <Review draft={draft} agents={agents ?? []} problems={problems} />}

      {error && <p className="text-sm text-red-600">{error}</p>}
      <div className="flex flex-wrap gap-2">
        {step > 0 && <Button variant="secondary" onClick={() => setStep(step - 1)}><ArrowLeft className="h-4 w-4" /> Back</Button>}
        {step < STEPS.length - 1 && <Button onClick={() => setStep(step + 1)}>Next <ArrowRight className="h-4 w-4" /></Button>}
        {step === STEPS.length - 1 && <>
          <Button disabled={problems.length > 0} loading={create.isPending && create.variables === 'active'} onClick={() => create.mutate('active')}>
            <Check className="h-4 w-4" /> Create
          </Button>
          <Button variant="secondary" disabled={!draft.name.trim() || !draft.instructions.trim()} loading={create.isPending && create.variables === 'draft'} onClick={() => create.mutate('draft')}>
            Save as draft
          </Button>
        </>}
      </div>
    </div>
  )
}

type StepProps = { draft: AgentDraft; set: (p: Partial<AgentDraft>) => void }

function WhoStep({ draft, set, agents }: StepProps & { agents: AgentDto[] }) {
  return (
    <Section title="Who it is" hint="Its name is what you and Jizza call it. Its use case is what its manager reads when deciding to hand it work.">
      <div className="grid gap-4 sm:grid-cols-[auto_1fr]">
        <div className="flex flex-col items-center gap-2">
          <AgentLogo agentKey="custom-preview" avatar={{ colour: draft.colour }} size={72} />
          <div className="flex flex-wrap justify-center gap-1" role="radiogroup" aria-label="Colour">
            {COLOURS.map(c => (
              <button key={c} type="button" role="radio" aria-checked={draft.colour === c} aria-label={c} onClick={() => set({ colour: c })}
                className={`h-5 w-5 rounded-full ring-2 ${draft.colour === c ? 'ring-gray-900 dark:ring-white' : 'ring-transparent'}`}
                style={{ background: `var(--avatar-${c}, ${c})` }} />
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-3">
          <Input label="Name" value={draft.name} maxLength={60} onChange={e => set({ name: e.target.value })} placeholder="e.g. Wanda" />
          <Field label="When to use it" hint={`${draft.description.length}/300. One or two sentences, e.g. "Plans trips: flights, hotels, itineraries."`}>
            <textarea className={fieldClass} rows={2} maxLength={300} value={draft.description} onChange={e => set({ description: e.target.value })} />
          </Field>
          <Field label="Reports to" hint="Jizza by default. Put it under another agent to build a team.">
            <ManagerSelect agents={agents} value={draft.parentAgentId} onChange={id => set({ parentAgentId: id })} />
          </Field>
          <Field label="Responsibilities" hint="Optional, one per line. Shown to its manager.">
            <textarea className={fieldClass} rows={3} value={draft.responsibilities.join('\n')}
              onChange={e => set({ responsibilities: e.target.value.split('\n').slice(0, 8) })} />
          </Field>
        </div>
      </div>
    </Section>
  )
}

function HowStep({ draft, set }: StepProps) {
  const [suggestion, setSuggestion] = useState<string | null>(null)
  const [error, setError] = useState('')
  const improve = useMutation({
    mutationFn: () => agentsApi.improve({ name: draft.name, description: draft.description, instructions: draft.instructions }),
    onSuccess: r => setSuggestion(r.instructions),
    onError: (e: unknown) => setError(errorText(e, 'Could not rewrite them')),
  })
  return (
    <Section title="How it works" hint="Its job, its tone, and what it must always or never do. Whatever this says, its tools and guardrails are what limit it."
      actions={<Button size="sm" variant="secondary" disabled={!draft.instructions.trim()} loading={improve.isPending} onClick={() => improve.mutate()}><Sparkles className="h-3.5 w-3.5" /> Improve these</Button>}>
      <textarea aria-label="Instructions" className={fieldClass} rows={10} maxLength={4000} value={draft.instructions} onChange={e => set({ instructions: e.target.value })} />
      <p className="mt-1 text-xs text-gray-400">{draft.instructions.length}/4000</p>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      {suggestion && (
        <div className="mt-3 rounded-lg border border-brand-200 bg-brand-50/50 p-3 dark:border-brand-800 dark:bg-brand-500/5">
          <p className="mb-1 text-xs font-medium text-brand-700 dark:text-brand-300">Jizza's rewrite</p>
          <p className="whitespace-pre-wrap text-sm text-gray-800 dark:text-gray-200">{suggestion}</p>
          <div className="mt-2 flex gap-2">
            <Button size="sm" onClick={() => { set({ instructions: suggestion }); setSuggestion(null) }}>Use this</Button>
            <Button size="sm" variant="ghost" onClick={() => setSuggestion(null)}>Keep mine</Button>
          </div>
        </div>
      )}
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <Field label="Model" hint="Deep reasons more carefully and costs several times as much.">
          <select className={fieldClass} value={draft.modelTier} onChange={e => set({ modelTier: e.target.value as 'standard' | 'deep' })}>
            <option value="standard">Standard</option>
            <option value="deep">Deep</option>
          </select>
        </Field>
        <Field label="Hands off to" hint="For a manager: which of its reports handles what, in your words.">
          <input className={fieldClass} value={draft.handsOffTo} onChange={e => set({ handsOffTo: e.target.value })} />
        </Field>
      </div>
    </Section>
  )
}

function ToolsStep({ draft, set }: StepProps) {
  const { data: options, isLoading } = useQuery({ queryKey: ['agent-tool-options'], queryFn: () => agentsApi.toolOptions() })
  const { data: suggested } = useQuery({
    queryKey: ['agent-tool-suggestions', draft.description, draft.toolHints.join(',')],
    queryFn: () => agentsApi.suggestTools(draft.description, draft.toolHints),
    enabled: !!draft.description.trim(),
  })
  const [filter, setFilter] = useState('')
  const has = (o: AgentToolOption) => draft.tools.some(t => t.type === o.type && t.ref === o.ref)
  const toggle = (o: AgentToolOption) => set({
    tools: has(o) ? draft.tools.filter(t => !(t.type === o.type && t.ref === o.ref)) : [...draft.tools, { type: o.type, ref: o.ref }],
  })
  const bySource = useMemo(() => {
    const groups = new Map<string, AgentToolOption[]>()
    for (const o of options ?? []) {
      if (filter && !`${o.name} ${o.source} ${o.description ?? ''}`.toLowerCase().includes(filter.toLowerCase())) continue
      const key = o.source ?? 'Other'
      groups.set(key, [...(groups.get(key) ?? []), o])
    }
    return [...groups.entries()]
  }, [options, filter])
  const risky = (options ?? []).filter(o => has(o) && (o.actionType === 'MONEY' || o.actionType === 'DELETE'))

  return (
    <Section title="What it can use" hint="It only ever sees the tools you tick. Each shows what kind of action it is.">
      {suggested && suggested.length > 0 && (
        <div className="mb-3">
          <p className="mb-1 text-xs font-medium text-gray-600 dark:text-gray-400">Suggested from its use case</p>
          <div className="flex flex-wrap gap-1">
            {suggested.map(o => (
              <button key={`${o.type}:${o.ref}`} type="button" onClick={() => toggle(o)}
                className={`rounded-full border px-2 py-0.5 text-xs ${has(o) ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-500/10 dark:text-brand-300' : 'border-gray-300 text-gray-600 dark:border-gray-700 dark:text-gray-300'}`}>
                {has(o) ? '✓ ' : '+ '}{o.name}
              </button>
            ))}
          </div>
        </div>
      )}
      {isLoading && <p className="text-sm text-gray-500">Loading your tools…</p>}
      {options && options.length === 0 && <p className="text-sm text-gray-500">You have no connected tools yet. The agent can still think, write and use its own notes; add tools later from its page.</p>}
      {options && options.length > 0 && <>
        <input className={`${fieldClass} mb-3`} placeholder="Filter tools" value={filter} onChange={e => setFilter(e.target.value)} />
        <div className="flex max-h-96 flex-col gap-3 overflow-y-auto">
          {bySource.map(([source, list]) => (
            <div key={source}>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-400">{source}</p>
              {list.map(o => (
                <label key={`${o.type}:${o.ref}`} className="flex items-start gap-2 rounded-md px-1 py-1 text-sm hover:bg-gray-50 dark:hover:bg-gray-800">
                  <input type="checkbox" className="mt-1" checked={has(o)} onChange={() => toggle(o)} />
                  <span className="min-w-0 flex-1">
                    <span className="text-gray-900 dark:text-gray-100">{o.name}</span>
                    {o.description && <span className="block truncate text-xs text-gray-500">{o.description}</span>}
                  </span>
                  {o.actionType && <Badge variant={o.actionType === 'MONEY' || o.actionType === 'DELETE' ? 'danger' : o.actionType === 'SEND' ? 'warning' : 'default'}>{ACTION_LABELS[o.actionType].label}</Badge>}
                </label>
              ))}
            </div>
          ))}
        </div>
      </>}
      {risky.length > 0 && (
        <p className="mt-3 text-xs text-amber-700 dark:text-amber-300">
          This agent will be able to ask you to {risky.some(o => o.actionType === 'MONEY') ? 'move money' : 'delete things'}.
          With its rules as they are, it will {draft.modes.MONEY === 'DENY' ? 'never do it on its own' : 'check the next step for what it may do alone'}.
        </p>
      )}
    </Section>
  )
}

function ModesStep({ draft, set, agents }: StepProps & { agents: AgentDto[] }) {
  const manager = agents.find(a => a.id === draft.parentAgentId)
  const setMode = (t: ActionType, m: AgentDraft['modes'][ActionType]) => set({ modes: { ...draft.modes, [t]: m } })
  const toggleLock = (t: ActionType) => set({ locks: draft.locks.includes(t) ? draft.locks.filter(l => l !== t) : [...draft.locks, t] })
  return (
    <Section title="What it may do on its own" hint={`Starts from the safe profile${manager && !manager.orchestrator ? `, no looser than ${manager.name}` : ''}. Anything looser asks for your password when you save.`}>
      <div className="flex flex-col gap-2">
        {ACTION_TYPES.map(t => (
          <div key={t} className="flex flex-wrap items-center gap-2 border-b border-gray-100 pb-2 last:border-0 dark:border-gray-800">
            <div className="min-w-[10rem] flex-1">
              <p className="text-sm text-gray-900 dark:text-gray-100">{ACTION_LABELS[t].label}</p>
              <p className="text-xs text-gray-500">{ACTION_LABELS[t].hint}</p>
            </div>
            <ModeSelect label={ACTION_LABELS[t].label} value={draft.modes[t]} onChange={m => setMode(t, m)} />
            {looser(draft.modes[t], CREATED_PROFILE[t]) && <Badge variant="warning">Looser than default</Badge>}
            <label className="flex items-center gap-1 text-xs text-gray-500" title="Everyone who reports to this agent is capped at this mode too">
              <input type="checkbox" checked={draft.locks.includes(t)} onChange={() => toggleLock(t)} /> Lock for its team
            </label>
          </div>
        ))}
      </div>
      <div className="mt-4">
        <p className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">Shared memory it can read</p>
        <p className="mb-2 text-xs text-gray-500">It always has its own private memory. Tick an area only if it needs it.</p>
        {SHARED_DOMAINS.map(d => (
          <label key={d.id} className="flex items-start gap-2 py-0.5 text-sm">
            <input type="checkbox" className="mt-1" checked={draft.memoryDomains.includes(d.id)}
              onChange={() => set({ memoryDomains: draft.memoryDomains.includes(d.id) ? draft.memoryDomains.filter(x => x !== d.id) : [...draft.memoryDomains, d.id] })} />
            <span>{d.label}{draft.memoryDomains.includes(d.id) && <span className="block text-xs text-amber-700 dark:text-amber-300">{d.warning}</span>}</span>
          </label>
        ))}
      </div>
      <div className="mt-4 max-w-xs">
        <Input label="Monthly spending limit (optional)" type="number" min={0} step="0.01" value={draft.monthlyCostLimit}
          onChange={e => set({ monthlyCostLimit: e.target.value })} />
        <p className="mt-1 text-xs text-gray-400">When reached, it stops working unattended until next month and Jizza tells you.</p>
      </div>
    </Section>
  )
}

function Review({ draft, agents, problems }: { draft: AgentDraft; agents: AgentDto[]; problems: string[] }) {
  const { alone, asks, never } = describeModes(draft.modes)
  const manager = agents.find(a => a.id === draft.parentAgentId)
  const stepUp = stepUpReasons(draft)
  const sentence = (list: string[]) => list.join(', ')
  return (
    <Section title={`Review ${draft.name || 'your agent'}`}>
      <div className="flex items-start gap-3">
        <AgentLogo agentKey="custom-preview" avatar={{ colour: draft.colour }} size={56} />
        <div className="min-w-0 text-sm text-gray-700 dark:text-gray-300">
          <p className="font-semibold text-gray-900 dark:text-gray-100">{draft.name || 'Unnamed'}</p>
          <p>{draft.description || 'No use case yet.'}</p>
          <p className="mt-1 text-xs text-gray-500">Reports to {manager?.name ?? 'Jizza'} · {draft.modelTier === 'deep' ? 'Deep' : 'Standard'} model · {draft.tools.length} tool{draft.tools.length === 1 ? '' : 's'}</p>
        </div>
      </div>
      <ul className="mt-4 flex flex-col gap-1.5 text-sm">
        {alone.length > 0 && <li className="text-emerald-700 dark:text-emerald-300">On its own, it will {sentence(alone)}.</li>}
        {asks.length > 0 && <li className="text-amber-700 dark:text-amber-300">It asks you before it will {sentence(asks)}.</li>}
        {never.length > 0 && <li className="text-red-700 dark:text-red-300">It will never {sentence(never)}.</li>}
        {draft.locks.length > 0 && <li className="text-gray-600 dark:text-gray-400">Locked for everyone below it: {draft.locks.map(l => ACTION_LABELS[l].label.toLowerCase()).join(', ')}.</li>}
        <li className="text-gray-600 dark:text-gray-400">
          It can read its own memory{draft.memoryDomains.length ? ` and your ${draft.memoryDomains.join(', ')} memory` : ''}.
        </li>
      </ul>
      {stepUp.length > 0 && <p className="mt-3 text-xs text-gray-500">You'll be asked for your password because {stepUp.join(' and ')}.</p>}
      {problems.length > 0 && <ul className="mt-3 text-sm text-red-600">{problems.map(p => <li key={p}>{p}</li>)}</ul>}
    </Section>
  )
}
