import { FormEvent, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Sparkles } from 'lucide-react'
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
import { inputSections, moduleInputs, ruleProblem } from '@/components/workflow/moduleContract'
import { DateValueInput } from '@/components/ui/DateValueInput'
import { FileValueInput } from '@/components/ui/FileValueInput'
import { RowsInput, rowsEmpty } from '@/components/ui/RowsInput'

/** A step as Jizza drafts it: an agent doing something, or a question for the user. */
interface DraftStep {
  kind: 'agent' | 'ask'
  agentId: string
  instruction: string
}

/** Jizza's drafted steps, with agent names or keys resolved to agents. */
function draftSteps(raw: unknown, agents: AgentDto[] | undefined): DraftStep[] {
  if (!Array.isArray(raw)) return []
  return raw.map((s: Record<string, unknown>) => ({
    kind: s?.kind === 'ask' ? 'ask' : 'agent',
    agentId: matchAgent(agents, s?.agent_key ?? s?.agent)?.id ?? '',
    instruction: str(s?.instruction),
  }))
}

/** Turns a list of drafted steps into the engine's graph: start, each step in order, end. */
export function toGraph(steps: DraftStep[]) {
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
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [canvas, setCanvas] = useState<CanvasGraph>(emptyGraph)

  // Opened from a saved row: editing that workflow rather than starting a new one.
  const workflowId = str(request?.fields.id) || undefined
  const status = str(request?.fields.status) || 'DRAFT'
  const baseVersion = Number(request?.fields.version) || undefined
  const readOnly = !!workflowId && status !== 'DRAFT'
  // The run shown on the wires: the one started here, else the workflow's latest.
  const [runId, setRunId] = useState<string | null>(null)
  const { data: runs } = useQuery({
    queryKey: ['workflow-runs', workflowId],
    queryFn: () => workflowsApi.latestRuns(workflowId!),
    enabled: !!workflowId,
  })
  const latestRunId = runId ?? runs?.[0]?.id ?? null
  const { data: shownRun } = useQuery({
    queryKey: ['run', latestRunId],
    queryFn: () => workflowsApi.getRun(latestRunId!),
    enabled: !!latestRunId,
    refetchInterval: q => (q.state.data?.status === 'RUNNING' ? 1500 : false),
  })
  // Load whatever Jizza (or a button) opened the form with; re-runs when agents arrive so
  // agent names in Jizza's draft resolve.
  useEffect(() => {
    if (!request) return
    setName(str(request.fields.name))
    setDescription(str(request.fields.description))
    setError('')
    setNotice(str(request.fields.notice))
  }, [request?.nonce, agents])

  useEffect(() => { setRunId(null) }, [request?.nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  // A saved graph opens as it is; a new workflow starts with just Start.
  useEffect(() => {
    if (!request) return
    setCanvas(request.fields.graph ? fromGraph(request.fields.graph) : emptyGraph())
  }, [request?.nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  // Steps Jizza drafted are laid out as wires, once its agent names can be looked up. Agents
  // arriving again later don't redo it, so changes made meanwhile stay.
  const drafted = useRef<{ nonce?: number; resolved: boolean }>({ resolved: false })
  useEffect(() => {
    if (!request || request.fields.graph || !Array.isArray(request.fields.steps)) return
    if (drafted.current.nonce === request.nonce && drafted.current.resolved) return
    drafted.current = { nonce: request.nonce, resolved: !!agents }
    setCanvas(fromGraph(toGraph(draftSteps(request.fields.steps, agents))))
  }, [request?.nonce, agents]) // eslint-disable-line react-hooks/exhaustive-deps

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
      graph: canvasToGraph(canvas),
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
  const declaredInputs = moduleInputs(canvasToGraph(canvas))
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
      setNotice(`Run started (${String(r?.status ?? 'running').toLowerCase().replace(/_/g, ' ')}). Arrows follow it along the wires.`)
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not start the run'),
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    if (readOnly) return
    if (!name.trim()) return setError('Give the workflow a name.')
    const issues = canvasProblems(canvas)
    if (issues.length) return setError(issues.slice(0, 3).join(' '))
    setError('')
    save.mutate()
  }

  const hi = !!request?.byJizza

  // While the form is open Jizza works on this workflow.
  useJizzaContext(request ? builderContext(name.trim(), canvas, canvasProblems(canvas)) : null)

  return (
    <SlideOver
      open={!!request}
      title={workflowId ? name || 'Workflow' : 'New workflow'}
      description={readOnly
        ? `This workflow is ${status.toLowerCase().replace(/_/g, ' ')}, so it is shown read-only here.`
        : 'Drag steps onto a step or a wire and they wire themselves in. Steps run top to bottom. A draft does nothing until you run it.'}
      onClose={close}
      wide
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
        <div className="grid gap-4 sm:grid-cols-2">
          <div className={hi && name ? 'rounded-lg animate-jizza-filled' : ''}>
            <Input label="Name" value={name} onChange={e => setName(e.target.value)} placeholder="Weekly report" />
          </div>
          <Field label="Description" highlight={hi && !!description}>
            <textarea className={fieldClass} rows={1} value={description} onChange={e => setDescription(e.target.value)} placeholder="What this workflow is for" />
          </Field>
        </div>

        </fieldset>
        {workflowId && <LocatorFixPanel workflowId={workflowId} focusNode={focusNode} onApplied={reopenFixed} />}
        <WiresBoard graph={canvas} onChange={setCanvas} readOnly={readOnly} run={shownRun} focusNode={focusNode} />
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
