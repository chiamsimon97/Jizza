import { useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Check, Copy, Plus, Trash2, X } from 'lucide-react'
import { fieldClass } from '@/components/ui/SlideOver'
import { TeamTrailList, groupByTeam } from '@/components/agents/agentParts'
import type { AgentDto, RunStepDto, TeamStep } from '@/types'
import {
  MAX_CASES, NODE_META, casesOf, exitsOf, fromDuration, nextCaseId, setNext, targetsFor, toDuration, updateNode, upstreamOf,
  type CanvasGraph, type CanvasNode, type ExitKey, type SwitchCase,
} from './canvasModel'
import { NODE_ICON, exitLabel } from './canvasParts'

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

/** Settings for the selected step: what it does, where it goes next, and how it did in the last run. */
export function CanvasInspector({ node, graph, agents, tools, workflows, issues, step, readOnly, onChange, onRemove, onClose }: {
  node: CanvasNode; graph: CanvasGraph; agents?: AgentDto[]; tools?: ToolOption[]; workflows?: WorkflowOption[]; issues: string[]
  step?: RunStepDto; readOnly: boolean
  /** {@code key} groups keystrokes in one field into a single undo step. */
  onChange: (g: CanvasGraph, key?: string) => void
  onRemove: () => void; onClose: () => void
}) {
  const setConfig = (patch: Record<string, unknown>, key: string) =>
    onChange(updateNode(graph, node.id, { config: { ...node.config, ...patch } }), `${node.id}:${key}`)
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  const targets = targetsFor(graph, node.id)
  const nextOf = (b: ExitKey) => graph.edges.find(e => e.from === node.id && e.branch === b)?.to ?? ''
  const upstream = upstreamOf(graph, node.id).filter(n => n.type !== 'TRIGGER')
  const Icon = NODE_ICON[node.type]
  const location = useLocation()
  const current = agents?.find(a => a.id === str('agentId'))
  const vars = varsOf(upstream)

  return (
    <aside aria-label="Step settings" className="absolute inset-y-0 right-0 z-10 flex w-80 flex-col gap-3 overflow-y-auto border-l shadow-xl border-gray-200 bg-white p-3 animate-fade-in dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center gap-2">
        <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold ${NODE_META[node.type].tone}`}>
          <Icon className="h-3 w-3" />{NODE_META[node.type].label}
        </span>
        <code className="truncate text-[11px] text-gray-400" title="This step's id, used to read its result as nodes.<id>">{node.id}</code>
        <span className="flex-1" />
        <button type="button" onClick={onClose} aria-label="Close step settings"
          className="grid h-7 w-7 place-items-center rounded-md text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"><X className="h-4 w-4" /></button>
      </div>

      {issues.length > 0 && (
        <ul className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700 dark:bg-red-500/10 dark:text-red-300">
          {issues.map(i => <li key={i}>{i}</li>)}
        </ul>
      )}

      <fieldset disabled={readOnly} className="flex flex-col gap-3">
        <Labelled label="Name">
          <input className={fieldClass} value={node.label} onChange={e => onChange(updateNode(graph, node.id, { label: e.target.value }), `${node.id}:label`)} />
        </Labelled>

        {node.type === 'AGENT_STEP' && <>
          <Labelled label="Agent">
            <select className={fieldClass} value={str('agentId')} onChange={e => setConfig({ agentId: e.target.value || undefined }, 'agent')}>
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
          <Labelled label="What should the agent do?">
            <textarea className={fieldClass} rows={4} value={str('instruction')} onChange={e => setConfig({ instruction: e.target.value }, 'instruction')} />
          </Labelled>
        </>}

        {node.type === 'HUMAN_INTERVENTION' && (
          <Labelled label="What should it ask you?">
            <textarea className={fieldClass} rows={3} value={str('prompt')} onChange={e => setConfig({ prompt: e.target.value }, 'prompt')} />
          </Labelled>
        )}

        {node.type === 'CONNECTOR_TOOL' && <>
          <Labelled label="Service action" hint={tools && tools.length === 0 ? 'No connected services have actions yet. Add one under Connectors.' : undefined}>
            <select className={fieldClass} value={str('toolId')}
              onChange={e => setConfig({ toolId: e.target.value || undefined, toolName: tools?.find(t => t.id === e.target.value)?.label }, 'tool')}>
              <option value="">Choose an action…</option>
              {str('toolId') && !tools?.some(t => t.id === str('toolId')) && <option value={str('toolId')}>{str('toolName') || 'Current action'}</option>}
              {tools?.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
            </select>
          </Labelled>
          <JsonField key={node.id} label="Arguments (JSON)" value={node.config.arguments} onChange={v => setConfig({ arguments: v }, 'arguments')} />
        </>}

        {node.type === 'TRANSFORM' && (
          <Labelled label="Expression" hint="JEXL over input, nodes, vars and instructions. fmt: helpers format values, e.g. fmt:upper(s) or fmt:round(n, 2).">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={str('expression')} onChange={e => setConfig({ expression: e.target.value }, 'expression')} />
          </Labelled>
        )}

        {node.type === 'BRANCH' && (
          <Labelled label="Condition" hint="A JEXL test over input, nodes and vars, e.g. nodes.check.price < 2000">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value }, 'condition')} />
          </Labelled>
        )}

        {node.type === 'PARALLEL' && <p className="text-[11px] text-gray-500">Every connected path runs. A step that several paths lead to waits for all of them.</p>}
        {node.type === 'JOIN' && <p className="text-[11px] text-gray-500">Carries on once every path leading here has finished (or was skipped by a branch). Later steps read each path's result as nodes.{node.id}.&lt;step id&gt;.</p>}

        {node.type === 'WAIT' && <WaitFields node={node} setConfig={setConfig} />}

        {(node.type === 'SUB_WORKFLOW' || node.type === 'FOR_EACH') && <>
          <Labelled label="Workflow to run" hint={node.type === 'FOR_EACH' ? 'It runs once per item, getting {"item", "index"} as its input.' : 'It must be approved to run when nobody is testing.'}>
            <select className={fieldClass} value={str('workflowId')}
              onChange={e => setConfig({ workflowId: e.target.value || undefined, workflowName: workflows?.find(w => w.id === e.target.value)?.name }, 'workflow')}>
              <option value="">Choose a workflow…</option>
              {str('workflowId') && !workflows?.some(w => w.id === str('workflowId')) && <option value={str('workflowId')}>{str('workflowName') || 'Current workflow'}</option>}
              {workflows?.map(w => <option key={w.id} value={w.id}>{w.name}{w.status === 'approved' || w.status === 'APPROVED' ? '' : ` (${w.status.toLowerCase().replace(/_/g, ' ')})`}</option>)}
            </select>
          </Labelled>
          {node.type === 'FOR_EACH' && <>
            <Labelled label="List to go through" hint="A JEXL expression that gives a list, e.g. nodes.fetch.invoices">
              <input className={`${fieldClass} font-mono text-xs`} value={str('items')} onChange={e => setConfig({ items: e.target.value }, 'items')} />
            </Labelled>
            <Labelled label="Items at a time">
              <input type="number" min={1} max={10} className={fieldClass} value={typeof node.config.concurrency === 'number' ? node.config.concurrency : 1}
                onChange={e => setConfig({ concurrency: Math.max(1, Math.min(10, Number(e.target.value) || 1)) }, 'concurrency')} />
            </Labelled>
            <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
              <input type="checkbox" checked={node.config.continueOnError === true} onChange={e => setConfig({ continueOnError: e.target.checked || undefined }, 'continueOnError')} />
              Keep going when an item fails
            </label>
          </>}
          {node.type === 'SUB_WORKFLOW' && (
            <Labelled label="Input (optional)" hint="A JEXL expression for the workflow's input, e.g. {'amount': nodes.check.total}">
              <input className={`${fieldClass} font-mono text-xs`} value={str('inputExpr')} onChange={e => setConfig({ inputExpr: e.target.value || undefined }, 'inputExpr')} />
            </Labelled>
          )}
        </>}

        {node.type === 'APPROVAL' && <ApprovalFields node={node} setConfig={setConfig} />}

        {node.type === 'HTTP_REQUEST' && <>
          <div className="flex gap-2">
            <select aria-label="Method" className={`${fieldClass} w-24 shrink-0`} value={str('method') || 'POST'} onChange={e => setConfig({ method: e.target.value }, 'method')}>
              {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(m => <option key={m} value={m}>{m}</option>)}
            </select>
            <input aria-label="URL" placeholder="https://…" className={`${fieldClass} font-mono text-xs`} value={str('url')} onChange={e => setConfig({ url: e.target.value }, 'url')} />
          </div>
          <p className="-mt-2 text-[11px] text-gray-400">{TEMPLATE_HINT}</p>
          <JsonField key={`${node.id}:headers`} label="Headers (JSON, optional)" value={node.config.headers} hint='e.g. {"Authorization": "Bearer {{ vars.token }}"}'
            onChange={v => setConfig({ headers: v }, 'headers')} />
          {(str('method') || 'POST') !== 'GET' && <>
            <Labelled label="Body" hint={`Text to send. ${TEMPLATE_HINT}`}>
              <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={str('body')} disabled={!!str('bodyExpr')}
                onChange={e => setConfig({ body: e.target.value || undefined }, 'body')} />
            </Labelled>
            <Labelled label="…or body as an expression" hint="JEXL that gives the body, sent as JSON, e.g. {'total': nodes.sum.value}. Used instead of the text above.">
              <input className={`${fieldClass} font-mono text-xs`} value={str('bodyExpr')} onChange={e => setConfig({ bodyExpr: e.target.value || undefined }, 'bodyExpr')} />
            </Labelled>
          </>}
          <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
            <input type="checkbox" checked={node.config.failOnError !== false} onChange={e => setConfig({ failOnError: e.target.checked ? undefined : false }, 'failOnError')} />
            Fail the step when the server answers with an error
          </label>
        </>}

        {node.type === 'FILTER' && (
          <Labelled label="Carry on only if" hint="A JEXL test over input, nodes and vars. When it is false this path stops here.">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value }, 'condition')} />
          </Labelled>
        )}

        {node.type === 'SWITCH' && <SwitchFields node={node} graph={graph} onChange={onChange} />}

        {node.type === 'SET_VARIABLE' && <VariableFields node={node} setConfig={setConfig} />}

        {node.type === 'STORAGE' && <StorageFields node={node} setConfig={setConfig} />}

        {node.type === 'NOTIFY' && <>
          <Labelled label="Title">
            <input className={fieldClass} value={str('title')} onChange={e => setConfig({ title: e.target.value || undefined }, 'title')} />
          </Labelled>
          <Labelled label="Message" hint={TEMPLATE_HINT}>
            <textarea className={fieldClass} rows={3} value={str('message')} onChange={e => setConfig({ message: e.target.value }, 'message')} />
          </Labelled>
          <Labelled label="Send to">
            <select className={fieldClass} value={str('channel') || 'app'} onChange={e => setConfig({ channel: e.target.value }, 'channel')}>
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

      {!readOnly && node.type !== 'TRIGGER' && (
        <button type="button" onClick={onRemove}
          className="mt-auto inline-flex items-center justify-center gap-1.5 rounded-lg border border-red-200 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:border-red-500/30 dark:text-red-400 dark:hover:bg-red-500/10">
          <Trash2 className="h-3.5 w-3.5" /> Remove step
        </button>
      )}
    </aside>
  )
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
      <DurationInput value={duration} onChange={d => setConfig({ duration: d }, 'duration')} />
    ) : (
      <Labelled label="Until" hint="In India time. The run continues within a minute of it.">
        <input type="datetime-local" className={fieldClass} value={until.slice(0, 16)} onChange={e => setConfig({ until: e.target.value ? `${e.target.value}:00` : '' }, 'until')} />
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

type SetConfig = (patch: Record<string, unknown>, key: string) => void

/** What an approval asks, and what happens if nobody answers in time. */
function ApprovalFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const timeout = typeof node.config.timeout === 'string' ? node.config.timeout : ''
  return <>
    <Labelled label="What should it ask you to approve?" hint={TEMPLATE_HINT}>
      <textarea className={fieldClass} rows={3} value={typeof node.config.prompt === 'string' ? node.config.prompt : ''}
        onChange={e => setConfig({ prompt: e.target.value }, 'prompt')} />
    </Labelled>
    <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={!!timeout} onChange={e => setConfig({ timeout: e.target.checked ? 'P1D' : undefined, onTimeout: undefined }, 'timeout')} />
      Stop waiting after a while
    </label>
    {!!timeout && <>
      <DurationInput value={timeout} onChange={d => setConfig({ timeout: d }, 'timeout')} />
      <Labelled label="If nobody answers by then">
        <select className={fieldClass} value={node.config.onTimeout === 'approved' ? 'approved' : 'rejected'}
          onChange={e => setConfig({ onTimeout: e.target.value }, 'onTimeout')}>
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
      <select className={fieldClass} value={action} onChange={e => setConfig({ action: e.target.value }, 'action')}>
        <option value="get">Read a value</option>
        <option value="set">Save a value</option>
        <option value="increment">Count up a number</option>
        <option value="append">Add to a list</option>
        <option value="delete">Forget a value</option>
      </select>
    </Labelled>
    <Labelled label="Key" hint={`Values are kept between runs of this workflow under this name. ${TEMPLATE_HINT}`}>
      <input className={`${fieldClass} font-mono text-xs`} value={typeof c.key === 'string' ? c.key : ''} onChange={e => setConfig({ key: e.target.value }, 'key')} />
    </Labelled>
    {(action === 'set' || action === 'append') && (
      <Labelled label={action === 'set' ? 'Value to save' : 'Value to add'} hint="A JEXL expression, e.g. nodes.fetch.total">
        <input className={`${fieldClass} font-mono text-xs`} value={typeof c.valueExpr === 'string' ? c.valueExpr : ''} onChange={e => setConfig({ valueExpr: e.target.value }, 'valueExpr')} />
      </Labelled>
    )}
    {action === 'increment' && (
      <Labelled label="Count up by">
        <input type="number" className={fieldClass} value={typeof c.by === 'number' ? c.by : 1}
          onChange={e => setConfig({ by: Number(e.target.value) || 1 }, 'by')} />
      </Labelled>
    )}
    {action === 'append' && (
      <Labelled label="Keep at most" hint="The oldest items are dropped past this many.">
        <input type="number" min={1} className={fieldClass} value={typeof c.maxItems === 'number' ? c.maxItems : 100}
          onChange={e => setConfig({ maxItems: Math.max(1, Number(e.target.value) || 100) }, 'maxItems')} />
      </Labelled>
    )}
    {action === 'get' && (
      <JsonField key={`${node.id}:default`} label="If nothing is saved yet (JSON, optional)" value={c.default} hint="Given instead, e.g. 0 or []."
        onChange={v => setConfig({ default: v }, 'default')} />
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

function Labelled({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-700 dark:text-gray-300">{label}</span>
      {children}
      {hint && <span className="text-[11px] text-gray-400">{hint}</span>}
    </label>
  )
}
