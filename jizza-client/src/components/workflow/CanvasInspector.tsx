import { useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Check, Copy, Trash2, X } from 'lucide-react'
import { fieldClass } from '@/components/ui/SlideOver'
import { TeamTrailList, groupByTeam } from '@/components/agents/agentParts'
import type { AgentDto, RunStepDto, TeamStep } from '@/types'
import { NODE_META, exitsOf, setNext, targetsFor, updateNode, upstreamOf, type CanvasGraph, type CanvasNode } from './canvasModel'
import { NODE_ICON } from './canvasParts'

/** A service action the user can pick, with the service it belongs to. */
export interface ToolOption { id: string; label: string }

/** The hand-offs an agent step recorded in its output, when it asked its team for help. */
function teamOf(output: unknown): TeamStep[] {
  const team = output && typeof output === 'object' ? (output as { team?: unknown }).team : undefined
  return Array.isArray(team) ? (team as TeamStep[]) : []
}

/** Settings for the selected step: what it does, where it goes next, and how it did in the last run. */
export function CanvasInspector({ node, graph, agents, tools, issues, step, readOnly, onChange, onRemove, onClose }: {
  node: CanvasNode; graph: CanvasGraph; agents?: AgentDto[]; tools?: ToolOption[]; issues: string[]
  step?: RunStepDto; readOnly: boolean
  /** {@code key} groups keystrokes in one field into a single undo step. */
  onChange: (g: CanvasGraph, key?: string) => void
  onRemove: () => void; onClose: () => void
}) {
  const setConfig = (patch: Record<string, unknown>, key: string) =>
    onChange(updateNode(graph, node.id, { config: { ...node.config, ...patch } }), `${node.id}:${key}`)
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  const targets = targetsFor(graph, node.id)
  const nextOf = (b: 'true' | 'false' | null) => graph.edges.find(e => e.from === node.id && e.branch === b)?.to ?? ''
  const upstream = upstreamOf(graph, node.id).filter(n => n.type !== 'TRIGGER')
  const Icon = NODE_ICON[node.type]
  const location = useLocation()
  const current = agents?.find(a => a.id === str('agentId'))

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
          <Labelled label="Expression" hint="JEXL over input, nodes and instructions.">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={4} value={str('expression')} onChange={e => setConfig({ expression: e.target.value }, 'expression')} />
          </Labelled>
        )}

        {node.type === 'BRANCH' && (
          <Labelled label="Condition" hint="A JEXL test, e.g. nodes.check.price < 2000">
            <textarea className={`${fieldClass} font-mono text-xs`} rows={3} value={str('condition')} onChange={e => setConfig({ condition: e.target.value }, 'condition')} />
          </Labelled>
        )}

        {exitsOf(node.type).map(b => (
          <Labelled key={String(b)} label={b === 'true' ? 'If yes, go to' : b === 'false' ? 'If no, go to' : 'Then go to'}>
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
function JsonField({ label, value, onChange }: { label: string; value: unknown; onChange: (v: unknown) => void }) {
  const [textValue, setText] = useState(() => (value === undefined ? '' : JSON.stringify(value, null, 2)))
  const [bad, setBad] = useState(false)
  return (
    <Labelled label={label} hint={bad ? 'This is not valid JSON yet.' : 'Values can use ${...} to read earlier steps.'}>
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
