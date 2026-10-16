import { FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Bot, Hand, Play, Plus, Sparkles, Trash2 } from 'lucide-react'
import { agentsApi } from '@/api/agents'
import { workflowsApi } from '@/api/workflows'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Field, SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { useFormStore } from '@/jizza/formStore'
import { useAssistantStore } from '@/jizza/assistantStore'
import type { AgentDto } from '@/types'
import { matchAgent, str } from './agentMatch'
import { AgentLogo } from '@/components/brand/AgentLogo'

interface Step {
  key: number
  kind: 'agent' | 'ask'
  agentId: string
  instruction: string
}

let stepKey = 1

function stepsFrom(raw: unknown, agents: AgentDto[] | undefined): Step[] {
  if (!Array.isArray(raw)) return []
  return raw.map((s: Record<string, unknown>) => ({
    key: stepKey++,
    kind: s?.kind === 'ask' ? 'ask' : 'agent',
    agentId: matchAgent(agents, s?.agent_key ?? s?.agent)?.id ?? '',
    instruction: str(s?.instruction),
  }))
}

/**
 * Reads a saved graph back into the builder's steps. Only a straight line of agent and "ask me"
 * steps fits the builder; anything else (branches, connector or transform nodes) returns null and
 * the workflow is shown read-only.
 */
export function stepsFromGraph(graph: unknown): Record<string, unknown>[] | null {
  const g = graph as { nodes?: Array<{ id: string; type: string; config?: Record<string, unknown> }>; edges?: Array<{ from: string; to: string }> } | null
  if (!g || !Array.isArray(g.nodes) || !Array.isArray(g.edges)) return null
  const byId = new Map(g.nodes.map(n => [n.id, n]))
  const next = new Map<string, string>()
  for (const e of g.edges) {
    if (next.has(e.from)) return null
    next.set(e.from, e.to)
  }
  const start = g.nodes.find(n => n.type === 'TRIGGER')
  if (!start) return null
  const steps: Record<string, unknown>[] = []
  const seen = new Set<string>([start.id])
  let cur = next.get(start.id)
  while (cur) {
    const node = byId.get(cur)
    if (!node || seen.has(cur)) return null
    seen.add(cur)
    if (node.type === 'END') break
    if (node.type === 'AGENT_STEP') steps.push({ kind: 'agent', agent: node.config?.agentId, instruction: node.config?.instruction })
    else if (node.type === 'HUMAN_INTERVENTION') steps.push({ kind: 'ask', instruction: node.config?.prompt })
    else return null
    cur = next.get(cur)
  }
  return seen.size === g.nodes.length ? steps : null
}

/** Turns the step list into the engine's graph: start, each step in order, end. */
export function toGraph(steps: Pick<Step, 'kind' | 'agentId' | 'instruction'>[]) {
  const nodes: unknown[] = [{ id: 'start', type: 'TRIGGER', label: 'Start', config: {} }]
  const edges: unknown[] = []
  let prev = 'start'
  steps.forEach((step, i) => {
    const id = `step_${i + 1}`
    nodes.push(step.kind === 'agent'
      ? { id, type: 'AGENT_STEP', label: `Step ${i + 1}`, config: { agentId: step.agentId, instruction: step.instruction } }
      : { id, type: 'HUMAN_INTERVENTION', label: 'Ask me', config: { prompt: step.instruction } })
    edges.push({ from: prev, to: id, branch: null })
    prev = id
  })
  nodes.push({ id: 'end', type: 'END', label: 'Done', config: {} })
  edges.push({ from: prev, to: 'end', branch: null })
  return { nodes, edges }
}

