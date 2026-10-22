import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Check, Copy, Plus, Sparkles, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { TeamTrailList, groupByTeam } from '@/components/agents/agentParts'
import type { AgentDto, RunStepDto, TeamStep } from '@/types'
import {
  MAX_CASES, NODE_META, casesOf, exitsOf, fieldIssues, fromDuration, issues as findIssues, nextCaseId, setNext, targetsFor, toDuration, updateNode, upstreamOf,
  type CanvasGraph, type CanvasNode, type ExitKey, type SwitchCase,
} from './canvasModel'
import { NODE_ICON, exitLabel } from './canvasParts'
import { useAssistantStore } from '@/jizza/assistantStore'
import { registerStepTarget, useContextStore, useJizzaContext } from '@/jizza/contextStore'
import { stepContext } from '@/jizza/builderContext'
import { canConfigureWithJizza, startersFor } from '@/jizza/commands'

/** A service action the user can pick, with the service it belongs to. */
export interface ToolOption { id: string; label: string }

/** Another workflow a step can run. */
export interface WorkflowOption { id: string; name: string; status: string }

/** Said under every field that takes {{ placeholders }}. */
const TEMPLATE_HINT = 'Use {{ expression }} to put in values from input, nodes, vars or instructions.'

/** What the "go to" picker for an exit is called. */
function exitPrompt(b: ExitKey, node: CanvasNode): string {
  if (b === null) return 'Then go to'
  if (b === 'true') return 'If yes, go to'
  if (b === 'false') return 'If no, go to'
  if (b === 'approved') return 'If approved, go to'
  if (b === 'rejected') return 'If rejected, go to'
  if (b === 'else') return 'Otherwise, go to'
  if (node.type === 'SWITCH') return `If "${exitLabel(b, node)}", go to`
  return `Path ${exitLabel(b, node).slice(5)} goes to`
}

/** The variables earlier set-variable steps keep, which a step can read as vars.<name>. */
function varsOf(nodes: CanvasNode[]): string[] {
  const names = nodes.filter(n => n.type === 'SET_VARIABLE')
    .flatMap(n => (Array.isArray(n.config.variables) ? n.config.variables as Array<{ name?: unknown }> : []).map(v => String(v?.name ?? '').trim()))
  return [...new Set(names.filter(Boolean))]
}

/** The hand-offs an agent step recorded in its output, when it asked its team for help. */
function teamOf(output: unknown): TeamStep[] {
  const team = output && typeof output === 'object' ? (output as { team?: unknown }).team : undefined
  return Array.isArray(team) ? (team as TeamStep[]) : []
}

/**
 * Settings for the selected step: what it does, where it goes next, and how it did in the last run.
 *
 * Changes are kept here until Save, which hands back the edited graph; Close drops them. Save stays
 * off until every mandatory field (see {@link fieldIssues}) has a value.
 */
