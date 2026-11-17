import { FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Bot, Cable, Hand, List, Network, Play, Plus, Sparkles, Trash2 } from 'lucide-react'
import { WorkflowCanvas } from '@/components/workflow/WorkflowCanvas'
import { WiresBoard } from '@/components/workflow/WiresBoard'
import { LocatorFixPanel } from '@/components/web/LocatorFixPanel'
import type { LocatorFix } from '@/api/web'
import { emptyGraph, fromGraph, problems as canvasProblems, toGraph as canvasToGraph, type CanvasGraph } from '@/components/workflow/canvasModel'
import { agentsApi } from '@/api/agents'
import { workflowsApi } from '@/api/workflows'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Field, SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { WorkflowTriggerControls } from '@/components/workflow/WorkflowTriggerControls'
import { useFormStore } from '@/jizza/formStore'
import { useAssistantStore } from '@/jizza/assistantStore'
import { useJizzaContext } from '@/jizza/contextStore'
import { builderContext } from '@/jizza/builderContext'
import { JizzaOrb } from '@/jizza/JizzaOrb'
import type { AgentDto } from '@/types'
import { matchAgent, str } from './agentMatch'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { inputSections, moduleInputs, ruleProblem } from '@/components/workflow/moduleContract'
import { DateValueInput } from '@/components/ui/DateValueInput'
import { FileValueInput } from '@/components/ui/FileValueInput'
import { RowsInput, rowsEmpty } from '@/components/ui/RowsInput'

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
  const setDockOpen = useAssistantStore(s => s.setDockOpen)
  const qc = useQueryClient()
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => agentsApi.list() })

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [steps, setSteps] = useState<Step[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [mode, setMode] = useState<EditorMode>(preferredMode)
  const [canvas, setCanvas] = useState<CanvasGraph>(emptyGraph)
  // Canvas and Wires are two views of the same graph; only the step list is separate.
  const graphMode = mode !== 'steps'

  // Opened from a saved row: editing that workflow rather than starting a new one.
  const workflowId = str(request?.fields.id) || undefined
  const status = str(request?.fields.status) || 'DRAFT'
  const baseVersion = Number(request?.fields.version) || undefined
  const readOnly = !!workflowId && status !== 'DRAFT'
  // Branches, service actions and the like only exist on the canvas; such a workflow can't be
  // shown as a simple list of steps.
  // The run shown on the canvas: the one started here, else the workflow's latest.
  const [runId, setRunId] = useState<string | null>(null)
  const { data: runs } = useQuery({
    queryKey: ['workflow-runs', workflowId],
    queryFn: () => workflowsApi.latestRuns(workflowId!),
    enabled: !!workflowId && graphMode,
  })
  const latestRunId = runId ?? runs?.[0]?.id ?? null
  const { data: shownRun } = useQuery({
    queryKey: ['run', latestRunId],
    queryFn: () => workflowsApi.getRun(latestRunId!),
    enabled: !!latestRunId && graphMode,
    refetchInterval: q => (q.state.data?.status === 'RUNNING' ? 1500 : false),
  })
  const canvasSteps = graphMode ? stepsFromGraph(canvasToGraph(canvas)) : null
  const stepsAvailable = mode === 'steps' || canvasSteps !== null

  function switchMode(next: EditorMode) {
    if (next === mode) return
    if (next === 'steps') {
      if (!canvasSteps) return
      setSteps(stepsFrom(canvasSteps, agents))
    } else if (!graphMode) {
      // Keep the canvas layout when the steps haven't changed since it was last shown.
      // Between Canvas and Wires nothing converts: both show the same graph.
      const current = stepsFromGraph(canvasToGraph(canvas))
      const same = current !== null && JSON.stringify(current.map(plain)) === JSON.stringify(steps.map(plain))
      if (!same) setCanvas(fromGraph(toGraph(steps)))
    }
    setMode(next)
    rememberMode(next)
    setError('')
  }

  // Load whatever Jizza (or a button) opened the form with; re-runs when agents arrive so
  // agent names in Jizza's draft resolve.
  useEffect(() => {
    if (!request) return
    setName(str(request.fields.name))
    setDescription(str(request.fields.description))
    setSteps(stepsFrom(request.fields.steps, agents))
    setError('')
    setNotice(str(request.fields.notice))
  }, [request?.nonce, agents])

  useEffect(() => { setRunId(null) }, [request?.nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  // Which editor to open in: a saved graph the step list can't show opens on the canvas.
  useEffect(() => {
    if (!request) return
    const saved = request.fields.graph
    setCanvas(saved ? fromGraph(saved) : emptyGraph())
    if (saved && !Array.isArray(request.fields.steps)) setMode(preferredMode() === 'wires' ? 'wires' : 'canvas')
    else if (request.byJizza) setMode('steps')
    else setMode(preferredMode())
  }, [request?.nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  // A run history or notification link names the step to look at, such as one with a fix to review.
  const focusNode = str(request?.fields.focusNode) || null
  const openForm = useFormStore(s => s.open)
  // An applied fix saved a new version: open that, on the same step.
  async function reopenFixed(fix: LocatorFix, version: number) {
    void qc.invalidateQueries({ queryKey: ['workflows'] })
    try {
      const wf = await workflowsApi.get(fix.workflowId)
      openForm('new_workflow', { id: wf.id, name: wf.name, description: wf.description, status: wf.status, version: wf.version, graph: wf.graph, focusNode: fix.nodeId,
        notice: wf.status === 'DRAFT' ? `Saved version ${version} with the new element for ${fix.nodeLabel}.` : `Version ${version}, with the new element for ${fix.nodeLabel}, is waiting for review in Procedures.` })
    } catch {
      setNotice(`Saved version ${version}. Close and open the workflow to see it.`)
    }
  }

  const save = useMutation({
    mutationFn: () => workflowsApi.save({
      id: workflowId, name: name.trim(), description: description.trim() || undefined,
      graph: graphMode ? canvasToGraph(canvas) : toGraph(steps),
      // The version opened here: a newer one saved meanwhile is not written over.
      ...(workflowId && baseVersion ? { baseVersion } : {}),
    }),
    onSuccess: (wf) => {
      void qc.invalidateQueries({ queryKey: ['workflows'] })
      if (request?.byJizza && !workflowId) note(`Saved "${wf?.name ?? name}" as a draft workflow. Want me to run it or turn it into a skill?`)
      close()
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not save the workflow'),
  })

  // A workflow that declares inputs asks for them before a run, as a module's caller would give them.
  const declaredInputs = graphMode ? moduleInputs(canvasToGraph(canvas)) : []
  const atStepInputs = declaredInputs.filter(f => f.ask === 'atStep')
  const [askInputs, setAskInputs] = useState(false)
  const [runInput, setRunInput] = useState<Record<string, string>>({})
  // After runInput: reading it before its useState line throws as soon as a workflow declares inputs.
  const inputProblems = declaredInputs.some(f => ruleProblem(f, runInput[f.name]) !== null)
  useEffect(() => { setAskInputs(false); setRunInput({}) }, [request?.nonce])

  const run = useMutation({
    mutationFn: () => workflowsApi.run(workflowId!, undefined, declaredInputs.length
      ? Object.fromEntries(Object.entries(runInput).filter(([, v]) => v.trim() !== ''))
      : undefined),
    onSuccess: (r) => {
      setAskInputs(false)
      if (r?.id) setRunId(r.id)
      setNotice(`Run started (${String(r?.status ?? 'running').toLowerCase().replace(/_/g, ' ')}). ${mode === 'canvas' ? 'Its progress shows on the canvas.' : mode === 'wires' ? 'Arrows follow it along the wires.' : "You'll see it in Activity and Notifications."}`)
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not start the run'),
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    if (readOnly) return
    if (!name.trim()) return setError('Give the workflow a name.')
    if (graphMode) {
      const issues = canvasProblems(canvas)
      if (issues.length) return setError(issues.slice(0, 3).join(' '))
      setError('')
      return save.mutate()
    }
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
    agentId: kind === 'agent' ? (agents?.find(a => !a.orchestrator && a.enabled)?.id ?? agents?.[0]?.id ?? '') : '',
  }])

  const hi = !!request?.byJizza

  // While the form is open Jizza works on this workflow, whichever editor shows it.
  const shownGraph = request ? (graphMode ? canvas : fromGraph(toGraph(steps))) : null
  useJizzaContext(request && shownGraph ? builderContext(name.trim(), shownGraph, canvasProblems(shownGraph)) : null)

  return (
    <SlideOver
      open={!!request}
      title={workflowId ? name || 'Workflow' : 'New workflow'}
      description={readOnly
        ? `This workflow is ${status.toLowerCase().replace(/_/g, ' ')}, so it is shown read-only here.`
        : mode === 'canvas'
          ? 'Drag steps onto the canvas and connect them. A draft does nothing until you run it.'
          : mode === 'wires'
            ? 'Drag steps onto a step or a wire and they wire themselves in. Steps run top to bottom. A draft does nothing until you run it.'
          : 'Steps run in order. A draft does nothing until you run it.'}
      onClose={close}
      wide={graphMode}
      headerExtra={<ModeToggle mode={mode} onChange={switchMode} stepsDisabled={!stepsAvailable} />}
      footer={<>
        {/* Jizza works on whatever is open here: the workflow, or a step's settings. */}
        <button type="button" onClick={() => setDockOpen(true)} title="Ask Jizza about this workflow (Ctrl+K)"
          className="mr-auto inline-flex items-center gap-2 rounded-full py-1 pl-1 pr-3 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800">
          <JizzaOrb size={32} />Ask Jizza
        </button>
        {workflowId && <div className="mr-2 hidden lg:block"><WorkflowTriggerControls workflowId={workflowId} compact /></div>}
        <Button variant="secondary" onClick={close}>{readOnly ? 'Close' : 'Cancel'}</Button>
        {workflowId && (
          <Button type="button" variant="secondary" onClick={() => { setError(''); if (declaredInputs.length && !askInputs) setAskInputs(true); else run.mutate() }} loading={run.isPending}>
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
        <div className={graphMode ? 'grid gap-4 sm:grid-cols-2' : 'contents'}>
          <div className={hi && name ? 'rounded-lg animate-jizza-filled' : ''}>
            <Input label="Name" value={name} onChange={e => setName(e.target.value)} placeholder="Weekly report" />
          </div>
          <Field label="Description" highlight={hi && !!description}>
            <textarea className={fieldClass} rows={graphMode ? 1 : 2} value={description} onChange={e => setDescription(e.target.value)} placeholder="What this workflow is for" />
          </Field>
        </div>

        {mode === 'steps' && <div>
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
          <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">Need branches or service actions? Switch to Canvas or Wires at the top.</p>
        </div>}
        </fieldset>
        {workflowId && <LocatorFixPanel workflowId={workflowId} focusNode={focusNode} onApplied={reopenFixed} />}
        {mode === 'canvas' && <WorkflowCanvas graph={canvas} onChange={setCanvas} readOnly={readOnly} run={shownRun} focusNode={focusNode} />}
        {mode === 'wires' && <WiresBoard graph={canvas} onChange={setCanvas} readOnly={readOnly} run={shownRun} focusNode={focusNode} />}
        {askInputs && declaredInputs.length > 0 && (
          <div className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-gray-50 p-3 animate-fade-in dark:border-gray-800 dark:bg-gray-950">
            <p className="text-sm font-medium text-gray-800 dark:text-gray-200">Inputs for this run</p>
            {inputSections(declaredInputs).map(section => (
              <section key={section.group || '-'} className="flex flex-col gap-2" aria-label={section.group || 'Inputs'}>
                {section.group && <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{section.group}</p>}
                <div className="grid gap-3 sm:grid-cols-2">
                  {section.fields.map(f => {
                    const label = f.label ?? f.name
                    const problem = ruleProblem(f, runInput[f.name])
                    return (
                      <Field key={f.name} label={`${label}${f.required && f.default === undefined ? ' *' : ''}`} hint={problem ?? f.description}>
                        {f.columns?.length ? (
                          <RowsInput label={label} columns={f.columns}
                            value={runInput[f.name] ?? (f.default !== undefined ? JSON.stringify(f.default) : '[]')}
                            onChange={v => setRunInput(r => ({ ...r, [f.name]: rowsEmpty(v) ? '' : v }))} />
                        ) : f.type === 'file' ? (
                          <FileValueInput label={`Input ${f.name}`} value={runInput[f.name] ?? ''}
                            onChange={v => setRunInput(r => ({ ...r, [f.name]: v }))} />
                        ) : f.type === 'date' || f.type === 'datetime' ? (
                          <DateValueInput type={f.type} format={f.format} className={fieldClass} aria-label={`Input ${f.name}`}
                            value={runInput[f.name] ?? (typeof f.default === 'string' ? f.default : '')}
                            onChange={v => setRunInput(r => ({ ...r, [f.name]: v }))} />
                        ) : f.options?.length ? (
                          <select className={fieldClass} aria-label={`Input ${f.name}`}
                            value={runInput[f.name] ?? (typeof f.default === 'string' ? f.default : '')}
                            onChange={e => setRunInput(v => ({ ...v, [f.name]: e.target.value }))}>
                            <option value="">{f.required && f.default === undefined ? 'Choose…' : 'Default'}</option>
                            {f.options.map(o => <option key={o} value={o}>{o}</option>)}
                          </select>
                        ) : <input className={fieldClass} aria-label={`Input ${f.name}`} value={runInput[f.name] ?? ''}
                          aria-invalid={problem ? true : undefined}
                          placeholder={f.default !== undefined ? `Default: ${typeof f.default === 'string' ? f.default : JSON.stringify(f.default)}` : f.type === 'any' ? '' : f.type}
                          onChange={e => setRunInput(v => ({ ...v, [f.name]: e.target.value }))} />}
                      </Field>
                    )
                  })}
                </div>
              </section>
            ))}
            {atStepInputs.length > 0 && (
              <p className="text-xs text-gray-600 dark:text-gray-400">
                Asked during the run, on the step that needs it: {atStepInputs.map(f => f.label ?? f.name).join(', ')}.
              </p>
            )}
            <p className="text-[11px] text-gray-500">The saved version runs. Lists and fields are written as JSON, such as ["a", "b"].</p>
            <div className="flex justify-end gap-2">
              <Button type="button" size="sm" variant="secondary" onClick={() => setAskInputs(false)}>Cancel</Button>
              <Button type="button" size="sm" disabled={inputProblems} onClick={() => { setError(''); run.mutate() }} loading={run.isPending}><Play className="h-4 w-4" />Start run</Button>
            </div>
          </div>
        )}
        {notice && <p className="text-sm text-emerald-600 dark:text-emerald-400">{notice}</p>}
        {error && <p className="text-sm text-red-500">{error}</p>}
      </form>
    </SlideOver>
  )
}

type EditorMode = 'steps' | 'canvas' | 'wires'
const MODE_KEY = 'jizza.workflowEditor'

/** The editor the user picked last time, so each person keeps the one they're comfortable with. */
function preferredMode(): EditorMode {
  try {
    const saved = localStorage.getItem(MODE_KEY)
    return saved === 'canvas' || saved === 'wires' ? saved : 'steps'
  } catch { return 'steps' }
}

function rememberMode(mode: EditorMode) {
  try { localStorage.setItem(MODE_KEY, mode) } catch { /* the choice just isn't remembered */ }
}

const plain = (s: { kind?: unknown; agentId?: unknown; agent?: unknown; instruction?: unknown }) => ({ kind: s.kind, agent: s.agentId ?? s.agent ?? '', instruction: s.instruction ?? '' })

function ModeToggle({ mode, onChange, stepsDisabled }: { mode: EditorMode; onChange: (m: EditorMode) => void; stepsDisabled: boolean }) {
  const item = (m: EditorMode, label: string, Icon: typeof List, disabled = false, title?: string) => (
    <button type="button" role="tab" aria-selected={mode === m} disabled={disabled} title={title} onClick={() => onChange(m)}
      className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
        mode === m ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-700 dark:text-gray-100' : 'text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
      <Icon className="h-3.5 w-3.5" />{label}
    </button>
  )
  return (
    <div role="tablist" aria-label="Editor" className="flex shrink-0 items-center gap-0.5 self-center rounded-lg bg-gray-100 p-0.5 dark:bg-gray-800">
      {item('steps', 'Steps', List, stepsDisabled, stepsDisabled ? 'This workflow uses branches or service actions, so it can only be edited on the canvas or wires.' : undefined)}
      {item('canvas', 'Canvas', Network)}
      {item('wires', 'Wires', Cable)}
    </div>
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