export function WorkflowForm() {
  const request = useFormStore(s => (s.request?.form === 'new_workflow' ? s.request : null))
  const close = useFormStore(s => s.close)
  const note = useAssistantStore(s => s.note)
  const qc = useQueryClient()
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [steps, setSteps] = useState<Step[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  // Opened from a saved row: editing that workflow rather than starting a new one.
  const workflowId = str(request?.fields.id) || undefined
  const status = str(request?.fields.status) || 'DRAFT'
  const fitsBuilder = !workflowId || Array.isArray(request?.fields.steps)
  const readOnly = !!workflowId && (status !== 'DRAFT' || !fitsBuilder)

  // Load whatever Jizza (or a button) opened the form with; re-runs when agents arrive so
  // agent names in Jizza's draft resolve.
  useEffect(() => {
    if (!request) return
    setName(str(request.fields.name))
    setDescription(str(request.fields.description))
    setSteps(stepsFrom(request.fields.steps, agents))
    setError('')
    setNotice('')
  }, [request?.nonce, agents]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = useMutation({
    mutationFn: () => workflowsApi.save({ id: workflowId, name: name.trim(), description: description.trim() || undefined, graph: toGraph(steps) }),
    onSuccess: (wf) => {
      void qc.invalidateQueries({ queryKey: ['workflows'] })
      if (request?.byJizza && !workflowId) note(`Saved "${wf?.name ?? name}" as a draft workflow. Want me to run it or turn it into a skill?`)
      close()
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not save the workflow'),
  })

  const run = useMutation({
    mutationFn: () => workflowsApi.run(workflowId!),
    onSuccess: (r) => setNotice(`Run started (${String(r?.status ?? 'running').toLowerCase().replace(/_/g, ' ')}). You'll see it in Activity and Notifications.`),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not start the run'),
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    if (readOnly) return
    if (!name.trim()) return setError('Give the workflow a name.')
    if (steps.length === 0) return setError('Add at least one step.')
    const bad = steps.findIndex(s => !s.instruction.trim() || (s.kind === 'agent' && !s.agentId))
    if (bad >= 0) return setError(`Step ${bad + 1} needs ${steps[bad].kind === 'agent' && !steps[bad].agentId ? 'an agent' : 'an instruction'}.`)
    setError('')
    save.mutate()
  }

  const update = (key: number, patch: Partial<Step>) => setSteps(list => list.map(s => (s.key === key ? { ...s, ...patch } : s)))
  const move = (i: number, by: number) => setSteps(list => {
    const next = [...list]
    const [s] = next.splice(i, 1)
    next.splice(i + by, 0, s)
    return next
  })
  const add = (kind: Step['kind']) => setSteps(list => [...list, {
    key: stepKey++, kind, instruction: '',
    agentId: kind === 'agent' ? (agents?.find(a => !a.isOrchestrator && a.enabled)?.id ?? agents?.[0]?.id ?? '') : '',
  }])

  const hi = !!request?.byJizza

  return (
    <SlideOver
      open={!!request}
      title={workflowId ? name || 'Workflow' : 'New workflow'}
      description={readOnly
        ? `This workflow is ${status.toLowerCase().replace(/_/g, ' ')}, so it is shown read-only here.`
        : 'Steps run in order. A draft does nothing until you run it.'}
      onClose={close}
      footer={<>
        <Button variant="secondary" onClick={close}>{readOnly ? 'Close' : 'Cancel'}</Button>
        {workflowId && (
          <Button type="button" variant="secondary" onClick={() => { setError(''); run.mutate() }} loading={run.isPending}>
            <Play className="h-4 w-4" />Run now
          </Button>
        )}
        {!readOnly && <Button type="submit" form="workflow-form" loading={save.isPending}>{workflowId ? 'Save changes' : 'Save workflow'}</Button>}
      </>}
    >
      <form id="workflow-form" onSubmit={submit} className="flex flex-col gap-5">
        <fieldset disabled={readOnly} className="contents">
        {hi && (
          <div className="flex items-start gap-2.5 rounded-xl border border-brand-300 bg-brand-50 px-3 py-2.5 text-sm text-brand-800 animate-fade-in dark:border-brand-700 dark:bg-brand-500/10 dark:text-brand-200">
            <Sparkles className="mt-0.5 h-4 w-4 shrink-0" />
            <span>Jizza drafted this for you. Check it over and save, or ask Jizza to change something.</span>
          </div>
        )}
        <div className={hi && name ? 'rounded-lg animate-jizza-filled' : ''}>
          <Input label="Name" value={name} onChange={e => setName(e.target.value)} placeholder="Weekly report" />
        </div>
        <Field label="Description" highlight={hi && !!description}>
          <textarea className={fieldClass} rows={2} value={description} onChange={e => setDescription(e.target.value)} placeholder="What this workflow is for" />
        </Field>

        <div>
          <p className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-300">Steps</p>
          <ol className="flex flex-col gap-3">
            {steps.map((step, i) => (
              <li key={step.key} className={`relative rounded-xl border border-gray-200 bg-gray-50 p-3 animate-fade-in-up dark:border-gray-800 dark:bg-gray-950 ${hi ? 'animate-jizza-filled' : ''}`}>
                <div className="mb-2 flex items-center gap-2">
                  <span className="grid h-6 w-6 place-items-center rounded-full bg-brand-500 text-xs font-semibold text-white">{i + 1}</span>
                  <span className="flex items-center gap-1 text-sm font-medium text-gray-800 dark:text-gray-200">
                    {step.kind === 'agent'
                      ? <>{step.agentId
                          ? <AgentLogo agentKey={agents?.find(a => a.id === step.agentId)?.key ?? 'other'} size={22} />
                          : <Bot className="h-4 w-4" />} {agents?.find(a => a.id === step.agentId)?.name ?? 'Agent'} does</>
                      : <><Hand className="h-4 w-4" /> Ask me</>}
                  </span>
                  <span className="flex-1" />
                  <IconButton label="Move up" disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp className="h-3.5 w-3.5" /></IconButton>
                  <IconButton label="Move down" disabled={i === steps.length - 1} onClick={() => move(i, 1)}><ArrowDown className="h-3.5 w-3.5" /></IconButton>
                  <IconButton label="Remove step" onClick={() => setSteps(list => list.filter(s => s.key !== step.key))}><Trash2 className="h-3.5 w-3.5" /></IconButton>
                </div>
                {step.kind === 'agent' && (
                  <select className={`${fieldClass} mb-2`} value={step.agentId} onChange={e => update(step.key, { agentId: e.target.value })}>
                    <option value="">Choose an agent…</option>
                    {agents?.map(a => <option key={a.id} value={a.id}>{a.name}{a.domain ? ` · ${a.domain}` : ''}</option>)}
                  </select>
                )}
                <textarea
                  className={fieldClass}
                  rows={2}
                  value={step.instruction}
                  onChange={e => update(step.key, { instruction: e.target.value })}
                  placeholder={step.kind === 'agent' ? 'What should the agent do?' : 'What should it ask you?'}
                />
              </li>
            ))}
          </ol>
          <div className="mt-3 flex gap-2">
            <Button type="button" size="sm" variant="secondary" onClick={() => add('agent')}><Plus className="h-4 w-4" />Agent step</Button>
            <Button type="button" size="sm" variant="secondary" onClick={() => add('ask')}><Hand className="h-4 w-4" />Ask me</Button>
          </div>
        </div>
        </fieldset>
        {readOnly && !fitsBuilder && (
          <p className="text-sm text-gray-500 dark:text-gray-400">This workflow has steps the builder can't show yet, such as branches or connector tools.</p>
        )}
        {notice && <p className="text-sm text-emerald-600 dark:text-emerald-400">{notice}</p>}
        {error && <p className="text-sm text-red-500">{error}</p>}
      </form>
    </SlideOver>
  )
}

function IconButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <button type="button" title={label} aria-label={label} disabled={disabled} onClick={onClick}
      className="grid h-7 w-7 place-items-center rounded-md text-gray-500 transition-colors hover:bg-gray-200 hover:text-gray-900 disabled:opacity-30 disabled:hover:bg-transparent dark:hover:bg-gray-800 dark:hover:text-gray-100">
      {children}
    </button>
  )
}