export function CanvasInspector({ node: opened, graph: canvas, agents, tools, workflows, issues: extraIssues, step, readOnly, focusNote, askJizza, onDirty, onSave, onClose }: {
  node: CanvasNode; graph: CanvasGraph; agents?: AgentDto[]; tools?: ToolOption[]; workflows?: WorkflowOption[]
  /** Problems the step has beyond its settings, such as an agent that is paused. */
  issues: string[]
  step?: RunStepDto; readOnly: boolean
  /** Opens with the note field in focus ("Add note" on the step's menu). */
  focusNote?: boolean
  /** Opens with Jizza working on this step ("Configure using Jizza" on the step's menu). */
  askJizza?: boolean
  /** Told whether there are unsaved changes, so leaving the step can ask first. */
  onDirty: (dirty: boolean) => void
  onSave: (edited: CanvasGraph) => void
  onClose: () => void
}) {
  const [graph, setGraph] = useState(canvas)
  const [dirty, setDirty] = useState(false)
  const node = graph.nodes.find(n => n.id === opened.id) ?? opened
  const onChange = (g: CanvasGraph, _key?: string) => { setGraph(g); setDirty(true) }
  useEffect(() => { onDirty(dirty) }, [dirty, onDirty])
  useEffect(() => () => onDirty(false), [onDirty])
  const note = useRef<HTMLTextAreaElement>(null)
  useEffect(() => { if (focusNote) note.current?.focus() }, [focusNote])

  const missing = fieldIssues(node)
  const isMissing = (field: string) => missing.some(m => m.field === field || m.field.startsWith(`${field}.`))
  const issues = [...findIssues(graph).filter(i => i.nodeId === node.id).map(i => i.message), ...extraIssues]
  const setConfig = (patch: Record<string, unknown>, _key?: string) =>
    onChange(updateNode(graph, node.id, { config: { ...node.config, ...patch } }))
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  const targets = targetsFor(graph, node.id)
  const nextOf = (b: ExitKey) => graph.edges.find(e => e.from === node.id && e.branch === b)?.to ?? ''
  const upstream = upstreamOf(graph, node.id).filter(n => n.type !== 'TRIGGER')
  const Icon = NODE_ICON[node.type]
  const location = useLocation()
  const current = agents?.find(a => a.id === str('agentId'))
  const vars = varsOf(upstream)
  const close = () => {
    if (dirty && !window.confirm('Close without saving your changes to this step?')) return
    onClose()
  }

  // ---- Configure using Jizza ---------------------------------------------------------------
  // While on, Jizza is told about this step and what it may set, and what it fills in lands here
  // as unsaved changes for the user to review and Save. It never saves the step itself.
  const [jizzaOn, setJizzaOn] = useState(false)
  const [filledByJizza, setFilledByJizza] = useState(false)
  /** Bumped when Jizza fills fields in, so fields that keep their own rows read the new values. */
  const [revision, setRevision] = useState(0)
  const workflowName = useContextStore(s => s.stack.find(e => e.ctx.kind === 'builder')?.ctx.detail)
  const latest = useRef(graph)
  latest.current = graph
  const jizzaAvailable = !readOnly && canConfigureWithJizza(node.type)
  const jizzaToken = useJizzaContext(jizzaOn && jizzaAvailable ? stepContext(node, graph, workflowName, {
    agents, tools, workflows, missing: missing.map(m => m.message),
  }) : null)
  // The chip's ✕ (or /leave) hands Jizza back to the Builder; the button here shows it is off.
  const jizzaLeft = useContextStore(s => jizzaToken !== undefined && !s.stack.some(e => e.token === jizzaToken))
  useEffect(() => { if (jizzaLeft) setJizzaOn(false) }, [jizzaLeft])
  useEffect(() => { if (askJizza && jizzaAvailable) setJizzaOn(true) }, [askJizza, jizzaAvailable])
  useEffect(() => {
    if (!jizzaOn) return
    useAssistantStore.getState().startStepSession(NODE_META[node.type].label, node.label, startersFor(node.type))
    return registerStepTarget(node.id, (patch, label) => {
      const g = latest.current
      const current = g.nodes.find(n => n.id === node.id)
      if (!current) return false
      const config: Record<string, unknown> = { ...current.config }
      for (const [k, v] of Object.entries(patch)) {
        if (k === 'ui') continue
        if (v === null) { delete config[k]; continue }
        if (k === 'auth' && v && typeof v === 'object') {
          // A credential Jizza was only told is set comes back as that placeholder: keep the real one.
          const was = (current.config.auth ?? {}) as Record<string, unknown>
          config.auth = Object.fromEntries(Object.entries(v as Record<string, unknown>)
            .map(([ak, av]) => [ak, av === '(set, hidden)' ? was[ak] : av]))
          continue
        }
        config[k] = v
      }
      onChange(updateNode(g, node.id, { config, ...(label && label.trim() ? { label: label.trim() } : {}) }))
      setRevision(r => r + 1)
      setFilledByJizza(true)
      return true
    })
    // Started once each time it is turned on, for the step this panel is for.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jizzaOn, node.id])

  return (
    <aside aria-label="Step settings" className="absolute inset-y-0 right-0 z-10 flex w-80 flex-col border-l shadow-xl border-gray-200 bg-white animate-fade-in dark:border-gray-800 dark:bg-gray-900">
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
      <div className="flex items-center gap-2">
        <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold ${NODE_META[node.type].tone}`}>
          <Icon className="h-3 w-3" />{NODE_META[node.type].label}
        </span>
        <code className="truncate text-[11px] text-gray-400" title="This step's id, used to read its result as nodes.<id>">{node.id}</code>
        <span className="flex-1" />
        {jizzaAvailable && (
          <button type="button" onClick={() => (jizzaOn ? useAssistantStore.getState().setDockOpen(true) : setJizzaOn(true))}
            title={jizzaOn ? 'Jizza is working on this step' : 'Configure using Jizza'} aria-label="Configure using Jizza" aria-pressed={jizzaOn}
            className={`inline-flex h-7 shrink-0 items-center gap-1 rounded-md px-2 text-[11px] font-medium transition-colors ${jizzaOn
              ? 'bg-brand-500 text-white'
              : 'text-brand-700 ring-1 ring-inset ring-brand-500/30 hover:bg-brand-50 dark:text-brand-300 dark:hover:bg-brand-500/10'}`}>
            <Sparkles className="h-3.5 w-3.5" />{jizzaOn ? 'Jizza is on it' : 'Configure using Jizza'}
          </button>
        )}
        <button type="button" onClick={close} aria-label="Close step settings"
          className="grid h-7 w-7 place-items-center rounded-md text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><X className="h-4 w-4" /></button>
      </div>

      {filledByJizza && (
        <p className="flex items-start gap-1.5 rounded-lg bg-brand-50 px-3 py-2 text-xs text-brand-800 ring-1 ring-inset ring-brand-500/20 animate-fade-in dark:bg-brand-500/10 dark:text-brand-200">
          <Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>Jizza filled in these settings. Check them, then press Save to keep them.</span>
        </p>
      )}

      {issues.length > 0 && (
        <ul className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700 dark:bg-red-500/10 dark:text-red-300">
          {issues.map(i => <li key={i}>{i}</li>)}
        </ul>
      )}

      <fieldset key={revision} disabled={readOnly} className={`flex flex-col gap-3 ${revision > 0 ? 'rounded-lg animate-jizza-filled' : ''}`}>
        <Labelled label="Name">
          <input className={fieldClass} value={node.label} onChange={e => onChange(updateNode(graph, node.id, { label: e.target.value }), `${node.id}:label`)} />
        </Labelled>

        {node.type === 'AGENT_STEP' && <>
          <Labelled label="Agent" required missing={isMissing('agentId')}>
            <select className={fieldClass} value={str('agentId')} onChange={e => setConfig({ agentId: e.target.value || undefined })}>
              <option value="">Choose an agent…</option>
              {current && (current.status === 'archived' || current.status === 'draft') && (
                <option value={current.id}>{current.name} ({current.status})</option>
              )}
              {groupByTeam(agents ?? []).map(g => (
                <optgroup key={g.label} label={g.label}>
                  {g.agents.map(a => (
                    <option key={a.id} value={a.id} disabled={!a.enabled && a.id !== current?.id}>
                      {a.name}{a.custom ? ' · custom' : a.domain ? ` · ${a.domain}` : ''}{a.enabled ? '' : ' (paused)'}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            {!readOnly && (
              <Link to="/agents/new" state={{ returnTo: `${location.pathname}${location.search}` }}
                className="mt-1 inline-block text-xs text-brand-600 hover:underline dark:text-brand-300">+ New agent</Link>
            )}
          </Labelled>
          <Labelled label="What should the agent do?" required missing={isMissing('instruction')}>
            <textarea className={fieldClass} rows={4} value={str('instruction')} onChange={e => setConfig({ instruction: e.target.value })} />
          </Labelled>
        </>}

        {node.type === 'HUMAN_INTERVENTION' && (
          <Labelled label="What should it ask you?">
            <textarea className={fieldClass} rows={3} value={str('prompt')} onChange={e => setConfig({ prompt: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'CONNECTOR_TOOL' && <>
          <Labelled label="Service action" required missing={isMissing('toolId')} hint={tools && tools.length === 0 ? 'No connected services have actions yet. Add one under Connectors.' : undefined}>
            <select className={fieldClass} value={str('toolId')}
              onChange={e => setConfig({ toolId: e.target.value || undefined, toolName: tools?.find(t => t.id === e.target.value)?.label })}>
              <option value="">Choose an action…</option>
              {str('toolId') && !tools?.some(t => t.id === str('toolId')) && <option value={str('toolId')}>{str('toolName') || 'Current action'}</option>}
              {tools?.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
            </select>
          </Labelled>
          <JsonField key={node.id} label="Arguments (JSON)" value={node.config.arguments} onChange={v => setConfig({ arguments: v })} />
        </>}

        {node.type === 'TRANSFORM' && (
          <Labelled label="Expression" required missing={isMissing('expression')} hint="JEXL over input, nodes, vars and instructions. fmt: helpers format values, e.g. fmt:upper(s) or fmt:round(n, 2).">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={str('expression')} onChange={e => setConfig({ expression: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'BRANCH' && (
          <Labelled label="Condition" required missing={isMissing('condition')} hint="A JEXL test over input, nodes and vars, e.g. nodes.check.price < 2000">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'PARALLEL' && <p className="text-[11px] text-gray-500">Every connected path runs. A step that several paths lead to waits for all of them.</p>}
        {node.type === 'JOIN' && <p className="text-[11px] text-gray-500">Carries on once every path leading here has finished (or was skipped by a branch). Later steps read each path's result as nodes.{node.id}.&lt;step id&gt;.</p>}

        {node.type === 'WAIT' && <WaitFields node={node} setConfig={setConfig} />}

        {(node.type === 'SUB_WORKFLOW' || node.type === 'FOR_EACH') && <>
          <Labelled label="Workflow to run" required missing={isMissing('workflowId')} hint={node.type === 'FOR_EACH' ? 'It runs once per item, getting {"item", "index"} as its input.' : 'It must be approved to run when nobody is testing.'}>
            <select className={fieldClass} value={str('workflowId')}
              onChange={e => setConfig({ workflowId: e.target.value || undefined, workflowName: workflows?.find(w => w.id === e.target.value)?.name })}>
              <option value="">Choose a workflow…</option>
              {str('workflowId') && !workflows?.some(w => w.id === str('workflowId')) && <option value={str('workflowId')}>{str('workflowName') || 'Current workflow'}</option>}
              {workflows?.map(w => <option key={w.id} value={w.id}>{w.name}{w.status === 'approved' || w.status === 'APPROVED' ? '' : ` (${w.status.toLowerCase().replace(/_/g, ' ')})`}</option>)}
            </select>
          </Labelled>
          {node.type === 'FOR_EACH' && <>
            <Labelled label="List to go through" required missing={isMissing('items')} hint="A JEXL expression that gives a list, e.g. nodes.fetch.invoices">
              <input className={`${fieldClass} font-mono text-xs`} value={str('items')} onChange={e => setConfig({ items: e.target.value })} />
            </Labelled>
            <Labelled label="Items at a time">
              <input type="number" min={1} max={10} className={fieldClass} value={typeof node.config.concurrency === 'number' ? node.config.concurrency : 1}
                onChange={e => setConfig({ concurrency: Math.max(1, Math.min(10, Number(e.target.value) || 1)) })} />
            </Labelled>
            <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
              <input type="checkbox" checked={node.config.continueOnError === true} onChange={e => setConfig({ continueOnError: e.target.checked || undefined })} />
              Keep going when an item fails
            </label>
          </>}
          {node.type === 'SUB_WORKFLOW' && (
            <Labelled label="Input (optional)" hint="A JEXL expression for the workflow's input, e.g. {'amount': nodes.check.total}">
              <input className={`${fieldClass} font-mono text-xs`} value={str('inputExpr')} onChange={e => setConfig({ inputExpr: e.target.value || undefined })} />
            </Labelled>
          )}
        </>}

        {node.type === 'APPROVAL' && <ApprovalFields node={node} setConfig={setConfig} />}

        {node.type === 'HTTP_REQUEST' && <WebhookFields node={node} setConfig={setConfig} isMissing={isMissing} />}

        {node.type === 'FILTER' && (
          <Labelled label="Carry on only if" required missing={isMissing('condition')} hint="A JEXL test over input, nodes and vars. When it is false this path stops here.">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'SWITCH' && <SwitchFields node={node} graph={graph} onChange={onChange} />}

        {node.type === 'SET_VARIABLE' && <VariableFields node={node} setConfig={setConfig} />}

        {node.type === 'STORAGE' && <StorageFields node={node} setConfig={setConfig} />}

        {node.type === 'NOTIFY' && <>
          <Labelled label="Title">
            <input className={fieldClass} value={str('title')} onChange={e => setConfig({ title: e.target.value || undefined })} />
          </Labelled>
          <Labelled label="Message" required missing={isMissing('message')} hint={TEMPLATE_HINT}>
            <textarea className={fieldClass} rows={3} value={str('message')} onChange={e => setConfig({ message: e.target.value })} />
          </Labelled>
          <Labelled label="Send to">
            <select className={fieldClass} value={str('channel') || 'app'} onChange={e => setConfig({ channel: e.target.value })}>
              <option value="app">The app</option>
              <option value="email">Email</option>
              <option value="both">The app and email</option>
            </select>
          </Labelled>
        </>}

        {exitsOf(node).map(b => (
          <Labelled key={String(b)} label={exitPrompt(b, node)}>
            <select className={fieldClass} value={nextOf(b)} onChange={e => onChange(setNext(graph, node.id, b, e.target.value || null))}>
              <option value="">Nothing (the run ends here)</option>
              {targets.map(t => <option key={t.id} value={t.id}>{t.label} · {NODE_META[t.type].label}</option>)}
            </select>
          </Labelled>
        ))}
      </fieldset>

      {upstream.length > 0 && node.type !== 'TRIGGER' && (
        <div>
          <p className="text-xs font-medium text-gray-700 dark:text-gray-300">Data from earlier steps</p>
          <p className="mb-1.5 text-[11px] text-gray-400">Click to copy a reference for an expression or argument.</p>
          <div className="flex flex-wrap gap-1">
            {upstream.map(n => <CopyChip key={n.id} text={`nodes.${n.id}`} label={n.label} />)}
            {vars.map(v => <CopyChip key={`vars.${v}`} text={`vars.${v}`} label={`vars.${v}`} />)}
          </div>
        </div>
      )}

      {step && (
        <div>
          <p className="text-xs font-medium text-gray-700 dark:text-gray-300">
            Last run: <span className={step.status === 'FAILED' ? 'text-red-600' : step.status === 'DONE' ? 'text-emerald-600' : 'text-gray-500'}>
              {step.status.toLowerCase()}</span>{step.attempts > 1 ? ` after ${step.attempts} tries` : ''}
          </p>
          {teamOf(step.output).length > 0 && (
            <div className="mt-1">
              <p className="text-xs text-gray-500">Who it handed work to</p>
              <TeamTrailList steps={teamOf(step.output)} />
            </div>
          )}
          {step.output !== undefined && step.output !== null && (
            <pre className="mt-1 max-h-56 overflow-auto rounded-lg bg-gray-50 p-2 text-[11px] text-gray-700 dark:bg-gray-950 dark:text-gray-300">
              {typeof step.output === 'string' ? step.output : JSON.stringify(step.output, null, 2)}
            </pre>
          )}
        </div>
      )}

      {node.type !== 'TRIGGER' && (
        <Labelled label="Note" hint="Shown on the step in the canvas. It does not change what the step does.">
          <textarea ref={note} className={fieldClass} rows={2} disabled={readOnly} value={str('note')}
            onChange={e => setConfig({ note: e.target.value || undefined })} />
        </Labelled>
      )}
    </div>

      <footer className="flex flex-col gap-1.5 border-t border-gray-200 p-3 dark:border-gray-800">
        {!readOnly && missing.length > 0 && (
          <p className="text-[11px] text-gray-500">Fill in the fields marked <span className="text-red-500">*</span> to save.</p>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" size="sm" onClick={close}>Close</Button>
          {!readOnly && (
            <Button type="button" size="sm" disabled={missing.length > 0}
              title={missing.length ? missing.map(m => m.message).join('\n') : undefined}
              onClick={() => onSave(graph)}>Save</Button>
          )}
        </div>
      </footer>
    </aside>
  )
}

/** The payload types a webhook can send, as Zoho Flow and Zapier offer them. */
const BODY_TYPES = [
  { value: 'json', label: 'JSON' },
  { value: 'form', label: 'Form data' },
  { value: 'raw', label: 'Raw (text, XML, …)' },
  { value: 'expression', label: 'Expression' },
  { value: 'none', label: 'No body' },
] as const

const CONTENT_TYPES = ['application/json', 'application/xml', 'text/xml', 'text/plain', 'text/html', 'text/csv']

/** A webhook step: request, query parameters, sign-in, headers, payload and how to read the answer. */
function WebhookFields({ node, setConfig, isMissing }: { node: CanvasNode; setConfig: SetConfig; isMissing: (field: string) => boolean }) {
  const c = node.config
  const s = (k: string) => (typeof c[k] === 'string' ? (c[k] as string) : '')
  const method = s('method') || 'POST'
  // Settings from before payload types existed read as what they would send.
  const bodyType = s('bodyType') || (s('bodyExpr') ? 'expression' : s('body') ? 'raw' : 'json')
  const auth = (c.auth && typeof c.auth === 'object' ? c.auth : {}) as Record<string, string>
  const authType = auth.type || 'none'
  const setAuth = (patch: Record<string, string | undefined>) => setConfig({ auth: { ...auth, ...patch } })
  const contentType = s('contentType')
  const customType = !!contentType && !CONTENT_TYPES.includes(contentType)

  return <>
    <Labelled label="URL" required missing={isMissing('url')} hint={TEMPLATE_HINT}>
      <div className="flex gap-2">
        <select aria-label="Method" className={`${fieldClass} w-24 shrink-0`} value={method} onChange={e => setConfig({ method: e.target.value })}>
          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(m => <option key={m} value={m}>{m}</option>)}
        </select>
        <input aria-label="URL" placeholder="https://…" className={`${fieldClass} font-mono text-xs`} value={s('url')} onChange={e => setConfig({ url: e.target.value })} />
      </div>
    </Labelled>

    <Section title="Query parameters">
      <PairsField key={`${node.id}:query`} value={c.queryParams} onChange={v => setConfig({ queryParams: v })} namePlaceholder="name" valuePlaceholder="value or {{ … }}" addLabel="Add a parameter" />
    </Section>

    <Section title="Authentication">
      <select aria-label="Authentication" className={fieldClass} value={authType}
        onChange={e => setConfig({ auth: e.target.value === 'none' ? undefined : { type: e.target.value, ...(e.target.value === 'apiKey' ? { in: 'header' } : {}) } })}>
        <option value="none">None</option>
        <option value="basic">Basic (username and password)</option>
        <option value="bearer">Bearer token</option>
        <option value="apiKey">API key</option>
      </select>
      {authType === 'basic' && <>
        <Labelled label="Username" required missing={isMissing('auth.username')}>
          <input className={fieldClass} autoComplete="off" value={auth.username ?? ''} onChange={e => setAuth({ username: e.target.value })} />
        </Labelled>
        <Labelled label="Password">
          <input type="password" className={fieldClass} autoComplete="new-password" value={auth.password ?? ''} onChange={e => setAuth({ password: e.target.value })} />
        </Labelled>
      </>}
      {authType === 'bearer' && (
        <Labelled label="Token" required missing={isMissing('auth.token')} hint="Sent as Authorization: Bearer …">
          <input type="password" className={`${fieldClass} font-mono text-xs`} autoComplete="new-password" value={auth.token ?? ''} onChange={e => setAuth({ token: e.target.value })} />
        </Labelled>
      )}
      {authType === 'apiKey' && <>
        <div className="flex gap-2">
          <Labelled label="Key name" required missing={isMissing('auth.name')}>
            <input className={`${fieldClass} font-mono text-xs`} placeholder="X-API-Key" value={auth.name ?? ''} onChange={e => setAuth({ name: e.target.value })} />
          </Labelled>
          <Labelled label="Send in">
            <select className={fieldClass} value={auth.in ?? 'header'} onChange={e => setAuth({ in: e.target.value })}>
              <option value="header">Header</option>
              <option value="query">Query</option>
            </select>
          </Labelled>
        </div>
        <Labelled label="Key" required missing={isMissing('auth.value')}>
          <input type="password" className={`${fieldClass} font-mono text-xs`} autoComplete="new-password" value={auth.value ?? ''} onChange={e => setAuth({ value: e.target.value })} />
        </Labelled>
      </>}
      {authType !== 'none' && (
        <p className="text-[11px] text-gray-400">Saved with the workflow. For a service you sign in to, a connector keeps the credential out of it. Values can use {'{{ vars.… }}'}.</p>
      )}
    </Section>

    <Section title="Headers">
      <PairsField key={`${node.id}:headers`} value={c.headers} onChange={v => setConfig({ headers: v })} namePlaceholder="Header" valuePlaceholder="value or {{ … }}" addLabel="Add a header" />
    </Section>

    {method !== 'GET' && method !== 'DELETE' && (
      <Section title="Payload">
        <Labelled label="Send as">
          <select className={fieldClass} value={bodyType} onChange={e => setConfig({ bodyType: e.target.value })}>
            {BODY_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </Labelled>
        {(bodyType === 'json' || bodyType === 'form') && <>
          <PairsField key={`${node.id}:fields`} value={c.fields} onChange={v => setConfig({ fields: v })} namePlaceholder="key" valuePlaceholder="value or {{ … }}" addLabel="Add a field" />
          {bodyType === 'json' && <>
            <p className="text-[11px] text-gray-400">A value that is just {'{{ … }}'} keeps its type: a number, a list or an object.</p>
            <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
              <input type="checkbox" checked={c.unflatten === true} onChange={e => setConfig({ unflatten: e.target.checked || undefined })} />
              Nest keys with dots (customer.name becomes {'{"customer": {"name": …}}'})
            </label>
            <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
              <input type="checkbox" checked={c.wrapInArray === true} onChange={e => setConfig({ wrapInArray: e.target.checked || undefined })} />
              Wrap the payload in a list ([ … ])
            </label>
          </>}
        </>}
        {bodyType === 'raw' && <>
          <Labelled label="Content type">
            <select className={fieldClass} value={customType ? 'custom' : contentType}
              onChange={e => setConfig({ contentType: e.target.value === 'custom' ? 'application/' : e.target.value || undefined })}>
              <option value="">Work it out</option>
              {CONTENT_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              <option value="custom">Other…</option>
            </select>
          </Labelled>
          {customType && (
            <input aria-label="Custom content type" className={`${fieldClass} font-mono text-xs`} value={contentType} onChange={e => setConfig({ contentType: e.target.value })} />
          )}
          <Labelled label="Body" required missing={isMissing('body')} hint={TEMPLATE_HINT}>
            <textarea className={`${fieldClass} font-mono text-xs`} rows={5} value={s('body')} onChange={e => setConfig({ body: e.target.value || undefined })} />
          </Labelled>
        </>}
        {bodyType === 'expression' && (
          <Labelled label="Body expression" required missing={isMissing('bodyExpr')} hint="JEXL that gives the body, sent as JSON, e.g. {'total': nodes.sum.value}.">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={s('bodyExpr')} onChange={e => setConfig({ bodyExpr: e.target.value || undefined })} />
          </Labelled>
        )}
      </Section>
    )}

    <Section title="Response">
      <Labelled label="Read the response as" hint={`Later steps read nodes.${node.id}.status, .ok, .headers and .body.`}>
        <select className={fieldClass} value={s('responseType') || 'auto'} onChange={e => setConfig({ responseType: e.target.value === 'auto' ? undefined : e.target.value })}>
          <option value="auto">Work it out (JSON when it is JSON)</option>
          <option value="json">JSON</option>
          <option value="text">Text</option>
        </select>
      </Labelled>
      <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={c.failOnError !== false} onChange={e => setConfig({ failOnError: e.target.checked ? undefined : false })} />
        Fail the step when the server answers with an error
      </label>
    </Section>
  </>
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2 border-t border-gray-100 pt-3 dark:border-gray-800">
      <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{title}</p>
      {children}
    </section>
  )
}

/**
 * Name and value rows, saved as an object. Rows without a name are kept on screen while being
 * typed, but not saved.
 */
function PairsField({ value, onChange, namePlaceholder, valuePlaceholder, addLabel }: {
  value: unknown; onChange: (v: Record<string, string> | undefined) => void
  namePlaceholder: string; valuePlaceholder: string; addLabel: string
}) {
  const [rows, setRows] = useState<Array<{ name: string; value: string }>>(() =>
    value && typeof value === 'object' && !Array.isArray(value)
      ? Object.entries(value as Record<string, unknown>).map(([name, v]) => ({ name, value: typeof v === 'string' ? v : JSON.stringify(v) }))
      : [])
  const save = (next: typeof rows) => {
    setRows(next)
    const named = next.filter(r => r.name.trim())
    onChange(named.length ? Object.fromEntries(named.map(r => [r.name.trim(), r.value])) : undefined)
  }
  return <>
    {rows.map((r, i) => (
      <div key={i} className="flex items-start gap-1.5">
        <input aria-label={`${namePlaceholder} ${i + 1}`} placeholder={namePlaceholder} className={`${fieldClass} w-28 shrink-0 font-mono text-xs`} value={r.name}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
        <input aria-label={`Value ${i + 1}`} placeholder={valuePlaceholder} className={`${fieldClass} font-mono text-xs`} value={r.value}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
        <button type="button" onClick={() => save(rows.filter((_, j) => j !== i))} aria-label={`Remove row ${i + 1}`}
          className="grid h-8 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
      </div>
    ))}
    <button type="button" onClick={() => setRows([...rows, { name: '', value: '' }])}
      className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3.5 w-3.5" /> {addLabel}</button>
  </>
}

/** How long a wait step waits: a length of time, or until a date and time. */
function WaitFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const duration = typeof node.config.duration === 'string' ? node.config.duration : ''
  const until = typeof node.config.until === 'string' ? node.config.until : ''
  const mode = until && !duration ? 'until' : 'for'
  return <>
    <Labelled label="Wait">
      <select className={fieldClass} value={mode}
        onChange={e => setConfig(e.target.value === 'until' ? { duration: undefined, until: until || '' } : { until: undefined, duration: duration || 'PT1H' }, 'waitMode')}>
        <option value="for">for a length of time</option>
        <option value="until">until a date and time</option>
      </select>
    </Labelled>
    {mode === 'for' ? (
      <DurationInput value={duration} onChange={d => setConfig({ duration: d })} />
    ) : (
      <Labelled label="Until" required hint="In India time. The run continues within a minute of it.">
        <input type="datetime-local" className={fieldClass} value={until.slice(0, 16)} onChange={e => setConfig({ until: e.target.value ? `${e.target.value}:00` : '' })} />
      </Labelled>
    )}
  </>
}

/** A length of time as a number and a unit, written as an ISO-8601 duration. */
function DurationInput({ value, onChange }: { value: string; onChange: (iso: string) => void }) {
  const parsed = fromDuration(value)
  return (
    <div className="flex gap-2">
      <input type="number" min={1} aria-label="How long" className={fieldClass} value={parsed?.amount ?? 1}
        onChange={e => onChange(toDuration(Math.max(1, Number(e.target.value) || 1), parsed?.unit ?? 'hours'))} />
      <select aria-label="Unit" className={fieldClass} value={parsed?.unit ?? 'hours'}
        onChange={e => onChange(toDuration(parsed?.amount ?? 1, e.target.value as 'minutes' | 'hours' | 'days'))}>
        <option value="minutes">minutes</option>
        <option value="hours">hours</option>
        <option value="days">days</option>
      </select>
    </div>
  )
}

type SetConfig = (patch: Record<string, unknown>, key?: string) => void

/** What an approval asks, and what happens if nobody answers in time. */
function ApprovalFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const timeout = typeof node.config.timeout === 'string' ? node.config.timeout : ''
  return <>
    <Labelled label="What should it ask you to approve?" hint={TEMPLATE_HINT}>
      <textarea className={fieldClass} rows={3} value={typeof node.config.prompt === 'string' ? node.config.prompt : ''}
        onChange={e => setConfig({ prompt: e.target.value })} />
    </Labelled>
    <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={!!timeout} onChange={e => setConfig({ timeout: e.target.checked ? 'P1D' : undefined, onTimeout: undefined })} />
      Stop waiting after a while
    </label>
    {!!timeout && <>
      <DurationInput value={timeout} onChange={d => setConfig({ timeout: d })} />
      <Labelled label="If nobody answers by then">
        <select className={fieldClass} value={node.config.onTimeout === 'approved' ? 'approved' : 'rejected'}
          onChange={e => setConfig({ onTimeout: e.target.value })}>
          <option value="rejected">treat it as rejected</option>
          <option value="approved">treat it as approved</option>
        </select>
      </Labelled>
    </>}
  </>
}

/** A paths step's cases: checked in order, the first that is true is the path taken. */
function SwitchFields({ node, graph, onChange }: { node: CanvasNode; graph: CanvasGraph; onChange: (g: CanvasGraph, key?: string) => void }) {
  const cases = casesOf(node.config)
  const save = (next: SwitchCase[], key: string, g: CanvasGraph = graph) =>
    onChange(updateNode(g, node.id, { config: { ...node.config, cases: next } }), `${node.id}:${key}`)
  const edit = (i: number, patch: Partial<SwitchCase>, key: string) => save(cases.map((c, j) => (j === i ? { ...c, ...patch } : c)), `${key}:${cases[i].id}`)
  // A removed case's wire goes with it.
  const remove = (id: string) => save(cases.filter(c => c.id !== id), 'removeCase', { ...graph, edges: graph.edges.filter(e => !(e.from === node.id && e.branch === id)) })
  return <>
    <p className="text-[11px] text-gray-500">Checked from the top; the first path whose condition is true runs. If none is, the "otherwise" path runs.</p>
    {cases.map((c, i) => (
      <div key={c.id} className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex items-center gap-1.5">
          <input aria-label={`Name of path ${i + 1}`} placeholder={`Path ${i + 1}`} className={fieldClass} value={c.label} onChange={e => edit(i, { label: e.target.value }, 'caseLabel')} />
          <button type="button" onClick={() => remove(c.id)} aria-label={`Remove path ${i + 1}`} disabled={cases.length <= 1}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 disabled:opacity-40 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
        </div>
        <input aria-label={`Condition for path ${i + 1}`} placeholder="e.g. nodes.check.total > 1000" className={`${fieldClass} font-mono text-xs`} value={c.condition}
          onChange={e => edit(i, { condition: e.target.value }, 'caseCondition')} />
      </div>
    ))}
    {cases.length < MAX_CASES && (
      <button type="button" onClick={() => save([...cases, { id: nextCaseId(cases), label: `Path ${cases.length + 1}`, condition: '' }], 'addCase')}
        className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3.5 w-3.5" /> Add a path</button>
    )}
    <p className="-mt-1 text-[11px] text-gray-400">Conditions are JEXL tests over input, nodes and vars.</p>
  </>
}

/** The variables a set-variable step keeps: a name and a JEXL expression each. */
function VariableFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const rows = (Array.isArray(node.config.variables) ? node.config.variables as Array<Record<string, unknown>> : [])
    .map(v => ({ name: typeof v?.name === 'string' ? v.name : '', expr: typeof v?.expr === 'string' ? v.expr : '' }))
  const save = (next: typeof rows, key: string) => setConfig({ variables: next }, key)
  return <>
    <p className="text-[11px] text-gray-500">Later steps read each one as vars.&lt;name&gt;.</p>
    {rows.map((r, i) => (
      <div key={i} className="flex items-start gap-1.5">
        <input aria-label={`Name of variable ${i + 1}`} placeholder="name" className={`${fieldClass} w-24 shrink-0 font-mono text-xs`} value={r.name}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)), `varName:${i}`)} />
        <input aria-label={`Expression for variable ${i + 1}`} placeholder="nodes.fetch.total" className={`${fieldClass} font-mono text-xs`} value={r.expr}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, expr: e.target.value } : x)), `varExpr:${i}`)} />
        <button type="button" onClick={() => save(rows.filter((_, j) => j !== i), 'removeVar')} aria-label={`Remove variable ${i + 1}`} disabled={rows.length <= 1}
          className="grid h-8 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 disabled:opacity-40 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
      </div>
    ))}
    <button type="button" onClick={() => save([...rows, { name: '', expr: '' }], 'addVar')}
      className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3.5 w-3.5" /> Add a variable</button>
    <p className="-mt-1 text-[11px] text-gray-400">Expressions are JEXL over input, nodes, vars and instructions, with fmt: helpers such as fmt:formatDate(d, 'dd MMM yyyy').</p>
  </>
}

/** A storage step: which action, on which key, with only the fields that action uses. */
function StorageFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const c = node.config
  const action = typeof c.action === 'string' ? c.action : 'get'
  return <>
    <Labelled label="Action">
      <select className={fieldClass} value={action} onChange={e => setConfig({ action: e.target.value })}>
        <option value="get">Read a value</option>
        <option value="set">Save a value</option>
        <option value="increment">Count up a number</option>
        <option value="append">Add to a list</option>
        <option value="delete">Forget a value</option>
      </select>
    </Labelled>
    <Labelled label="Key" required hint={`Values are kept between runs of this workflow under this name. ${TEMPLATE_HINT}`}>
      <input className={`${fieldClass} font-mono text-xs`} value={typeof c.key === 'string' ? c.key : ''} onChange={e => setConfig({ key: e.target.value })} />
    </Labelled>
    {(action === 'set' || action === 'append') && (
      <Labelled label={action === 'set' ? 'Value to save' : 'Value to add'} required hint="A JEXL expression, e.g. nodes.fetch.total">
        <input className={`${fieldClass} font-mono text-xs`} value={typeof c.valueExpr === 'string' ? c.valueExpr : ''} onChange={e => setConfig({ valueExpr: e.target.value })} />
      </Labelled>
    )}
    {action === 'increment' && (
      <Labelled label="Count up by">
        <input type="number" className={fieldClass} value={typeof c.by === 'number' ? c.by : 1}
          onChange={e => setConfig({ by: Number(e.target.value) || 1 })} />
      </Labelled>
    )}
    {action === 'append' && (
      <Labelled label="Keep at most" hint="The oldest items are dropped past this many.">
        <input type="number" min={1} className={fieldClass} value={typeof c.maxItems === 'number' ? c.maxItems : 100}
          onChange={e => setConfig({ maxItems: Math.max(1, Number(e.target.value) || 100) })} />
      </Labelled>
    )}
    {action === 'get' && (
      <JsonField key={`${node.id}:default`} label="If nothing is saved yet (JSON, optional)" value={c.default} hint="Given instead, e.g. 0 or []."
        onChange={v => setConfig({ default: v })} />
    )}
  </>
}

function CopyChip({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button type="button" title={text}
      onClick={() => { void navigator.clipboard?.writeText(text).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1200) }) }}
      className="inline-flex max-w-full items-center gap-1 rounded-md border border-gray-200 bg-gray-50 px-1.5 py-0.5 text-[11px] text-gray-700 hover:border-brand-400 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-300">
      {copied ? <Check className="h-3 w-3 text-emerald-600" /> : <Copy className="h-3 w-3" />}
      <span className="truncate">{label}</span>
    </button>
  )
}

/** Edits a JSON value as text, keeping the text while it doesn't parse. */
function JsonField({ label, value, hint, onChange }: { label: string; value: unknown; hint?: string; onChange: (v: unknown) => void }) {
  const [textValue, setText] = useState(() => (value === undefined ? '' : JSON.stringify(value, null, 2)))
  const [bad, setBad] = useState(false)
  return (
    <Labelled label={label} hint={bad ? 'This is not valid JSON yet.' : hint ?? 'Values can use ${...} to read earlier steps.'}>
      <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={textValue}
        onChange={e => {
          setText(e.target.value)
          if (!e.target.value.trim()) { setBad(false); onChange(undefined); return }
          try { onChange(JSON.parse(e.target.value)); setBad(false) } catch { setBad(true) }
        }} />
    </Labelled>
  )
}

function Labelled({ label, hint, required, missing, children }: {
  label: string; hint?: string
  /** A mandatory field: marked with *, and Save waits for it. */
  required?: boolean
  /** Whether a mandatory field is still empty or unusable. */
  missing?: boolean
  children: ReactNode
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
        {label}{required && <span className="ml-0.5 text-red-500" aria-hidden>*</span>}
        {required && <span className="sr-only"> (required)</span>}
      </span>
      {children}
      {hint && <span className="text-[11px] text-gray-400">{hint}</span>}
      {missing && <span className="text-[11px] text-red-500">Required</span>}
    </label>
  )
}
