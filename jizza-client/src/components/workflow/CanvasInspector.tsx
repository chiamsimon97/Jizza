import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ChevronDown, ChevronUp, ExternalLink, Loader2, Lock, Play, Plus, Sparkles, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import { TeamTrailList, groupByTeam } from '@/components/agents/agentParts'
import { useQuery } from '@tanstack/react-query'
import type { AgentDto, Device, DeviceActionSpec, DeviceItem, McpServer, RunStepDto, TeamStep } from '@/types'
import { devicesApi } from '@/api/devices'
import { ElementPreview } from '@/components/web/PageView'
import { webApi, type BrowserAgent, type WebTestResult } from '@/api/web'
import {
  MAX_CASES, NODE_META, buttonPathsAfter, clickedCondition, groupButtonsOf, WEB_ELEMENT_ACTIONS, WEB_ELEMENT_INPUT_ACTIONS, WEB_PALETTE, casesOf, fieldIssues, fromDuration, isInlineScript, issues as findIssues, nextCaseId, recordedItem, toDuration, updateNode,
  type CanvasGraph, type CanvasNode, type FieldIssue, type SwitchCase,
} from './canvasModel'
import { NODE_ICON } from './canvasParts'
import { LayerFields } from './LayerFields'
import { FoldSection, MoreOptions, countOf } from './MoreOptions'
import { GOES_BACK, GoBackFields } from './GoBackFields'
import { dataSourcesFor, type DeclaredField } from './dataMapping'
import { FIELD_TYPES, fieldsOf, isModule, moduleInputs, moduleOutputs, type ModuleField } from './moduleContract'
import type { ValueType } from './formula/functions'
import { schemaFields } from '@/components/connectors/ResultView'
import { MappedField, type MappingContext } from './MappedField'
import { DateValueInput } from '@/components/ui/DateValueInput'
import { ScriptEditor } from './formula/ScriptEditor'
import { formulaApi, type FormulaPreview } from '@/api/formula'
import { functionsApi, type ScriptFunction } from '@/api/functions'
import { queryClient } from '@/lib/queryClient'
import { pictureFields, wantsPictures, withPictures, type PictureField, type Pictures } from './pictureFields'
import { infoOf, resultFields } from '@/components/functions/functionModel'
import { useAssistantStore } from '@/jizza/assistantStore'
import { registerStepTarget, useContextStore, useJizzaContext } from '@/jizza/contextStore'
import { stepContext } from '@/jizza/builderContext'
import { canConfigureWithJizza, startersFor } from '@/jizza/commands'

/** A service action the user can pick, with the service it belongs to. */
export interface ToolOption { id: string; label: string }

/** Another workflow a step can run; its graph says what inputs and outputs it declares. */
export interface WorkflowOption { id: string; name: string; status: string; graph?: unknown }

/** A field with a set width: fieldClass is full width, which would win over a w-* next to it. */
const NARROW_FIELD = fieldClass.replace('w-full', '')

/** Said under every field that takes {{ placeholders }}. */
const TEMPLATE_HINT = 'Type {{, press Ctrl+E, or use {} to put in data from earlier steps.'

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
const WIDTH_KEY = 'jizza.stepSettingsWidth'
const MIN_WIDTH = 360
const DEFAULT_WIDTH = 560

function savedWidth(): number {
  try { return Math.max(MIN_WIDTH, Number(localStorage.getItem(WIDTH_KEY)) || DEFAULT_WIDTH) } catch { return DEFAULT_WIDTH }
}

/** The panel's width, changed by dragging its left edge (or the arrow keys on it) and remembered. */
function usePanelWidth() {
  const [width, setWidth] = useState(savedWidth)
  const panel = useRef<HTMLElement>(null)
  const clamp = (w: number) => {
    const room = panel.current?.parentElement?.clientWidth ?? window.innerWidth
    return Math.round(Math.min(Math.max(w, MIN_WIDTH), Math.max(MIN_WIDTH, room * 0.85)))
  }
  const keep = (w: number) => { try { localStorage.setItem(WIDTH_KEY, String(w)) } catch { /* just not remembered */ } }
  const onPointerDown = (e: React.PointerEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const start = panel.current?.offsetWidth ?? width
    let last = start
    const move = (ev: PointerEvent) => { last = clamp(start + startX - ev.clientX); setWidth(last) }
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); keep(last) }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const next = clamp(width + (e.key === 'ArrowLeft' ? 32 : -32))
    setWidth(next)
    keep(next)
  }
  return { panel, width, handle: { onPointerDown, onKeyDown } }
}

export function CanvasInspector({ node: opened, graph: canvas, agents, tools, mcpServers, functions, workflows, issues: extraIssues, step, runSteps, readOnly, lockedIn, onUnlock, focusNote, askJizza, onDirty, onSave, onBranchByButton, onClose }: {
  node: CanvasNode; graph: CanvasGraph; agents?: AgentDto[]; tools?: ToolOption[]; workflows?: WorkflowOption[]
  /** The MCP servers added under Connectors, for the MCP tool step. */
  mcpServers?: McpServer[]
  /** The person's functions under Functions, for the Script step. */
  functions?: ScriptFunction[]
  /** Every step of the last run, so earlier steps' real output fields can be mapped in. */
  runSteps?: ReadonlyMap<string, RunStepDto>
  /** Problems the step has beyond its settings, such as an agent that is paused. */
  issues: string[]
  step?: RunStepDto; readOnly: boolean
  /** The title of the locked group this step is in; its settings are shown but not editable. */
  lockedIn?: string
  /** Unlocks that group, so the step can be edited. */
  onUnlock?: () => void
  /** Opens with the note field in focus ("Add note" on the step's menu). */
  focusNote?: boolean
  /** Opens with Jizza working on this step ("Configure using Jizza" on the step's menu). */
  askJizza?: boolean
  /** Told whether there are unsaved changes, so leaving the step can ask first. */
  onDirty: (dirty: boolean) => void
  onSave: (edited: CanvasGraph) => void
  /** Saves the step and puts a paths step after it, one path per button of its group. */
  onBranchByButton?: (edited: CanvasGraph) => void
  onClose: () => void
}) {
  const [graph, setGraph] = useState(canvas)
  const { panel, width, handle } = usePanelWidth()
  const [dirty, setDirty] = useState(false)
  const node = graph.nodes.find(n => n.id === opened.id) ?? opened
  const onChange = (g: CanvasGraph, _key?: string) => { setGraph(g); setDirty(true) }
  useEffect(() => { onDirty(dirty) }, [dirty, onDirty])
  useEffect(() => () => onDirty(false), [onDirty])
  const note = useRef<HTMLTextAreaElement>(null)
  useEffect(() => { if (focusNote) note.current?.focus() }, [focusNote])

  const child = node.type === 'SUB_WORKFLOW' ? workflows?.find(w => w.id === node.config.workflowId) : undefined
  // The device catalog is read only when a device step is in the workflow.
  const hasDeviceStep = canvas.nodes.some(n => n.type === 'DEVICE_ACTION')
  const { data: deviceCatalog } = useQuery({ queryKey: ['devices', 'catalog'], queryFn: devicesApi.catalog, enabled: hasDeviceStep, staleTime: Infinity }, queryClient)
  // A module's required inputs that have no default must be mapped; the module is only known here.
  const unmapped = moduleInputs(child?.graph).filter(f => f.required && f.default === undefined && !mappedInputs(node)[f.name]?.trim())
  // Likewise a function's required inputs, for a Script step that runs one.
  const fn = node.type === 'SCRIPT' ? functions?.find(f => f.id === node.config.functionId) : undefined
  const unmappedFn = (fn?.inputs ?? []).filter(f => f.required && f.default === undefined && !mappedInputs(node)[f.name]?.trim())
  const missing = [...fieldIssues(node), ...webInputIssues(node, deviceCatalog), ...[...unmapped, ...unmappedFn].map(f => ({ field: `inputs.${f.name}`, message: `"${node.label}" needs a value for the input "${f.name}".` }))]
  const isMissing = (field: string) => missing.some(m => m.field === field || m.field.startsWith(`${field}.`))
  const issues = [...findIssues(graph).filter(i => i.nodeId === node.id).map(i => i.message), ...extraIssues]
  const setConfig = (patch: Record<string, unknown>, _key?: string) =>
    onChange(updateNode(graph, node.id, { config: { ...node.config, ...patch } }))
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  const outputs = useMemo(() => new Map([...(runSteps ?? new Map<string, RunStepDto>())].map(([id, s]) => [id, s.output])), [runSteps])
  // Connections can't change in here, so the earlier steps are those of the graph it opened with.
  const { data: deviceList } = useQuery({ queryKey: ['devices'], queryFn: devicesApi.list, enabled: hasDeviceStep }, queryClient)
  // Later steps can map the outputs of a tool a device added itself, as of a catalog action.
  const deviceSpecs = useMemo(() => [...(deviceCatalog ?? []), ...ownTools(Array.isArray(deviceList) ? deviceList : []).map(t => t.spec)], [deviceCatalog, deviceList])
  const mapping: MappingContext = useMemo(() => ({
    graph: canvas,
    sources: dataSourcesFor(canvas, opened.id, outputs, n => [...mcpOutputFields(n, mcpServers), ...deviceOutputFields(n, deviceSpecs), ...moduleOutputFields(n, workflows), ...functionOutputFields(n, functions)]),
    sample: sampleOf(canvas, outputs),
  }), [canvas, opened.id, outputs, mcpServers, deviceSpecs, workflows, functions])
  const Icon = NODE_ICON[node.type]
  const location = useLocation()
  const current = agents?.find(a => a.id === str('agentId'))
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
    agents, tools, mcpServers, workflows, functions, missing: missing.map(m => m.message),
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
    <aside ref={panel} aria-label="Step settings" style={{ width }}
      className="absolute inset-y-0 right-0 z-10 flex max-w-full flex-col border-l shadow-xl border-gray-200 bg-white animate-fade-in dark:border-gray-800 dark:bg-gray-900">
    <div role="separator" aria-orientation="vertical" aria-label="Resize step settings" tabIndex={0} {...handle}
      className="absolute inset-y-0 -left-1 z-20 w-2 cursor-col-resize touch-none hover:bg-brand-500/20 focus-visible:bg-brand-500/30 focus-visible:outline-none" />
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto overflow-x-hidden p-4">
      <div className="flex items-center gap-2">
        <span className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-md px-2 py-0.5 text-[11px] font-semibold ${NODE_META[node.type].tone}`}>
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

      {lockedIn !== undefined && (
        <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-inset ring-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200">
          <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span className="flex-1">This step is in the locked group “{lockedIn || 'Untitled group'}”. Unlock the group to change it.</span>
          {onUnlock && <button type="button" onClick={onUnlock} className="shrink-0 font-medium underline underline-offset-2">Unlock</button>}
        </p>
      )}

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

      <fieldset key={revision} disabled={readOnly} className={`flex min-w-0 flex-col gap-3 ${revision > 0 ? 'rounded-lg animate-jizza-filled' : ''}`}>
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

        {node.type === 'MCP_TOOL' && <McpToolFields node={node} servers={mcpServers} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />}
        {node.type === 'DEVICE_ACTION' && (node.config.preset === 'web'
          ? <WebStepFields node={node} catalog={deviceCatalog} setConfig={setConfig} isMissing={isMissing} mapping={mapping}
              branch={node.config.action === 'browser.clickOneOf' && !readOnly && onBranchByButton
                ? { existing: buttonPathsAfter(graph, node.id)?.label, disabled: missing.length > 0, onClick: () => onBranchByButton(graph) }
                : undefined}
              layer={{ graph, onGraph: g => onChange(g) }} />
          : <DeviceActionFields node={node} catalog={deviceCatalog} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />)}

        {node.type === 'TRANSFORM' && (
          <Labelled group label="Expression" required missing={isMissing('expression')} hint="A formula over earlier steps' data. Press {} or Ctrl+E for data, chain functions with a dot, e.g. a name chip then .trim().upper()">
            <MappedField aria-label="Expression" mode="expression" context={mapping} multiline rows={4} mono invalid={isMissing('expression')} value={str('expression')} onChange={v => setConfig({ expression: v })} />
          </Labelled>
        )}

        {node.type === 'BRANCH' && (
          <Labelled group label="Condition" required missing={isMissing('condition')} hint="A formula that is true or false, e.g. a price chip followed by < 2000">
            <MappedField aria-label="Condition" mode="expression" context={mapping} mono invalid={isMissing('condition')} value={str('condition')} onChange={v => setConfig({ condition: v })} />
          </Labelled>
        )}

        {node.type === 'PARALLEL' && <p className="text-[11px] text-gray-500">Every connected path runs at the same time. A step that several paths lead to waits for all of them.</p>}
        {node.type === 'JOIN' && <JoinFields node={node} setConfig={setConfig} />}

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
            <Labelled group label="List to go through" required missing={isMissing('items')} hint="Data that is a list, such as an earlier step's invoices.">
              <MappedField aria-label="List to go through" mode="expression" context={mapping} mono invalid={isMissing('items')} value={str('items')} onChange={v => setConfig({ items: v })} />
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
          <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
            <input type="checkbox" checked={node.config.newBrowserSession === true}
              onChange={e => setConfig({ newBrowserSession: e.target.checked || undefined })} />
            Start a new browser session
          </label>
          {node.type === 'SUB_WORKFLOW' && <ModuleCallFields key={str('workflowId')} node={node} module={child} setConfig={setConfig} isMissing={isMissing} mapping={mapping} str={str} />}
        </>}

        {node.type === 'TRIGGER' && <ContractFields kind="inputs" node={node} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />}
        {node.type === 'SCRIPT' && (isInlineScript(node.config)
          ? <ScriptFields node={node} setConfig={setConfig} isMissing={isMissing} mapping={mapping} functions={functions} />
          : <FunctionCallFields key={str('functionId')} node={node} functions={functions} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />)}
        {node.type === 'END' && <ContractFields kind="outputs" node={node} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />}
        {node.type === 'END' && Array.isArray(node.config.outputs) && node.config.outputs.length > 0 && (
          <Labelled label="Result message" hint="The one line Jizza replies with when the run finishes, e.g. Booked. PNR {{ result.pnr }}, fare {{ result.fare }}. Each output is result.<its name>.">
            <textarea className={fieldClass} rows={2} aria-label="Result message" value={str('resultMessage')} onChange={e => setConfig({ resultMessage: e.target.value })} />
          </Labelled>
        )}

        {node.type === 'APPROVAL' && <ApprovalFields node={node} setConfig={setConfig} mapping={mapping} />}

        {node.type === 'HTTP_REQUEST' && <WebhookFields node={node} setConfig={setConfig} isMissing={isMissing} mapping={mapping} />}

        {node.type === 'FILTER' && (
          <Labelled group label="Carry on only if" required missing={isMissing('condition')} hint="A formula that is true or false. When it is false this path stops here.">
            <MappedField aria-label="Carry on only if" mode="expression" context={mapping} mono invalid={isMissing('condition')} value={str('condition')} onChange={v => setConfig({ condition: v })} />
          </Labelled>
        )}

        {node.type === 'SWITCH' && <SwitchFields node={node} graph={graph} onChange={onChange} mapping={mapping} />}

        {node.type === 'SET_VARIABLE' && <VariableFields node={node} setConfig={setConfig} mapping={mapping} />}

        {node.type === 'STORAGE' && <StorageFields node={node} setConfig={setConfig} mapping={mapping} />}

        {node.type === 'NOTIFY' && <>
          <Labelled group label="Title">
            <MappedField aria-label="Title" mode="template" context={mapping} value={str('title')} onChange={v => setConfig({ title: v || undefined })} />
          </Labelled>
          <Labelled group label="Message" required missing={isMissing('message')} hint={TEMPLATE_HINT}>
            <MappedField aria-label="Message" mode="template" context={mapping} multiline invalid={isMissing('message')} value={str('message')} onChange={v => setConfig({ message: v })} />
          </Labelled>
          <Labelled label="Send to">
            <select className={fieldClass} value={str('channel') || 'app'} onChange={e => setConfig({ channel: e.target.value })}>
              <option value="app">The app</option>
              <option value="email">Email</option>
              <option value="both">The app and email</option>
            </select>
          </Labelled>
        </>}

        {GOES_BACK.has(node.type) && <GoBackFields node={node} graph={graph} setConfig={setConfig} />}

      </fieldset>


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
        <Labelled label="Note" hint="Shown on the step when you hover its pencil mark. It does not change what the step does.">
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

const AUTH_NAMES: Record<string, string> = { none: 'none', basic: 'username and password', bearer: 'bearer token', apiKey: 'API key' }

/** How many name/value rows a query or header setting holds. */
const pairCount = (value: unknown) => value && typeof value === 'object' ? Object.keys(value).length : 0

const CONTENT_TYPES = ['application/json', 'application/xml', 'text/xml', 'text/plain', 'text/html', 'text/csv']

/** A webhook step: request, query parameters, sign-in, headers, payload and how to read the answer. */
function WebhookFields({ node, setConfig, isMissing, mapping }: { node: CanvasNode; setConfig: SetConfig; isMissing: (field: string) => boolean; mapping: MappingContext }) {
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
    <Labelled label="Method">
      <select aria-label="Method" className={fieldClass} value={method} onChange={e => setConfig({ method: e.target.value })}>
        {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(m => <option key={m} value={m}>{m}</option>)}
      </select>
    </Labelled>
    <Labelled group label="URL" required missing={isMissing('url')} hint={TEMPLATE_HINT}>
      <MappedField aria-label="URL" placeholder="https://…" mode="template" context={mapping} mono invalid={isMissing('url')} value={s('url')} onChange={v => setConfig({ url: v })} />
    </Labelled>

    <FoldSection title="Query parameters" summary={countOf(pairCount(c.queryParams), 'parameter')}>
      <PairsField key={`${node.id}:query`} mapping={mapping} value={c.queryParams} onChange={v => setConfig({ queryParams: v })} namePlaceholder="name" valuePlaceholder="value" addLabel="Add a parameter" />
    </FoldSection>

    <FoldSection title="Authentication" summary={AUTH_NAMES[authType] ?? authType}
      hasError={['auth.username', 'auth.token', 'auth.name', 'auth.value'].some(isMissing)}>
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
    </FoldSection>

    <FoldSection title="Headers" summary={countOf(pairCount(c.headers), 'header')}>
      <PairsField key={`${node.id}:headers`} mapping={mapping} value={c.headers} onChange={v => setConfig({ headers: v })} namePlaceholder="Header" valuePlaceholder="value" addLabel="Add a header" />
    </FoldSection>

    {method !== 'GET' && method !== 'DELETE' && (
      <Section title="Payload">
        <Labelled label="Send as">
          <select className={fieldClass} value={bodyType} onChange={e => setConfig({ bodyType: e.target.value })}>
            {BODY_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </Labelled>
        {(bodyType === 'json' || bodyType === 'form') && <>
          <PairsField key={`${node.id}:fields`} mapping={mapping} value={c.fields} onChange={v => setConfig({ fields: v })} namePlaceholder="key" valuePlaceholder="value" addLabel="Add a field" />
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
          <Labelled group label="Body" required missing={isMissing('body')} hint={TEMPLATE_HINT}>
            <MappedField aria-label="Body" mode="template" context={mapping} multiline rows={5} mono invalid={isMissing('body')} value={s('body')} onChange={v => setConfig({ body: v || undefined })} />
          </Labelled>
        </>}
        {bodyType === 'expression' && (
          <Labelled group label="Body expression" required missing={isMissing('bodyExpr')} hint="A formula that gives the body, sent as JSON, e.g. {'total': followed by a chip and }.">
            <MappedField aria-label="Body expression" mode="expression" context={mapping} multiline mono invalid={isMissing('bodyExpr')} value={s('bodyExpr')} onChange={v => setConfig({ bodyExpr: v || undefined })} />
          </Labelled>
        )}
      </Section>
    )}

    <FoldSection title="Response" summary={`read as ${s('responseType') || 'worked out'}${c.failOnError === false ? ', errors carry on' : ''}`}>
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
    </FoldSection>
  </>
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
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
function PairsField({ value, onChange, namePlaceholder, valuePlaceholder, addLabel, mapping }: {
  value: unknown; onChange: (v: Record<string, string> | undefined) => void; mapping: MappingContext
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
        <input aria-label={`${namePlaceholder} ${i + 1}`} placeholder={namePlaceholder} className={`${NARROW_FIELD} h-[2.375rem] w-28 shrink-0 font-mono text-xs`} value={r.name}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
        <div className="min-w-0 flex-1">
          <MappedField aria-label={`Value ${i + 1}`} placeholder={valuePlaceholder} mode="template" context={mapping} mono value={r.value}
            onChange={v => save(rows.map((x, j) => (j === i ? { ...x, value: v } : x)))} />
        </div>
        <button type="button" onClick={() => save(rows.filter((_, j) => j !== i))} aria-label={`Remove row ${i + 1}`}
          className="grid h-[2.375rem] w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
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

/** How long a join waits for the paths still on their way, and what it does when time runs out. */
function JoinFields({ node, setConfig }: { node: CanvasNode; setConfig: SetConfig }) {
  const raw = typeof node.config.timeout === 'string' ? node.config.timeout : ''
  const waitsForever = raw === 'none'
  const timeout = waitsForever || !raw ? 'PT1H' : raw
  return <>
    <p className="text-[11px] text-gray-500">Carries on once every path leading here has finished (or was skipped by a branch). Later steps read each path's result as nodes.{node.id}.&lt;step id&gt;.</p>
    <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={!waitsForever} onChange={e => setConfig({ timeout: e.target.checked ? undefined : 'none' })} />
      Stop waiting after a while
    </label>
    {!waitsForever && <>
      <Labelled group label="Wait at most" hint="The timer starts when the first path gets here. An hour unless you change it.">
        <DurationInput value={timeout} onChange={d => setConfig({ timeout: d === 'PT1H' ? undefined : d })} />
      </Labelled>
      <Labelled label="If some paths have not arrived by then">
        <select className={fieldClass} value={node.config.onTimeout === 'fail' ? 'fail' : 'continue'}
          onChange={e => setConfig({ onTimeout: e.target.value === 'fail' ? 'fail' : undefined })}>
          <option value="continue">carry on with the paths that arrived</option>
          <option value="fail">stop the run as failed</option>
        </select>
      </Labelled>
      {node.config.onTimeout !== 'fail' && <p className="text-[11px] text-gray-500">Steps still running or waiting on the way here are dropped. nodes.{node.id}._timedOut lists the steps it gave up on.</p>}
    </>}
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
function ApprovalFields({ node, setConfig, mapping }: { node: CanvasNode; setConfig: SetConfig; mapping: MappingContext }) {
  const timeout = typeof node.config.timeout === 'string' ? node.config.timeout : ''
  return <>
    <Labelled group label="What should it ask you to approve?" hint={TEMPLATE_HINT}>
      <MappedField aria-label="What should it ask you to approve?" mode="template" context={mapping} multiline value={typeof node.config.prompt === 'string' ? node.config.prompt : ''}
        onChange={v => setConfig({ prompt: v })} />
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
function SwitchFields({ node, graph, onChange, mapping }: { node: CanvasNode; graph: CanvasGraph; onChange: (g: CanvasGraph, key?: string) => void; mapping: MappingContext }) {
  const cases = casesOf(node.config)
  const save = (next: SwitchCase[], key: string, g: CanvasGraph = graph) =>
    onChange(updateNode(g, node.id, { config: { ...node.config, cases: next } }), `${node.id}:${key}`)
  const edit = (i: number, patch: Partial<SwitchCase>, key: string) => save(cases.map((c, j) => (j === i ? { ...c, ...patch } : c)), `${key}:${cases[i].id}`)
  // A removed case's wire goes with it.
  const remove = (id: string) => save(cases.filter(c => c.id !== id), 'removeCase', { ...graph, edges: graph.edges.filter(e => !(e.from === node.id && e.branch === id)) })
  // Paths made by "Branch by button": one per button of the group step before it.
  const byButton = typeof node.config.byButton === 'string' ? node.config.byButton : ''
  const group = byButton ? graph.nodes.find(n => n.id === byButton) : undefined
  const unused = groupButtonsOf(group).filter(b => !cases.some(c => c.condition === clickedCondition(byButton, b)))
  // A new path starts where "otherwise" goes, so the run carries on as before until steps go on it.
  const addFor = (button: string) => {
    const id = nextCaseId(cases)
    const onward = graph.edges.filter(e => e.from === node.id && e.branch === 'else').map(e => ({ from: node.id, to: e.to, branch: id }))
    save([...cases, { id, label: button, condition: clickedCondition(byButton, button) }], 'addCase', { ...graph, edges: [...graph.edges, ...onward] })
  }
  return <>
    {group && (
      <p className="rounded-lg bg-green-50 p-2 text-[11px] text-green-900 dark:bg-green-500/10 dark:text-green-200">
        Each path runs when its button was the one "{group.label}" clicked. Put steps on a path's connection for that button.
        A button without a path of its own (or whose path goes straight on) carries on with "otherwise".
      </p>
    )}
    <p className="text-[11px] text-gray-500">Checked from the top; the first path whose condition is true runs. If none is, the "otherwise" path runs.</p>
    {cases.map((c, i) => (
      <div key={c.id} className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex items-center gap-1.5">
          <input aria-label={`Name of path ${i + 1}`} placeholder={`Path ${i + 1}`} className={fieldClass} value={c.label} onChange={e => edit(i, { label: e.target.value }, 'caseLabel')} />
          <button type="button" onClick={() => remove(c.id)} aria-label={`Remove path ${i + 1}`} disabled={cases.length <= 1}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 disabled:opacity-40 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
        </div>
        <MappedField aria-label={`Condition for path ${i + 1}`} placeholder="e.g. a total chip followed by > 1000" mode="expression" context={mapping} mono value={c.condition}
          onChange={v => edit(i, { condition: v }, 'caseCondition')} />
      </div>
    ))}
    {group && unused.length > 0 && cases.length < MAX_CASES && (
      <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
        Add a path for
        <select aria-label="Add a path for button" className={fieldClass} value="" onChange={e => { if (e.target.value) addFor(e.target.value) }}>
          <option value="">Choose a button…</option>
          {unused.map(b => <option key={b} value={b}>{b}</option>)}
        </select>
      </label>
    )}
    {!group && cases.length < MAX_CASES && (
      <button type="button" onClick={() => save([...cases, { id: nextCaseId(cases), label: `Path ${cases.length + 1}`, condition: '' }], 'addCase')}
        className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3.5 w-3.5" /> Add a path</button>
    )}
    <p className="-mt-1 text-[11px] text-gray-400">Conditions are formulas that are true or false. Press {'{}'} or Ctrl+E to put in data from earlier steps.</p>
  </>
}

/** The variables a set-variable step keeps: a name and a formula each. */
function VariableFields({ node, setConfig, mapping }: { node: CanvasNode; setConfig: SetConfig; mapping: MappingContext }) {
  const rows = (Array.isArray(node.config.variables) ? node.config.variables as Array<Record<string, unknown>> : [])
    .map(v => ({ name: typeof v?.name === 'string' ? v.name : '', expr: typeof v?.expr === 'string' ? v.expr : '' }))
  const save = (next: typeof rows, key: string) => setConfig({ variables: next }, key)
  return <>
    <p className="text-[11px] text-gray-500">Later steps read each one as vars.&lt;name&gt;.</p>
    {rows.map((r, i) => (
      <div key={i} className="flex items-start gap-1.5">
        <input aria-label={`Name of variable ${i + 1}`} placeholder="name" className={`${NARROW_FIELD} w-24 shrink-0 font-mono text-xs`} value={r.name}
          onChange={e => save(rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)), `varName:${i}`)} />
        <div className="min-w-0 flex-1">
          <MappedField aria-label={`Expression for variable ${i + 1}`} placeholder="value" mode="expression" context={mapping} mono value={r.expr}
            onChange={v => save(rows.map((x, j) => (j === i ? { ...x, expr: v } : x)), `varExpr:${i}`)} />
        </div>
        <button type="button" onClick={() => save(rows.filter((_, j) => j !== i), 'removeVar')} aria-label={`Remove variable ${i + 1}`} disabled={rows.length <= 1}
          className="grid h-[2.375rem] w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 disabled:opacity-40 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
      </div>
    ))}
    <button type="button" onClick={() => save([...rows, { name: '', expr: '' }], 'addVar')}
      className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3.5 w-3.5" /> Add a variable</button>
    <p className="-mt-1 text-[11px] text-gray-400">Values are formulas. Press {'{}'} to put in data, then add functions with a dot, e.g. .formatDate('dd MMM').</p>
  </>
}

/** A storage step: which action, on which key, with only the fields that action uses. */
function StorageFields({ node, setConfig, mapping }: { node: CanvasNode; setConfig: SetConfig; mapping: MappingContext }) {
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
    <Labelled group label="Key" required hint={`Values are kept between runs of this workflow under this name. ${TEMPLATE_HINT}`}>
      <MappedField aria-label="Key" mode="template" context={mapping} mono value={typeof c.key === 'string' ? c.key : ''} onChange={v => setConfig({ key: v })} />
    </Labelled>
    {(action === 'set' || action === 'append') && (
      <Labelled group label={action === 'set' ? 'Value to save' : 'Value to add'} required hint="Data from an earlier step, or a formula.">
        <MappedField aria-label={action === 'set' ? 'Value to save' : 'Value to add'} mode="expression" context={mapping} mono value={typeof c.valueExpr === 'string' ? c.valueExpr : ''} onChange={v => setConfig({ valueExpr: v })} />
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


/** What a module step maps into each of the module's inputs. */
function mappedInputs(node: CanvasNode): Record<string, string> {
  const raw = node.config.inputs
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, string> : {}
}

/** What a Script step that runs a function gives later steps: a map's keys, or result. */
function functionOutputFields(node: CanvasNode, functions?: ScriptFunction[]): DeclaredField[] {
  if (node.type !== 'SCRIPT' || !node.config.functionId) return []
  const f = functions?.find(x => x.id === node.config.functionId)
  return f ? resultFields(f).map(r => ({ path: r.path, type: r.type })) : []
}

/**
 * A Script step: pick one of your functions under Functions, map each of its inputs from earlier
 * steps, and see what later steps can map from its result. Test runs it with the last run's data.
 */
function FunctionCallFields({ node, functions, setConfig, isMissing, mapping }: {
  node: CanvasNode; functions?: ScriptFunction[]; setConfig: SetConfig; isMissing: (f: string) => boolean; mapping: MappingContext
}) {
  const id = typeof node.config.functionId === 'string' ? node.config.functionId : ''
  const fn = functions?.find(f => f.id === id)
  const values = mappedInputs(node)
  const [result, setResult] = useState<FormulaPreview | null>(null)
  const [testing, setTesting] = useState(false)
  const set = (name: string, v: string) => {
    const next = { ...values }
    if (v) next[name] = v
    else delete next[name]
    setConfig({ inputs: next }, `inputs:${name}`)
  }
  const pick = (value: string) => {
    const f = functions?.find(x => x.id === value)
    // Inputs the new function also takes keep their mapping.
    const kept = Object.fromEntries(Object.entries(values).filter(([k]) => f?.inputs.some(i => i.name === k)))
    setConfig({ functionId: value || undefined, functionName: f?.name, inputs: kept })
    setResult(null)
  }
  const test = async () => {
    setTesting(true)
    try {
      setResult(await formulaApi.previewScript({ functionId: id, inputs: values }, mapping.sample ?? { input: {}, nodes: {} }))
    } catch (e) {
      setResult({ value: null, type: null, error: e instanceof Error ? e.message : 'The test could not run.' })
    } finally {
      setTesting(false)
    }
  }
  const outputs = fn ? resultFields(fn) : []
  return <>
    <Labelled label="Function" required missing={isMissing('functionId')}
      hint={functions && functions.length === 0 ? 'You have no functions yet. Make one, or install a public one, under Functions.' : fn?.description ?? undefined}>
      <select aria-label="Function" className={fieldClass} value={id} onChange={e => pick(e.target.value)}>
        <option value="">Choose a function…</option>
        {id && !fn && <option value={id}>{node.config.functionName ? `fn.${String(node.config.functionName)} (deleted)` : 'Deleted function'}</option>}
        {(['own', 'public'] as const).map(scope => {
          const group = functions?.filter(f => (f.scope ?? 'own') === scope) ?? []
          return group.length > 0 && (
            <optgroup key={scope} label={scope === 'own' ? 'Your functions' : 'Installed public functions'}>
              {group.map(f => <option key={f.id} value={f.id}>fn.{f.name}({f.inputs.map(i => i.name).join(', ')}) → {f.returnType}</option>)}
            </optgroup>
          )
        })}
      </select>
    </Labelled>
    <div className="flex flex-wrap gap-3 text-xs">
      {fn && <Link to={`/functions/${fn.id}`} target="_blank" className="inline-flex items-center gap-1 text-brand-600 hover:underline dark:text-brand-300"><ExternalLink className="h-3 w-3" />Open in Functions</Link>}
      <Link to="/functions/new" target="_blank" className="inline-flex items-center gap-1 text-brand-600 hover:underline dark:text-brand-300"><Plus className="h-3 w-3" />New function</Link>
    </div>
    {fn && fn.inputs.length > 0 && (
      <Section title="Inputs">
        {fn.inputs.map(f => (
          <Labelled group key={f.name} label={f.name} required={f.required && f.default === undefined} missing={isMissing(`inputs.${f.name}`)}
            hint={[f.description, TYPE_HINT[f.type], f.default !== undefined ? `Leave empty for ${JSON.stringify(f.default)}.` : ''].filter(Boolean).join(' ') || TEMPLATE_HINT}>
            <MappedField aria-label={`Input ${f.name}`} mode="template" context={mapping} mono={f.type !== 'text'} invalid={isMissing(`inputs.${f.name}`)}
              value={values[f.name] ?? ''} onChange={v => set(f.name, v)} />
          </Labelled>
        ))}
        <p className="text-[11px] text-gray-400">A value that is just {'{{ … }}'} keeps its type. Text is read as the type the function asks for.</p>
      </Section>
    )}
    {fn && (
      <Section title="Result">
        <p className="text-[11px] text-gray-500">Later steps can map these from this step:</p>
        <ul className="flex flex-wrap gap-1">
          {outputs.map(f => (
            <li key={f.path} title={`nodes.${node.id}.${f.path}`}
              className="rounded-md bg-violet-50 px-1.5 py-0.5 font-mono text-[11px] text-violet-700 dark:bg-violet-500/10 dark:text-violet-300">
              {f.path}<span className="ml-1 text-[10px] text-violet-400">{f.type}</span>
            </li>
          ))}
          {outputs.length === 0 && <li className="text-[11px] text-gray-500">The function returns a map but lists no keys. Add them under Functions.</li>}
        </ul>
        <div className="flex min-w-0 items-start gap-2">
          <Button type="button" size="sm" variant="secondary" onClick={test} disabled={testing}>
            {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />} Test
          </Button>
          <p className="min-w-0 pt-1 text-[11px] text-gray-500">{mapping.sample ? 'Runs the function with the last run\'s data.' : 'No run yet, so inputs mapped from earlier steps are empty.'}</p>
        </div>
        {result && (
          <div role="status" className={`rounded-lg border p-2 text-[11px] ${result.error ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300' : 'border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50'}`}>
            {result.error ?? <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all font-mono text-gray-700 dark:text-gray-200">{JSON.stringify(result.value, null, 2)}</pre>}
          </div>
        )}
      </Section>
    )}
  </>
}

/** What a module step gives later steps: the outputs its module declares, with their types. */
function moduleOutputFields(node: CanvasNode, workflows?: WorkflowOption[]): DeclaredField[] {
  if (node.type !== 'SUB_WORKFLOW') return []
  return moduleOutputs(workflows?.find(w => w.id === node.config.workflowId)?.graph).map(f => ({ path: f.name, type: f.type }))
}

/** The last run's data, for a formula's preview: the run's input and each step's result. */
function sampleOf(graph: CanvasGraph, outputs: ReadonlyMap<string, unknown>): Record<string, unknown> | undefined {
  if (!outputs.size) return undefined
  const start = graph.nodes.find(n => n.type === 'TRIGGER')?.id
  const nodes = Object.fromEntries([...outputs].filter(([id]) => id !== start))
  return { input: start ? outputs.get(start) ?? {} : {}, nodes }
}

const TYPE_HINT: Partial<Record<ValueType, string>> = {
  number: 'A number.', boolean: 'true or false.', date: 'A date, such as 2026-10-24.',
  datetime: 'A date and time, such as 2026-10-24T18:30.', list: 'A list.', map: 'A set of fields.',
}

/**
 * A module step: one mapped field per input the module declares, and what later steps can read.
 * A module that declares no inputs takes one formula for its whole input, as before.
 */
function ModuleCallFields({ node, module, setConfig, isMissing, mapping, str }: {
  node: CanvasNode; module?: WorkflowOption; setConfig: SetConfig; isMissing: (f: string) => boolean; mapping: MappingContext; str: (k: string) => string
}) {
  const inputs = moduleInputs(module?.graph)
  const outputs = moduleOutputs(module?.graph)
  const values = mappedInputs(node)
  const set = (name: string, v: string) => {
    const next = { ...values }
    if (v) next[name] = v
    else delete next[name]
    setConfig({ inputs: Object.keys(next).length ? next : undefined })
  }
  if (!str('workflowId')) return null
  if (module && !isModule(module.graph) && !str('inputExpr')) {
    return <>
      <p className="text-[11px] text-gray-500">"{module.name}" doesn't declare inputs or outputs. Add them on its Start and End steps to use it as a module.</p>
      <Labelled group label="Input (optional)" hint="A formula for the workflow's whole input, e.g. {'amount': followed by a total chip and }">
        <MappedField aria-label="Input" mode="expression" context={mapping} mono value={str('inputExpr')} onChange={v => setConfig({ inputExpr: v || undefined })} />
      </Labelled>
    </>
  }
  return <>
    {inputs.length > 0 && (
      <Section title="Inputs">
        {inputs.map(f => (
          <Labelled group key={f.name} label={f.name} required={f.required && f.default === undefined} missing={isMissing(`inputs.${f.name}`)}
            hint={[f.description, TYPE_HINT[f.type], f.default !== undefined ? `Leave empty for ${JSON.stringify(f.default)}.` : ''].filter(Boolean).join(' ') || TEMPLATE_HINT}>
            <MappedField aria-label={`Input ${f.name}`} mode="template" context={mapping} mono={f.type !== 'text'} invalid={isMissing(`inputs.${f.name}`)}
              value={values[f.name] ?? ''} onChange={v => set(f.name, v)} />
            {(f.type === 'date' || f.type === 'datetime') && !(values[f.name] ?? '').includes('{{') && (
              <DateValueInput type={f.type} format={f.format} className={`${NARROW_FIELD} text-xs`} aria-label={`Pick ${f.name}`}
                value={values[f.name] ?? ''} onChange={v => set(f.name, v)} />
            )}
          </Labelled>
        ))}
        <p className="text-[11px] text-gray-400">A value that is just {'{{ … }}'} keeps its type. Text is read as the type the module asks for.</p>
      </Section>
    )}
    {str('inputExpr') && (
      <Labelled group label="Input formula (older setting)" hint="Used only when no inputs above are filled in.">
        <MappedField aria-label="Input" mode="expression" context={mapping} mono value={str('inputExpr')} onChange={v => setConfig({ inputExpr: v || undefined })} />
      </Labelled>
    )}
    {outputs.length > 0 && (
      <Section title="Outputs">
        <p className="text-[11px] text-gray-500">Later steps can map these from this step:</p>
        <ul className="flex flex-wrap gap-1">
          {outputs.map(f => (
            <li key={f.name} title={f.description ?? `nodes.${node.id}.${f.name}`}
              className="rounded-md bg-indigo-50 px-1.5 py-0.5 font-mono text-[11px] text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300">
              {f.name}<span className="ml-1 text-[10px] text-indigo-400">{f.type}</span>
            </li>
          ))}
        </ul>
      </Section>
    )}
  </>
}

/**
 * The inputs a workflow takes (on its Start step) or the outputs it gives (on an End step), which
 * make it usable as a module in other workflows.
 */
function ContractFields({ kind, node, setConfig, isMissing, mapping }: {
  kind: 'inputs' | 'outputs'; node: CanvasNode; setConfig: SetConfig; isMissing: (f: string) => boolean; mapping: MappingContext
}) {
  const rows = fieldsOf(node.config, kind)
  const save = (next: ModuleField[], key: string) => setConfig({ [kind]: next.length ? next : undefined }, key)
  const edit = (i: number, patch: Partial<ModuleField>) => save(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)), `${kind}:${i}`)
  const inputs = kind === 'inputs'
  return (
    <Section title={inputs ? 'Inputs' : 'Outputs'}>
      <p className="text-[11px] text-gray-500">{inputs
        ? 'What this workflow needs when it runs. Steps read each one as input.<name>, and a workflow that uses this one as a module fills them in.'
        : 'What this workflow gives back when another workflow uses it as a module. That step\'s later steps read each one as nodes.<step>.<name>.'}</p>
      {rows.map((r, i) => (
        <div key={i} className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
          <div className="flex items-center gap-1.5">
            <input aria-label={`Name of ${inputs ? 'input' : 'output'} ${i + 1}`} placeholder="name"
              className={`${NARROW_FIELD} min-w-0 flex-1 font-mono text-xs ${isMissing(`${kind}.${i}.name`) ? '!border-red-400' : ''}`}
              value={r.name} onChange={e => edit(i, { name: e.target.value.replace(/\s+/g, '_') })} />
            <select aria-label={`Type of ${inputs ? 'input' : 'output'} ${i + 1}`} className={`${NARROW_FIELD} w-32 shrink-0`} value={r.type}
              onChange={e => edit(i, { type: e.target.value as ValueType })}>
              {FIELD_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <button type="button" onClick={() => save(rows.filter((_, j) => j !== i), `remove:${kind}`)} aria-label={`Remove ${inputs ? 'input' : 'output'} ${i + 1}`}
              className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
          </div>
          {inputs ? <>
            <input aria-label={`Description of input ${i + 1}`} placeholder="What it is (optional)" className={`${fieldClass} text-xs`}
              value={r.description ?? ''} onChange={e => edit(i, { description: e.target.value || undefined })} />
            <div className="flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-1.5 text-xs text-gray-700 dark:text-gray-300">
                <input type="checkbox" checked={r.required === true} onChange={e => edit(i, { required: e.target.checked || undefined })} />
                Required
              </label>
              <label className="flex min-w-0 flex-1 items-center gap-1.5 text-xs text-gray-700 dark:text-gray-300">
                <span className="shrink-0">Default</span>
                {r.type === 'date' || r.type === 'datetime' ? (
                  <DateValueInput type={r.type} format={r.format} aria-label={`Default for input ${i + 1}`} className={`${NARROW_FIELD} min-w-0 flex-1 text-xs`}
                    value={typeof r.default === 'string' ? r.default : ''}
                    onChange={v => edit(i, { default: v === '' ? undefined : v })} />
                ) : (
                  <input aria-label={`Default for input ${i + 1}`} placeholder="none" className={`${NARROW_FIELD} min-w-0 flex-1 font-mono text-xs`}
                    value={r.default === undefined ? '' : typeof r.default === 'string' ? r.default : JSON.stringify(r.default)}
                    onChange={e => edit(i, { default: e.target.value === '' ? undefined : e.target.value })} />
                )}
              </label>
            </div>
            {(r.type === 'date' || r.type === 'datetime') && (
              <label className="flex items-center gap-1.5 text-xs text-gray-700 dark:text-gray-300">
                <span className="shrink-0">Site's format</span>
                <input aria-label={`Date format of input ${i + 1}`} placeholder="e.g. DD/MM/YYYY" className={`${NARROW_FIELD} min-w-0 flex-1 font-mono text-xs`}
                  value={r.format ?? ''} onChange={e => edit(i, { format: e.target.value.trim() ? e.target.value : undefined })} />
              </label>
            )}
          </> : (
            <MappedField aria-label={`Value of output ${i + 1}`} placeholder="Map data from earlier steps" mode="template" context={mapping} mono
              invalid={isMissing(`${kind}.${i}.value`)} value={r.value ?? ''} onChange={v => edit(i, { value: v })} />
          )}
        </div>
      ))}
      <button type="button" onClick={() => save([...rows, { name: '', type: 'text', ...(inputs ? {} : { value: '' }) }], `add:${kind}`)}
        className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300">
        <Plus className="h-3.5 w-3.5" /> {inputs ? 'Add an input' : 'Add an output'}
      </button>
    </Section>
  )
}

/**
 * A Script step: inputs mapped from earlier steps, a few lines of Jizza script over them, and the
 * outputs it returns, which later steps map like any other step's fields. Test runs it in the
 * engine's sandbox over the last run's data.
 */
function ScriptFields({ node, setConfig, isMissing, mapping, functions }: {
  node: CanvasNode; setConfig: SetConfig; isMissing: (f: string) => boolean; mapping: MappingContext; functions?: ScriptFunction[]
}) {
  const [moving, setMoving] = useState<string | null>(null)
  // Scripts now live under Functions: this one becomes a function there, and the step runs it.
  const moveToFunctions = async () => {
    setMoving('')
    try {
      const base = (node.label || 'script').replace(/[^A-Za-z0-9_]+(.)?/g, (_m, c: string | undefined) => (c ? c.toUpperCase() : '')).replace(/^[^A-Za-z]+/, '') || 'script'
      const taken = new Set((functions ?? []).map(f => f.name))
      let name = base.charAt(0).toLowerCase() + base.slice(1)
      for (let n = 2; taken.has(name); n++) name = `${base.charAt(0).toLowerCase() + base.slice(1)}${n}`
      const outs = fieldsOf(node.config, 'outputs').filter(f => f.name)
      const created = await functionsApi.create({
        name, description: `Moved from the workflow step "${node.label}".`,
        inputs: inputs.filter(i => i.name).map(i => ({ name: i.name, type: i.type })),
        script, returnType: outs.length ? 'map' : 'any', returnFields: outs.map(o => ({ path: o.name, type: o.type })),
      })
      await queryClient.invalidateQueries({ queryKey: ['functions'] })
      setConfig({
        functionId: created.id, functionName: created.name, script: undefined, outputs: undefined,
        inputs: Object.fromEntries(inputs.filter(i => i.name && i.value).map(i => [i.name, i.value!])),
      }, 'move')
      setMoving(null)
    } catch (e) {
      setMoving(e instanceof Error ? e.message : 'Could not move the script.')
    }
  }
  const inputs = fieldsOf(node.config, 'inputs')
  const outputs = fieldsOf(node.config, 'outputs')
  const script = typeof node.config.script === 'string' ? node.config.script : ''
  const [result, setResult] = useState<FormulaPreview | null>(null)
  const [testing, setTesting] = useState(false)
  useEffect(() => setResult(null), [node.id])
  const save = (key: 'inputs' | 'outputs', next: ModuleField[], why: string) => setConfig({ [key]: next }, why)
  const edit = (key: 'inputs' | 'outputs', rows: ModuleField[], i: number, patch: Partial<ModuleField>) =>
    save(key, rows.map((r, j) => (j === i ? { ...r, ...patch } : r)), `${key}:${i}`)
  const test = async () => {
    setTesting(true)
    try {
      setResult(await formulaApi.previewScript({ inputs, script, outputs }, mapping.sample ?? { input: {}, nodes: {} }))
    } catch (e) {
      setResult({ value: null, type: null, error: e instanceof Error ? e.message : 'The test could not run.' })
    } finally {
      setTesting(false)
    }
  }
  const row = (key: 'inputs' | 'outputs', rows: ModuleField[], r: ModuleField, i: number) => {
    const what = key === 'inputs' ? 'input' : 'output'
    return (
      <div key={i} className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex items-center gap-1.5">
          <input aria-label={`Name of script ${what} ${i + 1}`} placeholder="name"
            className={`${NARROW_FIELD} min-w-0 flex-1 font-mono text-xs ${isMissing(`${key}.${i}.name`) ? '!border-red-400' : ''}`}
            value={r.name} onChange={e => edit(key, rows, i, { name: e.target.value.replace(/\s+/g, '_') })} />
          <select aria-label={`Type of script ${what} ${i + 1}`} className={`${NARROW_FIELD} w-32 shrink-0`} value={r.type}
            onChange={e => edit(key, rows, i, { type: e.target.value as ValueType })}>
            {FIELD_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
          <button type="button" onClick={() => save(key, rows.filter((_, j) => j !== i), `remove:${key}`)} aria-label={`Remove script ${what} ${i + 1}`}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-gray-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10"><Trash2 className="h-3.5 w-3.5" /></button>
        </div>
        {key === 'inputs' && (
          <MappedField aria-label={`Value of script input ${i + 1}`} placeholder="Map data from earlier steps" mode="template" context={mapping} mono
            invalid={isMissing(`inputs.${i}.value`)} value={r.value ?? ''} onChange={v => edit(key, rows, i, { value: v })} />
        )}
      </div>
    )
  }
  const add = (key: 'inputs' | 'outputs', rows: ModuleField[]) => (
    <button type="button" onClick={() => save(key, [...rows, { name: '', type: key === 'inputs' ? 'text' : 'any', ...(key === 'inputs' ? { value: '' } : {}) }], `add:${key}`)}
      className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline dark:text-brand-300">
      <Plus className="h-3.5 w-3.5" /> {key === 'inputs' ? 'Add an input' : 'Add an output'}
    </button>
  )
  return <>
    <div className="rounded-lg border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
      <p>Scripts now live under Functions, where other workflows and functions can reuse them. This step still runs its own script.</p>
      <Button type="button" size="sm" variant="secondary" className="mt-1.5" loading={moving === ''} onClick={moveToFunctions}>Move to Functions</Button>
      {moving && <p className="mt-1 text-red-600 dark:text-red-400">{moving}</p>}
    </div>
    <Section title="Inputs">
      <p className="text-[11px] text-gray-500">Values from earlier steps the script works on. The script reads each one by its name.</p>
      {inputs.map((r, i) => row('inputs', inputs, r, i))}
      {add('inputs', inputs)}
    </Section>
    <Section title="Script">
      <Labelled group label="Script" required missing={isMissing('script')}
        hint="var, if/else and for (var item : list) { } with every formula function. End with return {'name': value} giving the outputs below. Ctrl+Space suggests, Ctrl+/ comments a line.">
        <ScriptEditor aria-label="Script" value={script} inputs={inputs.filter(f => f.name).map(f => ({ name: f.name, type: f.type }))} functions={functions?.map(infoOf)}
          invalid={isMissing('script')} onChange={v => setConfig({ script: v }, 'script')} />
      </Labelled>
      <div className="flex min-w-0 items-start gap-2">
        <Button type="button" size="sm" variant="secondary" onClick={test} disabled={testing || !script.trim()}>
          {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />} Test
        </Button>
        <p className="min-w-0 pt-1 text-[11px] text-gray-500">{mapping.sample ? 'Runs the script with the last run\'s data.' : 'No run yet, so inputs mapped from earlier steps are empty.'}</p>
      </div>
      {result && (
        <div role="status" className={`rounded-lg border p-2 text-[11px] ${result.error ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300' : 'border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50'}`}>
          {result.error ?? <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all font-mono text-gray-700 dark:text-gray-200">{JSON.stringify(result.value, null, 2)}</pre>}
        </div>
      )}
    </Section>
    <Section title="Outputs">
      <p className="text-[11px] text-gray-500">The keys of the map the script returns. Later steps map each one from this step.</p>
      {outputs.map((r, i) => row('outputs', outputs, r, i))}
      {add('outputs', outputs)}
    </Section>
  </>
}

/** Edits a JSON value as text, keeping the text while it doesn't parse. */
/**
 * An MCP server added under Connectors, one of its tools, and that tool's arguments: one mapped
 * field per argument its schema names, or JSON when it names none.
 */
/** What an MCP tool step gives later steps: its tool's output fields. */
function mcpOutputFields(node: CanvasNode, servers?: McpServer[]): string[] {
  if (node.type !== 'MCP_TOOL') return []
  const tool = servers?.find(s => s.id === node.config.serverId)?.tools.find(t => t.name === node.config.tool)
  return schemaFields(tool?.outputSchema).map(f => f.path)
}

function McpToolFields({ node, servers, setConfig, isMissing, mapping }: {
  node: CanvasNode; servers?: McpServer[]; setConfig: (patch: Record<string, unknown>) => void; isMissing: (f: string) => boolean; mapping: MappingContext
}) {
  const serverId = typeof node.config.serverId === 'string' ? node.config.serverId : ''
  const toolName = typeof node.config.tool === 'string' ? node.config.tool : ''
  const server = servers?.find(s => s.id === serverId)
  const tool = server?.tools.find(t => t.name === toolName)
  const args = (node.config.arguments && typeof node.config.arguments === 'object' && !Array.isArray(node.config.arguments)
    ? node.config.arguments : {}) as Record<string, unknown>
  const schema = (tool?.inputSchema ?? {}) as { properties?: Record<string, { description?: string; type?: string; enum?: unknown[] }>; required?: string[] }
  const properties = Object.entries(schema.properties ?? {})
  const required = new Set(schema.required ?? [])
  return <>
    <Labelled label="MCP server" required missing={isMissing('serverId')}
      hint={servers && servers.length === 0 ? 'No MCP servers yet. Add one under Connectors.' : undefined}>
      <select className={fieldClass} value={serverId}
        onChange={e => setConfig({ serverId: e.target.value || undefined, serverName: servers?.find(s => s.id === e.target.value)?.name, tool: undefined, toolLabel: undefined, arguments: undefined })}>
        <option value="">Choose a server…</option>
        {serverId && !server && <option value={serverId}>{String(node.config.serverName ?? 'Current server')}</option>}
        {servers?.map(s => <option key={s.id} value={s.id}>{s.name}{s.enabled ? '' : ' (off)'}</option>)}
      </select>
    </Labelled>
    {serverId && (
      <Labelled label="Tool" required missing={isMissing('tool')}
        hint={server && server.tools.length === 0 ? 'This server has no tools yet. Open it under Connectors and check its tools.' : undefined}>
        <select className={fieldClass} value={toolName}
          onChange={e => {
            const t = server?.tools.find(x => x.name === e.target.value)
            setConfig({ tool: e.target.value || undefined, toolLabel: t?.title || t?.name, arguments: undefined })
          }}>
          <option value="">Choose a tool…</option>
          {toolName && !tool && <option value={toolName}>{String(node.config.toolLabel ?? toolName)}</option>}
          {server?.tools.filter(t => t.enabled || t.name === toolName).map(t => <option key={t.name} value={t.name}>{t.title || t.name}</option>)}
        </select>
      </Labelled>
    )}
    {tool?.description && <p className="text-[11px] text-gray-500">{tool.description}</p>}
    {tool && (
      <p className="text-[11px] text-gray-500">
        {tool.outputSchema
          ? `Later steps can map its ${schemaFields(tool.outputSchema).length} output fields.`
          : 'Try this tool once on its server page under Connectors, and later steps can map its output fields.'}
      </p>
    )}
    {tool && !tool.actionType && <p className="text-[11px] text-amber-700 dark:text-amber-300">This tool doesn't say what it does yet, so it is treated as moving money. Choose what it does on the server's page under Connectors.</p>}
    {tool && properties.length > 0 && properties.map(([key, p]) => (
      <Labelled group key={key} label={key} required={required.has(key)} hint={[p.description, p.enum ? `One of: ${p.enum.join(', ')}` : ''].filter(Boolean).join(' ') || TEMPLATE_HINT}>
        <MappedField aria-label={key} mode="template" context={mapping} mono={p.type !== 'string'}
          value={args[key] === undefined || args[key] === null ? '' : typeof args[key] === 'string' ? args[key] as string : JSON.stringify(args[key])}
          onChange={v => {
            const next = { ...args }
            if (v === '') delete next[key]
            else next[key] = p.type === 'number' || p.type === 'integer' ? (v.trim() !== '' && !Number.isNaN(Number(v)) ? Number(v) : v)
              : p.type === 'boolean' ? (v === 'true' ? true : v === 'false' ? false : v) : v
            setConfig({ arguments: next })
          }} />
      </Labelled>
    ))}
    {tool && properties.length === 0 && (
      <JsonField key={`${node.id}:${toolName}`} label="Arguments (JSON)" value={node.config.arguments} hint="Text values can use {{ … }} to put in data from earlier steps." onChange={v => setConfig({ arguments: v })} />
    )}
  </>
}

/** What a device step gives later steps: its action's output fields, plus which device ran it. */
function deviceOutputFields(node: CanvasNode, catalog?: DeviceActionSpec[]): DeclaredField[] {
  if (node.type !== 'DEVICE_ACTION') return []
  const spec = catalog?.find(a => a.action === node.config.action && (!node.config.version || a.version === node.config.version))
  return schemaFields(spec?.output ?? undefined).map(f => ({
    path: f.path,
    type: f.type === 'boolean' ? 'boolean' : f.type === 'number' || f.type === 'integer' ? 'number'
      : f.type === 'string' ? 'text' : f.type === 'array' ? 'list' : f.type === 'object' ? 'map' : 'any',
  }))
}

type WebProperty = {
  type?: string
  description?: string
  enum?: unknown[]
  default?: unknown
  oneOf?: unknown[]
  minimum?: number
  maximum?: number
  $ref?: string
}

function webInputIssues(node: CanvasNode, catalog?: DeviceActionSpec[]): FieldIssue[] {
  if (node.type !== 'DEVICE_ACTION' || node.config.preset !== 'web') return []
  const spec = catalog?.find(a => a.action === node.config.action
    && (!node.config.version || a.version === node.config.version))
  if (!spec) {
    return [{
      field: 'action',
      message: `"${node.label}" is waiting for its web action definition.`,
    }]
  }
  const schema = (spec?.input ?? {}) as {
    required?: string[]
    oneOf?: Array<{ required?: string[] }>
  }
  const inputs = asInputs(node.config.inputs)
  const present = (field: string) => inputs[field] !== undefined && inputs[field] !== null
    && (typeof inputs[field] !== 'string' || inputs[field].trim() !== '')
  const issues: FieldIssue[] = []
  for (const field of schema.required ?? []) {
    if (field !== 'element' && !present(field)) {
      issues.push({
        field: `inputs.${field}`,
        message: `"${node.label}" needs a value for the input "${field}".`,
      })
    }
  }
  if (spec.action === 'browser.fillForm') {
    const rows = asRows(inputs.fields)
    if (rows.length === 0) issues.push({ field: 'inputs.fields', message: `"${node.label}" needs at least one form field.` })
    rows.forEach((row, i) => {
      const element = row.element as { strategies?: unknown[] } | string | undefined
      const located = typeof element === 'string' ? element.includes('{{') : Array.isArray(element?.strategies) && element.strategies.length > 0
      if (!located) issues.push({ field: 'inputs.fields', message: `"${node.label}": pick the element of field ${i + 1}.` })
    })
  }
  if (schema.oneOf?.length) {
    const matches = schema.oneOf.filter(choice => (choice.required ?? []).every(present)).length
    if (matches !== 1) {
      const field = schema.oneOf.flatMap(choice => choice.required ?? [])[0] ?? 'inputs'
      issues.push({
        field: `inputs.${field}`,
        message: `"${node.label}" needs exactly one of the available input choices.`,
      })
    }
  }
  return issues
}

const asInputs = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}

/** Browser-specific device action settings. Includes Pick on page. */
export function WebStepFields({ node, catalog, setConfig, isMissing, mapping, branch, layer }: {
  node: CanvasNode; catalog?: DeviceActionSpec[]; setConfig: SetConfig
  isMissing: (f: string) => boolean; mapping: MappingContext
  /** A device layer changes its own exits: the graph it is in, and how to change it. */
  layer?: { graph: CanvasGraph; onGraph: (graph: CanvasGraph) => void }
  /** A group of buttons: branching the flow on which one was clicked. */
  branch?: { existing?: string; disabled: boolean; onClick: () => void }
}) {
  const { data: browsers } = useQuery({ queryKey: ['web', 'browsers'], queryFn: webApi.browsers }, queryClient)
  const action = typeof node.config.action === 'string' ? node.config.action : WEB_PALETTE[0].config!.action as string
  const version = typeof node.config.version === 'number' ? node.config.version : 1
  const target = typeof node.config.target === 'string' ? node.config.target : 'sameBrowser'
  const spec = catalog?.find(a => a.action === action && a.version === version)
  const inputs = asInputs(node.config.inputs)
  const schema = (spec?.input ?? {}) as { properties?: Record<string, WebProperty>; required?: string[] }
  const required = new Set(schema.required ?? [])
  const elementAction = WEB_ELEMENT_INPUT_ACTIONS.has(action)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<WebTestResult>()
  const [testError, setTestError] = useState('')
  const concrete = browsers?.find(b => b.id === target)

  const setInput = (key: string, value: unknown) => setInputs({ [key]: value })
  const setInputs = (patch: Record<string, unknown>) => {
    const next = { ...inputs }
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined || value === '') delete next[key]
      else next[key] = value
    }
    setConfig({ inputs: next })
  }
  const switchAction = (nextAction: string) => {
    const preset = WEB_PALETTE.find(e => e.config?.action === nextAction)!
    const keepElement = WEB_ELEMENT_INPUT_ACTIONS.has(nextAction) ? inputs.element : undefined
    setConfig({
      action: nextAction, version: preset.config?.version, actionTitle: preset.label,
      inputs: keepElement === undefined ? undefined : { element: keepElement },
    })
    setTestResult(undefined)
    setTestError('')
  }
  const test = async () => {
    if (!concrete) return
    setTesting(true)
    setTestResult(undefined)
    setTestError('')
    try {
      setTestResult(await webApi.testStep({ deviceId: concrete.id, action, version, inputs }))
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      setTestError(problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'The test failed.'))
    } finally {
      setTesting(false)
    }
  }
  const ordinary = Object.entries(schema.properties ?? {}).filter(([key]) =>
    action !== 'browser.fillLayer' && !ADVANCED_WEB_INPUTS.has(key) && webInputShown(action, key, inputs)
    && !(action === 'browser.fillForm' && key === 'fields')
    && !(key === 'within' || key === 'loadMore' || key === 'itemCss' || key === 'titleCss')
    // A run-time list choice has one field for its item: the list's own dropdown (below).
    && !(key === 'item' && action === 'browser.clickItem')
    // Grouped buttons keep their elements as recorded; only which one to click is chosen.
    && !(key === 'options' && action === 'browser.clickOneOf')
    // A dropdown's items and where they come from have their own section (below).
    && !((key === 'options' || key === 'mode') && action === 'browser.selectOption'))
  const groupButtons = action === 'browser.clickOneOf' && Array.isArray(inputs.options)
    ? (inputs.options as { name?: unknown }[]).map(o => typeof o?.name === 'string' ? o.name : '').filter(Boolean)
    : []
  const dropdown = action === 'browser.selectOption'
  const dropdownItems = dropdown && inputs.mode !== 'dynamic' && Array.isArray(inputs.options)
    ? (inputs.options as unknown[]).map(String).filter(Boolean)
    : []
  const pickable = action === 'browser.click' || action === 'browser.clickItem'
  const choosesAtRunTime = action === 'browser.clickItem'
  const listNames = asInputs(node.config.list) as RecordedListNames
  const toggleRunTimeChoice = (on: boolean) => {
    setConfig(on
      ? { action: 'browser.clickItem', version: 1, actionTitle: 'Choose from a list', inputs: { element: inputs.element, item: recordedItem(inputs.element) } }
      : { action: 'browser.click', version: 2, actionTitle: 'Click', inputs: inputs.element === undefined ? undefined : { element: inputs.element } })
  }
  const retry = asInputs(node.config.retry)
  const setRetry = (patch: Record<string, unknown>) => {
    const next = { ...retry, ...patch }
    for (const k of Object.keys(next)) if (next[k] === undefined) delete next[k]
    setConfig({ retry: Object.keys(next).length ? next : undefined })
  }
  const hasHighlight = Boolean(schema.properties?.highlight)
  const hasTrusted = Boolean(schema.properties?.trusted)

  return <>
    <Labelled label="Browser" hint="Same browser continues the browser used by the previous web step.">
      <select aria-label="Browser" className={fieldClass} value={target}
        onChange={e => {
          const browser = browsers?.find(b => b.id === e.target.value)
          setConfig({
            target: e.target.value, deviceName: browser?.name,
            workspaceId: browser?.workspaceId ?? undefined,
          })
        }}>
        <option value="sameBrowser">Same browser as earlier steps</option>
        <option value="any">Any browser</option>
        {target !== 'sameBrowser' && target !== 'any' && !concrete && <option value={target}>{String(node.config.deviceName ?? 'Current browser')}</option>}
        {browsers?.map(b => <option key={b.id} value={b.id}>{b.name}{b.online ? ' (online)' : ' (offline)'}</option>)}
      </select>
    </Labelled>

    <Labelled label="Step">
      <select aria-label="Step" className={fieldClass} value={action} onChange={e => switchAction(e.target.value)}>
        {WEB_PALETTE.map(e => <option key={String(e.config?.action)} value={String(e.config?.action)}>{e.label}</option>)}
      </select>
    </Labelled>
    {spec?.description && <p className="text-[11px] text-gray-500">{spec.description}</p>}

    {elementAction && (
      <ElementField label={choosesAtRunTime || action === 'browser.listItems' ? 'One item of the list' : undefined}
        value={inputs.element} deviceId={concrete?.id} browsers={browsers}
        hasEarlierOpen={mapping.graph.nodes.some(n => n.id !== node.id && n.config.action === 'browser.open')} mapping={mapping}
        required={WEB_ELEMENT_ACTIONS.has(action)} missing={isMissing('inputs.element')} onChange={value => setInput('element', value)} />
    )}

    {pickable && (
      <div className="space-y-1">
        <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={choosesAtRunTime} onChange={e => toggleRunTimeChoice(e.target.checked)} />
          Choose the item at run time
        </label>
        <p className="text-[11px] text-gray-500">
          {choosesAtRunTime
            ? 'The step finds the list this item belongs to as the page shows it when the run gets here, then clicks the item named below (such as a movie or a date). Map it from earlier steps. With Ask me, your approval lists what the page shows then; with Let Jizza decide, Jizza clicks it when the page lists it and asks you when it does not.'
            : 'For a card or row that changes from day to day, such as a movie or a date: the step picks it from the list on the page when it runs.'}
        </p>
      </div>
    )}

    {(choosesAtRunTime || action === 'browser.listItems') && (
      <ListChoiceOptions inputs={inputs} browsers={browsers} deviceId={concrete?.id} mapping={mapping}
        list={listNames} setInput={setInput} setInputs={setInputs} withItem={choosesAtRunTime}
        earlierLists={earlierLists(mapping.graph, node.id)}
        onFollows={dependsOn => setConfig({ list: { ...asInputs(node.config.list), dependsOn } })}
        itemRequired={required.has('item')} itemMissing={isMissing('inputs.item')}
        pictures={choosesAtRunTime ? node.config.screenshots : undefined}
        onPictures={choosesAtRunTime ? screenshots => setConfig({ screenshots }) : undefined} />
    )}

    {action === 'browser.fillForm' && (
      <FillFormFields value={inputs.fields} browsers={browsers} deviceId={concrete?.id} mapping={mapping}
        missing={isMissing('inputs.fields')} onChange={value => setInput('fields', value)} />
    )}

    {action === 'browser.fillLayer' && layer && (
      <LayerFields node={node} graph={layer.graph} browsers={browsers} deviceId={concrete?.id} mapping={mapping}
        isMissing={isMissing} onGraph={layer.onGraph} />
    )}

    {action === 'browser.clickOneOf' && (
      <p className="text-xs text-gray-600 dark:text-gray-300">
        Buttons in this group: {groupButtons.length > 0 ? groupButtons.join(', ') : 'none'}. Record the group again to change them.
      </p>
    )}

    {dropdown && (
      <DropdownItems inputs={inputs} deviceId={concrete?.id} setInputs={setInputs} />
    )}

    {branch && groupButtons.length > 0 && (
      <div className="space-y-1 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <p className="text-xs font-medium text-gray-800 dark:text-gray-200">Different steps for each button</p>
        {branch.existing
          ? <p className="text-[11px] text-gray-500">The paths step "{branch.existing}" after this step picks the path by the button clicked. Open it to add or remove a button's path.</p>
          : <>
              <p className="text-[11px] text-gray-500">Adds a paths step after this one with a path per button. Every path carries on as now until you put steps on it, so buttons that need nothing different just continue.</p>
              <Button type="button" size="sm" variant="secondary" disabled={branch.disabled} onClick={branch.onClick}>Branch by button clicked</Button>
            </>}
      </div>
    )}

    <FoldSection title="Ask on the approval card" summary={countOf(Array.isArray((node.config.details as { blocks?: unknown } | undefined)?.blocks) ? (node.config.details as { blocks: unknown[] }).blocks.length : 0, 'note or question', 'notes and questions')}>
      <StepDetailsEditor nodeId={node.id} value={node.config.details} mapping={mapping}
        onChange={details => setConfig({ details })} />
    </FoldSection>

    {ordinary.map(([key, property]) => key === 'item' && action === 'browser.clickOneOf'
      ? <WebSchemaField key={key} name={key} required={required.has(key)}
          property={{ ...property, enum: groupButtons, description: 'Choose… leaves it to the page: whichever of the buttons is there is clicked.' }}
          value={inputs[key]} label={listNames.named && listNames.title ? listNames.title : 'Button to click'}
          mapping={mapping} onChange={value => setInput(key, value)} />
      : key === 'item' && dropdown && dropdownItems.length > 0
      ? <WebSchemaField key={key} name={key} required={required.has(key)}
          property={{ ...property, enum: dropdownItems, description: 'One of the items kept with the step, or map it: a value that is not one of them is asked.' }}
          value={inputs[key]} label={dropdownLabel(inputs.element)}
          mapping={mapping} onChange={value => setInput(key, value)} />
      : key === 'item' && dropdown
      ? <WebSchemaField key={key} name={key} property={{ ...property, description: 'The item to choose. Map it from earlier steps; the run reads the items the dropdown shows then and asks when none matches.' }}
          value={inputs[key]} required={required.has(key)} label={dropdownLabel(inputs.element)}
          mapping={mapping} onChange={value => setInput(key, value)} />
      : key === 'value' && (action === 'browser.setDate' || action === 'browser.pickDate')
        && !(typeof inputs[key] === 'string' && (inputs[key] as string).includes('{{'))
      ? <div key={key} className="space-y-1">
          <WebSchemaField name={key} property={property} value={inputs[key]} required={required.has(key)}
            mapping={mapping} onChange={value => setInput(key, value)} />
          <DateValueInput type="date" format={typeof inputs.format === 'string' ? inputs.format : undefined}
            className={`${NARROW_FIELD} text-xs`} aria-label="Pick the date"
            value={typeof inputs[key] === 'string' ? inputs[key] as string : ''} onChange={v => setInput(key, v || undefined)} />
        </div>
      : key === 'items' && property.type === 'array'
      ? <Labelled key={key} group label={action === 'browser.checkItems' ? 'Boxes to tick' : 'Items'} required={required.has(key)}
          hint={property.description ?? 'Comma-separated, or map a list from earlier steps.'}>
          <MappedField aria-label={action === 'browser.checkItems' ? 'Boxes to tick' : 'Items'} mode="template" context={mapping}
            value={Array.isArray(inputs[key]) ? (inputs[key] as unknown[]).join(', ') : typeof inputs[key] === 'string' ? inputs[key] as string : ''}
            onChange={v => setInput(key, v)} />
        </Labelled>
      : isLocatorProperty(property)
      ? <ElementField key={key} label={webInputLabel(key)} value={inputs[key]} deviceId={concrete?.id} browsers={browsers}
          hasEarlierOpen mapping={mapping} required={required.has(key)} missing={isMissing(`inputs.${key}`)}
          onChange={value => setInput(key, value)} />
      : <WebSchemaField key={key} name={key} property={property} value={inputs[key]} required={required.has(key)}
          label={key === 'item' && choosesAtRunTime && listNames.named && listNames.title ? listNames.title : undefined}
          mapping={mapping} onChange={value => setInput(key, value)} />
    )}

    <FoldSection title="Advanced options" summary="timeout, retries, what happens on failure">
      {<>
        {hasHighlight && <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={inputs.highlight === true} onChange={e => setInput('highlight', e.target.checked || undefined)} /> Highlight the element while the step runs
        </label>}
        {hasTrusted && <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={inputs.trusted === true} onChange={e => setInput('trusted', e.target.checked || undefined)} /> Trusted input
        </label>}
        {hasTrusted && <p className="text-[11px] text-gray-500">Trusted input types and clicks like a real keyboard and mouse. Use it when a site ignores the step otherwise; Chrome shows a debugging bar while it runs.</p>}
        <Labelled group label="Timeout (milliseconds)">
          <MappedField aria-label="Timeout (milliseconds)" mode="template" context={mapping} mono
            value={inputs.timeoutMs == null ? '' : String(inputs.timeoutMs)}
            onChange={v => setInput('timeoutMs', v && !v.includes('{{') && !Number.isNaN(Number(v)) ? Number(v) : v)} />
        </Labelled>
        <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={inputs.screenshotOnError !== false} onChange={e => setInput('screenshotOnError', e.target.checked)} /> Screenshot on error
        </label>
        <div className="grid grid-cols-2 gap-2">
          <Labelled label="Tries before giving up" hint="1 means no retry.">
            <input type="number" aria-label="Tries before giving up" min={1} max={10} className={fieldClass}
              value={typeof retry.maxAttempts === 'number' ? retry.maxAttempts : 3}
              onChange={e => setRetry({ maxAttempts: Math.max(1, Math.min(10, Number(e.target.value) || 1)) })} />
          </Labelled>
          <Labelled label="Seconds between tries" hint="Doubles after each try.">
            <input type="number" aria-label="Seconds between tries" min={0} max={600} className={fieldClass}
              value={typeof retry.backoffMillis === 'number' ? retry.backoffMillis / 1000 : 1}
              onChange={e => setRetry({ backoffMillis: Math.round(Math.max(0, Math.min(600, Number(e.target.value) || 0)) * 1000) })} />
          </Labelled>
        </div>
        <Labelled label="When it still fails">
          <select aria-label="When it still fails" className={fieldClass} value={node.config.onFailure === 'dead_letter' ? 'dead_letter' : 'intervene'}
            onChange={e => setConfig({ onFailure: e.target.value === 'dead_letter' ? 'dead_letter' : undefined })}>
            <option value="intervene">Ask me what to do</option>
            <option value="dead_letter">Stop the run</option>
          </select>
        </Labelled>
        {node.config.onFailure !== 'dead_letter' && (
          <Labelled label="If I don't answer in time" hint="Applied when the question in your inbox expires unanswered.">
            <select aria-label="If I don't answer in time" className={fieldClass}
              value={node.config.ifNoAnswer === 'skip' || node.config.ifNoAnswer === 'retry' ? node.config.ifNoAnswer : 'stop'}
              onChange={e => setConfig({ ifNoAnswer: e.target.value === 'stop' ? undefined : e.target.value })}>
              <option value="stop">Stop the run</option>
              <option value="skip">Skip this step</option>
              <option value="retry">Try again</option>
            </select>
          </Labelled>
        )}
      </>}
    </FoldSection>

    <Section title="Test step">
      <p className="text-[11px] text-gray-500">Test step uses the values as written.</p>
      {!concrete && <p className="text-xs text-amber-700 dark:text-amber-300">Choose a specific browser above to test this step.</p>}
      <Button type="button" variant="secondary" size="sm" disabled={!concrete || testing} onClick={test}>
        {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />} Test step
      </Button>
      {testError && <p className="text-xs text-red-600">{testError}</p>}
      {testResult && <WebTestResultView result={testResult} deviceId={concrete?.id} />}
    </Section>
  </>
}

/** Inputs shown under Advanced options instead of with the step's own fields. */
const ADVANCED_WEB_INPUTS = new Set(['session', 'element', 'trusted', 'timeoutMs', 'screenshotOnError', 'highlight'])

/** Which of Wait's inputs matter for what it waits for; other steps show every input. */
const WAIT_INPUTS: Record<string, string[]> = {
  time: ['ms'], text: ['text'], textGone: ['text'], url: ['urlContains'],
  element: [], elementGone: [], navigation: [],
}

function webInputShown(action: string, key: string, inputs: Record<string, unknown>): boolean {
  if (action !== 'browser.wait' || key === 'for') return true
  const mode = typeof inputs.for === 'string' ? inputs.for : ''
  return (WAIT_INPUTS[mode] ?? []).includes(key)
}

/** A schema input that takes an element (a locator bundle), such as Drag this onto that's "to". */
function isLocatorProperty(property: WebProperty): boolean {
  return typeof property.$ref === 'string' && property.$ref.endsWith('locatorBundle')
}

const WEB_INPUT_LABELS: Record<string, string> = {
  to: 'Drop onto', waitElement: 'Wait for element', nextPage: 'Next page button', urlContains: 'Address contains',
  promptText: 'Text for prompt dialogs', prefer: 'When several match', opener: 'Opens the calendar',
  optionElement: 'One suggestion (optional)', submit: 'Send button (optional)', checkErrors: "Read the site's errors after sending",
  option: 'Suggestion to choose', date: 'Date', item: 'Item to choose',
  message: 'What to say', picture: 'Picture to show (optional)', answerElement: 'Box for the answer (optional)',
  secret: 'Secret answer (never shown or kept)', wantsAnswer: 'Ask for an answer (off: just Done)',
  successUrl: 'Done when the address contains', successText: 'Done when the page shows',
  failureUrl: 'Failed when the address contains', failureText: 'Failed when the page shows',
  expectOrigin: 'Only on this site',
}

/** What a recorded list choice kept about its list (config.list): the names the person gave its
 *  items and cards while recording, and the names the list showed then. */
interface RecordedListNames {
  title?: string
  withinTitle?: string
  named?: boolean
  options?: string[]
  groups?: { label: string; items: string[] }[]
  /** Mapped before recording: the list whose choice this one follows (browser agent 0.9.0+). */
  dependsOn?: { title?: string; inputName?: string; relation?: 'inside' | 'after' }
}

/**
 * The run-time list's own options: choosing inside one card of another list first (a showtime of
 * the theatre chosen), and loading more of a list that grows as you scroll. Each list is one field:
 * a dropdown of the names it showed when recorded (or loaded from the page now), which can also be
 * mapped from earlier steps. The items follow the card chosen.
 */
/** A list another one can load after: its name, the input it is mapped from, and the lists it
 *  follows in turn, outermost first (Trains for Coaches). */
interface EarlierList { title: string; inputName?: string; chain: string[] }

/** What each list of the workflow follows: the card list it is chosen in, and the list it loads after. */
function listParents(graph: CanvasGraph): Map<string, string[]> {
  const parents = new Map<string, string[]>()
  for (const n of graph.nodes) {
    const list = asInputs(n.config.list)
    if (typeof list.title !== 'string' || !list.title.trim()) continue
    const follows = asInputs(list.dependsOn)
    const direct = [
      typeof list.withinTitle === 'string' ? list.withinTitle.trim() : '',
      follows.relation !== 'inside' && typeof follows.title === 'string' ? follows.title.trim() : '',
    ].filter(Boolean)
    if (!parents.has(list.title.trim())) parents.set(list.title.trim(), direct)
  }
  return parents
}

/** The lists one list follows, outermost first: Trains › Coaches for Dates that load after Coaches. */
function chainOf(parents: Map<string, string[]>, direct: string[]): string[] {
  const out: string[] = []
  const visit = (title: string, depth: number) => {
    if (depth > 6 || out.includes(title)) return
    for (const parent of parents.get(title) ?? []) visit(parent, depth + 1)
    if (!out.includes(title)) out.push(title)
  }
  direct.forEach(t => visit(t, 0))
  return out
}

/** The names of the other steps' lists in the workflow (Trains, Coaches), with the input each is
 *  mapped from and the lists it follows, which a list can load after. */
function earlierLists(graph: CanvasGraph, nodeId: string): EarlierList[] {
  const parents = listParents(graph)
  const out = new Map<string, EarlierList>()
  for (const n of graph.nodes) {
    if (n.id === nodeId) continue
    const list = asInputs(n.config.list)
    const inputs = asInputs(n.config.inputs)
    const add = (title: unknown, value: unknown) => {
      if (typeof title !== 'string' || !title.trim() || out.has(title.trim())) return
      const input = typeof value === 'string' ? /^\s*\{\{\s*input\.([A-Za-z0-9_]+)\s*\}\}\s*$/.exec(value)?.[1] : undefined
      const name = title.trim()
      out.set(name, { title: name, ...(input ? { inputName: input } : {}), chain: chainOf(parents, parents.get(name) ?? []) })
    }
    add(list.withinTitle, asInputs(inputs.within).item)
    add(list.title, inputs.item)
  }
  return [...out.values()]
}

function ListChoiceOptions({ inputs, browsers, deviceId, mapping, list = {}, setInput, setInputs, withItem, itemRequired, itemMissing, pictures, onPictures, earlierLists = [], onFollows }: {
  inputs: Record<string, unknown>; browsers?: BrowserAgent[]; deviceId?: string; mapping: MappingContext
  list?: RecordedListNames
  setInput: (key: string, value: unknown) => void
  setInputs: (patch: Record<string, unknown>) => void
  /** The step chooses an item (Choose from a list), not only reads the list. */
  withItem: boolean
  itemRequired?: boolean; itemMissing?: boolean
  /** `config.screenshots` and how to change it: a picture of each item with the question, set per field. */
  pictures?: unknown
  onPictures?: (value: Pictures | undefined) => void
  /** The other lists of the workflow this one can load after, and how to set it. */
  earlierLists?: EarlierList[]
  onFollows?: (dependsOn: RecordedListNames['dependsOn'] | undefined) => void
}) {
  const within = asInputs(inputs.within)
  const loadMore = asInputs(inputs.loadMore)
  const inCard = inputs.within !== undefined
  const loads = inputs.loadMore !== undefined
  const [loadedCards, setLoadedCards] = useState<string[] | undefined>()
  const [loadedItems, setLoadedItems] = useState<{ card: string; items: string[] } | undefined>()
  const fixedCard = typeof within.item === 'string' && within.item.trim() !== '' && !within.item.includes('{{')
  const cardList: Record<string, unknown> | undefined = within.element === undefined ? undefined
    : {
        element: within.element, max: 100,
        ...(typeof within.itemCss === 'string' ? { itemCss: within.itemCss } : {}),
        ...(typeof within.titleCss === 'string' ? { titleCss: within.titleCss } : {}),
      }
  const itemList: Record<string, unknown> | undefined = inputs.element === undefined || (inCard && !fixedCard) ? undefined
    : {
        element: inputs.element, max: 100,
        ...(typeof inputs.itemCss === 'string' ? { itemCss: inputs.itemCss } : {}),
        ...(typeof inputs.titleCss === 'string' ? { titleCss: inputs.titleCss } : {}),
        ...(inCard ? { within: { element: within.element, item: within.item, ...(typeof within.itemCss === 'string' ? { itemCss: within.itemCss } : {}), ...(typeof within.titleCss === 'string' ? { titleCss: within.titleCss } : {}) } } : {}),
      }
  const cardName = list.named && list.withinTitle ? list.withinTitle : undefined
  const itemName = list.named && list.title ? list.title : undefined
  const groups = list.groups ?? []
  const cardChoices = loadedCards ?? groups.map(g => g.label)
  // The items of the card chosen; with the card mapped (chosen each run), the items of every card.
  const currentCard = typeof within.item === 'string' ? within.item : ''
  // Inside a card with no card's items known (the card box just ticked, nothing loaded yet): the
  // names the list showed when recorded still offer the item, such as a card's Book Now, so the
  // field stays a list instead of turning into a mapping box.
  const cardItems = fixedCard ? groups.find(g => g.label === within.item)?.items ?? [] : [...new Set(groups.flatMap(g => g.items))]
  const itemChoices = loadedItems && (!inCard || loadedItems.card === currentCard)
    ? loadedItems.items
    : inCard && cardItems.length > 0
      ? cardItems
      : list.options ?? []
  const chooseCard = (value: string) => {
    // A new card has other items: an item chosen from the old card's names goes.
    const item = typeof inputs.item === 'string' ? inputs.item : ''
    const items = groups.find(g => g.label === value)?.items
    const stale = item !== '' && !item.includes('{{') && items !== undefined && !items.includes(item)
    setInputs({ within: { ...within, item: value }, ...(stale ? { item: undefined } : {}) })
  }
  const follows = list.dependsOn?.title
  // Every list this one follows, outermost first: the card list it is in, then what it loads after
  // and what that follows in turn (Trains › Coaches for Dates).
  const followChain = (() => {
    const after = follows && list.dependsOn?.relation !== 'inside' ? follows : undefined
    const found = after ? earlierLists.find(l => l.title === after) : undefined
    const chain = [...(found?.chain ?? []), ...(after ? [after] : [])]
    const card = inCard && list.withinTitle ? list.withinTitle : undefined
    return card && !chain.includes(card) ? [card, ...chain] : chain
  })()
  const fields = pictureFields(inCard)
  const picturesSwitch = (field: PictureField, what: string, hint: string) => onPictures && (
    <div className="space-y-1">
      <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={wantsPictures(pictures, field)}
          onChange={e => onPictures(withPictures(pictures, field, e.target.checked, fields))} />
        Send a picture of each {what} with the question
      </label>
      {wantsPictures(pictures, field) && <p className="text-[11px] text-gray-500">{hint}</p>}
    </div>
  )
  return <div className="space-y-2">
    {follows && list.dependsOn?.relation !== 'inside' && <p className="rounded-lg border border-gray-200 p-2 text-[11px] text-gray-500 dark:border-gray-700" data-testid="list-follows">
      Follows {follows}{list.dependsOn?.inputName ? ` (input ${list.dependsOn.inputName})` : ''}: the page loads these choices again when {follows} changes. After changing {follows}, load them from the page.
    </p>}
    {onFollows && (earlierLists.length > 0 || (follows && list.dependsOn?.relation !== 'inside')) && <label className="block space-y-1 text-xs text-gray-700 dark:text-gray-300">
      <span>Loads after (depends on)</span>
      <select className={fieldClass} aria-label="Loads after" data-testid="list-loads-after"
        value={follows && list.dependsOn?.relation !== 'inside' ? follows : ''}
        onChange={e => {
          const picked = earlierLists.find(l => l.title === e.target.value)
          onFollows(picked ? { title: picked.title, ...(picked.inputName ? { inputName: picked.inputName } : {}), relation: 'after' } : undefined)
        }}>
        <option value="">Nothing: its choices show on their own</option>
        {[...earlierLists, ...(follows && list.dependsOn?.relation !== 'inside' && !earlierLists.some(l => l.title === follows) ? [{ title: follows }] : [])]
          .filter(l => l.title !== (list.named ? list.title : undefined))
          .map(l => <option key={l.title} value={l.title}>{[...('chain' in l ? l.chain : []), l.title].join(' › ')}</option>)}
      </select>
      {followChain.length > 0 && <p className="text-[11px] text-gray-700 dark:text-gray-300" data-testid="list-chain">
        Chosen in order: {[...followChain, itemName ?? 'this list'].join(' › ')}
      </p>}
      <span className="block text-[11px] text-gray-500">Such as Dates that show once a Coach is chosen. When an earlier step of the run chose it (and the card it is in), the approval shows those choices as fixed instead of asking again.</span>
    </label>}
    {inCard && <div className="space-y-2 border-l-2 border-gray-200 pl-3 dark:border-gray-700">
      <ListValueField label={cardName ?? 'Which card'} choices={cardChoices} value={within.item} mapping={mapping} required
        hint={cardChoices.length > 0 ? `Chosen first, such as a theatre. Its ${(itemName ?? 'item').toLowerCase()}s follow it.` : 'Its name, such as a theatre. Map it from earlier steps.'}
        onChange={chooseCard} />
      {picturesSwitch('within.item', 'card', 'Reading the cards also takes a picture of each one (the first 20), and the question shows the picture of whichever card you pick.')}
      <LoadFromPage what="cards" deviceId={deviceId} list={cardList} onLoaded={setLoadedCards} />
    </div>}
    {withItem && <ListValueField label={itemName ?? 'Item to choose'} choices={itemChoices} value={inputs.item} mapping={mapping}
      required={itemRequired} missing={itemMissing}
      hint={inCard && !fixedCard && groups.length > 0 && itemChoices.length > 0
        ? `The ${(itemName ?? 'item').toLowerCase()}s of every ${(cardName ?? 'card').toLowerCase()}: choose one above to see only its own.`
        : 'Map it from earlier steps, or choose one of the names the list showed.'}
      onChange={v => setInput('item', v)} />}
    {withItem && picturesSwitch('item', 'item', 'Reading the list also takes a picture of each card (the first 20), and the question shows the picture of whichever one you pick.')}
    <MoreOptions summary={[inCard ? 'inside a card' : undefined, loads ? 'loads more as you scroll' : undefined, 'load from the page, patterns'].filter(Boolean).join(' · ')}
      hasError={inCard && within.element === undefined}>
    {inputs.element !== undefined && <LoadFromPage what="items" deviceId={deviceId} list={itemList}
      blocked={inCard && !fixedCard ? 'Choose a card above (a fixed one, not mapped) to load its items.' : undefined}
      onLoaded={labels => setLoadedItems({ card: currentCard, items: labels })} />}
    <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={inCard}
        onChange={e => setInput('within', e.target.checked ? { item: '' } : undefined)} />
      Choose inside a card of another list first
    </label>
    {inCard && <div className="space-y-2 border-l-2 border-gray-200 pl-3 dark:border-gray-700">
      <p className="text-[11px] text-gray-500">Such as a showtime inside the theatre you choose: pick one card of the outer list (a theatre).</p>
      <ElementField label="One card of the outer list" value={within.element} deviceId={deviceId} browsers={browsers}
        hasEarlierOpen mapping={mapping} required missing={within.element === undefined}
        onChange={value => setInput('within', { ...within, element: value })} />
      <PatternField label="Card pattern (CSS)" value={within.itemCss}
        onChange={v => setInput('within', { ...within, itemCss: v })} />
      {typeof within.titleCss === 'string' && <PatternField label="Card name pattern (CSS)" value={within.titleCss}
        onChange={v => setInput('within', { ...within, titleCss: v })} />}
    </div>}
    {inputs.element !== undefined && <div className="space-y-1">
      <PatternField label="Item pattern (CSS)" value={inputs.itemCss} onChange={v => setInput('itemCss', v)} />
      {typeof inputs.titleCss === 'string' && <PatternField label="Item name pattern (CSS)" value={inputs.titleCss}
        onChange={v => setInput('titleCss', v)} />}
    </div>}
    <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" checked={loads}
        onChange={e => setInput('loadMore', e.target.checked ? { rounds: 5 } : undefined)} />
      The list loads more as you scroll
    </label>
    {loads && <div className="space-y-2 border-l-2 border-gray-200 pl-3 dark:border-gray-700">
      <ElementField label="Load more button (optional)" value={loadMore.button} deviceId={deviceId} browsers={browsers}
        hasEarlierOpen mapping={mapping} required={false} missing={false}
        onChange={value => setInput('loadMore', { ...loadMore, button: value })} />
      <Labelled label="Times to load more" hint="It stops sooner when the list stops growing.">
        <input type="number" aria-label="Times to load more" min={1} max={30} className={fieldClass}
          value={typeof loadMore.rounds === 'number' ? loadMore.rounds : 5}
          onChange={e => setInput('loadMore', { ...loadMore, rounds: Math.max(1, Math.min(30, Number(e.target.value) || 1)) })} />
      </Labelled>
    </div>}
    </MoreOptions>
  </div>
}

const MAP_CHOICE = '__map'

/**
 * One list's one field: a dropdown of its names (and the value set now, when it is not one of
 * them), with "Map from earlier steps or type…" to map it instead. A mapped value, or a list with
 * no names to offer, is a mapped text field.
 */
export function ListValueField({ label, choices, value, mapping, required, missing, hint, onChange }: {
  label: string; choices: string[]; value: unknown; mapping: MappingContext
  required?: boolean; missing?: boolean; hint?: string
  onChange: (value: string) => void
}) {
  const text = typeof value === 'string' ? value : ''
  const [typing, setTyping] = useState(false)
  const mapped = text.includes('{{')
  if (choices.length === 0 || mapped || typing) {
    return <Labelled group label={label} required={required} hint={hint}>
      <MappedField aria-label={label} mode="template" context={mapping} value={text} invalid={missing}
        onChange={v => onChange(v)} />
      {choices.length > 0 && <button type="button" className="self-start text-[11px] text-brand-600 hover:underline dark:text-brand-300"
        onClick={() => { setTyping(false); if (mapped) onChange('') }}>Choose from the list instead</button>}
    </Labelled>
  }
  const extra = text !== '' && !choices.includes(text)
  return <Labelled label={label} required={required} hint={hint}>
    <select aria-label={label} className={fieldClass} value={text}
      onChange={e => {
        if (e.target.value === MAP_CHOICE) { setTyping(true); return }
        onChange(e.target.value)
      }}>
      <option value="">Choose one of {choices.length}…</option>
      {extra && <option value={text}>{text}</option>}
      {choices.map(v => <option key={v} value={v}>{v}</option>)}
      <option value={MAP_CHOICE}>Map from earlier steps or type…</option>
    </select>
  </Labelled>
}

/**
 * Reads a list from the page as the chosen browser shows it now (List items, run as a test): its
 * names become the choices of the list's own dropdown (a theatre, then its showtimes).
 */
export function LoadFromPage({ what, deviceId, list, blocked, onLoaded, action = 'browser.listItems' }: {
  what: 'cards' | 'items'; deviceId?: string; list?: Record<string, unknown>; blocked?: string
  onLoaded: (labels: string[]) => void
  /** The action that reads the list: List items, or List dropdown items for a dropdown. */
  action?: string
}) {
  const [loading, setLoading] = useState(false)
  const [found, setFound] = useState<number | undefined>()
  const [error, setError] = useState<string | undefined>()
  const reason = blocked ?? (!deviceId ? 'Choose a browser above to load from its page.' : undefined)
  const load = async () => {
    if (!deviceId || !list) return
    setLoading(true); setError(undefined); setFound(undefined)
    try {
      const result = await webApi.testStep({ deviceId, action, version: 1, inputs: list })
      const items = (result.output as { items?: { label?: string }[] } | undefined)?.items
      if (result.status === 'succeeded' && items) {
        const labels = [...new Set(items.map(i => i.label ?? '').filter(Boolean))]
        setFound(labels.length)
        if (labels.length === 0) setError('The page shows no such list now.')
        else onLoaded(labels)
      } else {
        setError(result.error?.detail ?? result.message ?? 'The list could not be read.')
      }
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      setError(problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'The list could not be read.'))
    } finally {
      setLoading(false)
    }
  }
  if (!list && !blocked) return null
  const noun = what === 'cards' ? 'cards' : 'items'
  return <div className="space-y-1">
    <button type="button" disabled={loading || reason !== undefined} onClick={() => void load()}
      title={reason ?? `Read the ${noun} the page shows now, in the browser chosen above`}
      className="inline-flex items-center gap-1 self-start text-xs text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-300">
      {loading && <Loader2 className="h-3 w-3 animate-spin" />}Load {noun} from the page
    </button>
    {reason && <p className="text-[11px] text-gray-500">{reason}</p>}
    {found !== undefined && found > 0 && <p className="text-[11px] text-gray-500">{found} loaded: choose one above.</p>}
    {error && <p className="text-[11px] text-red-600">{error} Open the page in that browser first.</p>}
  </div>
}

/** What a dropdown step's item is called: the dropdown's recorded label. */
function dropdownLabel(element: unknown): string {
  const label = (element as { label?: unknown } | undefined)?.label
  return typeof label === 'string' && label.trim() && label.length <= 60 ? label.trim() : 'Item to choose'
}

/**
 * Where a dropdown step's items come from. Static: the items kept with the step (read while
 * recording or loaded here), offered as the choices. Dynamic: read from the page on each run, and
 * offered on the approval card as the page shows them then.
 */
function DropdownItems({ inputs, deviceId, setInputs }: {
  inputs: Record<string, unknown>; deviceId?: string; setInputs: (patch: Record<string, unknown>) => void
}) {
  const dynamic = inputs.mode === 'dynamic'
  const items = Array.isArray(inputs.options) ? (inputs.options as unknown[]).map(String) : []
  const [text, setText] = useState(items.join(', '))
  useEffect(() => setText(items.join(', ')), [items.join('\u0000')])
  const keep = (list: string[]) => setInputs({ options: list.length ? list : undefined, mode: 'static' })
  return <Section title="Dropdown items">
    <div role="radiogroup" aria-label="Dropdown items" className="flex flex-wrap gap-3 text-xs text-gray-700 dark:text-gray-300">
      <label className="flex items-center gap-1.5">
        <input type="radio" name="dropdown-items" checked={!dynamic} onChange={() => setInputs({ mode: 'static' })} />Static items
      </label>
      <label className="flex items-center gap-1.5">
        <input type="radio" name="dropdown-items" checked={dynamic} onChange={() => setInputs({ mode: 'dynamic', options: undefined })} />Dynamic items
      </label>
    </div>
    <p className="text-[11px] text-gray-500">{dynamic
      ? 'Each run reads the items the dropdown shows then, chooses the one named and asks you when none matches.'
      : 'The items below are kept with the step and offered as its choices. A value that is not one of them is asked.'}</p>
    {!dynamic && (
      <Labelled group label="Items" hint="Comma-separated. Load them from the page, or type them.">
        <input aria-label="Dropdown items" className={fieldClass} value={text}
          onChange={e => setText(e.target.value)}
          onBlur={() => keep(text.split(',').map(s => s.trim()).filter(Boolean))} />
      </Labelled>
    )}
    {!dynamic && (
      <LoadFromPage what="items" action="browser.listOptions" deviceId={deviceId}
        list={inputs.element ? { element: inputs.element } : undefined} onLoaded={keep} />
    )}
  </Section>
}

type DetailBlock = {
  kind: 'note' | 'field'; text?: string; name?: string; label?: string; type?: string
  options?: string[]; required?: boolean; value?: unknown; format?: string
}

const DETAIL_FIELD_TYPES = [
  { type: 'text', label: 'Text' }, { type: 'number', label: 'Number' },
  { type: 'select', label: 'Choice' }, { type: 'checkbox', label: 'Checkbox' },
  { type: 'date', label: 'Date' }, { type: 'datetime', label: 'Date and time' },
]

function detailName(label: string, blocks: DetailBlock[], at: number): string {
  const base = label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40) || 'answer'
  let name = base
  for (let n = 2; blocks.some((b, j) => j !== at && b.kind === 'field' && b.name === name); n += 1) name = `${base}_${n}`
  return name
}

/**
 * Notes and questions the step puts on its approval card: the details a person needs before
 * letting it go on (a berth preference, a remark), asked with the step's question and kept as
 * the step's `details` output for later steps.
 */
function StepDetailsEditor({ nodeId, value, mapping, onChange }: {
  nodeId: string; value: unknown; mapping: MappingContext; onChange: (value: unknown) => void
}) {
  const blocks: DetailBlock[] = Array.isArray((value as { blocks?: unknown } | undefined)?.blocks)
    ? ((value as { blocks: unknown[] }).blocks.filter(b => b && typeof b === 'object') as DetailBlock[])
    : []
  const keep = (next: DetailBlock[]) => onChange(next.length ? { blocks: next } : undefined)
  const update = (at: number, patch: Partial<DetailBlock>) => keep(blocks.map((b, j) => j === at ? { ...b, ...patch } : b))
  return <div className="space-y-2">
    <p className="text-[11px] text-gray-500">
      Notes to read and questions to answer when this step stops for you. The run goes on only once every question is answered; the answers are this step's output, as {'{{'} nodes.{nodeId}.details.name {'}}'}. Nothing here is typed into the site.
    </p>
    {blocks.map((block, i) => (
      <div key={i} className="space-y-2 rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-medium uppercase tracking-wide text-gray-500">{block.kind === 'note' ? 'Note' : 'Question'}</span>
          <button type="button" aria-label={`Remove ${block.kind === 'note' ? 'note' : 'question'} ${i + 1}`}
            className="text-[11px] text-red-600 hover:underline dark:text-red-400"
            onClick={() => keep(blocks.filter((_, j) => j !== i))}>Remove</button>
        </div>
        {block.kind === 'note' ? (
          <textarea aria-label={`Note ${i + 1}`} className={`${fieldClass} min-h-[52px]`} value={block.text ?? ''}
            placeholder="What the person should read before deciding, e.g. Check the running days before booking."
            onChange={e => update(i, { text: e.target.value.slice(0, 500) })} />
        ) : <>
          <div className="grid grid-cols-2 gap-2">
            <Labelled label="Question">
              <input aria-label={`Question ${i + 1}`} className={fieldClass} value={block.label ?? ''}
                placeholder="e.g. Berth preference"
                onChange={e => update(i, { label: e.target.value.slice(0, 200), name: detailName(e.target.value, blocks, i) })} />
            </Labelled>
            <Labelled label="Answer type">
              <select aria-label={`Answer type of question ${i + 1}`} className={fieldClass} value={block.type ?? 'text'}
                onChange={e => update(i, { type: e.target.value, options: e.target.value === 'select' ? block.options : undefined })}>
                {DETAIL_FIELD_TYPES.map(t => <option key={t.type} value={t.type}>{t.label}</option>)}
              </select>
            </Labelled>
          </div>
          {block.type === 'select' && (
            <DetailOptionsInput index={i} options={block.options} onChange={options => update(i, { options })} />
          )}
          <Labelled group label="Starts as (optional)" hint="A value or a mapping from earlier steps; the person can change it.">
            <MappedField aria-label={`Default answer of question ${i + 1}`} mode="template" context={mapping}
              value={typeof block.value === 'string' ? block.value : block.value == null ? '' : String(block.value)}
              onChange={v => update(i, { value: v || undefined })} />
          </Labelled>
          <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
            <input type="checkbox" checked={block.required !== false}
              onChange={e => update(i, { required: e.target.checked ? undefined : false })} />
            Must be answered before the run goes on
          </label>
        </>}
      </div>
    ))}
    <div className="flex gap-2">
      <Button type="button" size="sm" variant="secondary"
        onClick={() => keep([...blocks, { kind: 'note', text: '' }])}>Add a note</Button>
      <Button type="button" size="sm" variant="secondary"
        onClick={() => keep([...blocks, { kind: 'field', label: '', name: detailName('', blocks, blocks.length), type: 'text' }])}>Add a question</Button>
    </div>
  </div>
}

/** The choices of a Choice question, typed comma-separated and kept as a list. */
function DetailOptionsInput({ index, options, onChange }: {
  index: number; options: string[] | undefined; onChange: (options: string[] | undefined) => void
}) {
  const items = Array.isArray(options) ? options : []
  const [text, setText] = useState(items.join(', '))
  useEffect(() => setText(items.join(', ')), [items.join('\u0000')])
  return <Labelled group label="Choices" hint="Comma-separated, e.g. Lower, Middle, Upper.">
    <input aria-label={`Choices of question ${index + 1}`} className={fieldClass} value={text}
      onChange={e => setText(e.target.value)}
      onBlur={() => {
        const list = text.split(',').map(t => t.trim()).filter(Boolean).slice(0, 50)
        onChange(list.length ? list : undefined)
      }} />
  </Labelled>
}

/** The CSS pattern Choose from a list of cards saved, editable for a site whose markup changed. */
function PatternField({ label, value, onChange }: { label: string; value: unknown; onChange: (value: string | undefined) => void }) {
  return <Labelled label={label} hint="Saved when you marked the list while recording; the list is read with it first. Leave empty to find the list by its shape.">
    <input aria-label={label} className={fieldClass} value={typeof value === 'string' ? value : ''}
      placeholder="Found by its shape"
      onChange={e => onChange(e.target.value.trim() === '' ? undefined : e.target.value)} />
  </Labelled>
}

function webInputLabel(name: string): string {
  return WEB_INPUT_LABELS[name] ?? name.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase())
}

function WebSchemaField({ name, property, value, required, mapping, label: named, onChange }: {
  name: string; property: WebProperty; value: unknown; required: boolean
  mapping: MappingContext; label?: string; onChange: (value: unknown) => void
}) {
  const label = named ?? webInputLabel(name)
  const hint = property.description
  const [fromData, setFromData] = useState(typeof value === 'string' && value.includes('{{'))
  if (property.enum && fromData) {
    return <Labelled group label={label} required={required} hint={hint ?? `Mapped: the value must be one of ${property.enum.join(', ')} (or yes/no for a checkbox).`}>
      <MappedField aria-label={label} mode="template" context={mapping}
        value={typeof value === 'string' ? value : ''} onChange={v => onChange(v)} />
      <button type="button" className="self-start text-[11px] text-brand-600 hover:underline dark:text-brand-300"
        onClick={() => { setFromData(false); onChange(undefined) }}>Choose a fixed value instead</button>
    </Labelled>
  }
  if (property.enum) {
    return <Labelled label={label} required={required} hint={hint}>
      <select aria-label={label} className={fieldClass} value={value == null ? '' : String(value)}
        onChange={e => {
          if (e.target.value === '__data') { setFromData(true); onChange(undefined); return }
          onChange(e.target.value === '' ? undefined : property.enum?.find(v => String(v) === e.target.value))
        }}>
        <option value="">Choose…</option>
        {property.enum.map(v => <option key={String(v)} value={String(v)}>{String(v)}</option>)}
        <option value="__data">Map from earlier steps…</option>
      </select>
    </Labelled>
  }
  if (property.type === 'boolean') {
    return <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
      <input type="checkbox" aria-label={label} checked={value === true} onChange={e => onChange(e.target.checked)} /> {label}
    </label>
  }
  if (property.type === 'object' || property.type === 'array' || property.oneOf || property.$ref) {
    return <JsonField label={label} value={value} hint={hint ?? 'Enter compact JSON.'} onChange={onChange} />
  }
  return <Labelled group label={label} required={required} hint={hint}>
    <MappedField aria-label={label} mode="template" context={mapping} mono={property.type !== 'string'}
      value={value == null ? '' : typeof value === 'string' ? value : String(value)}
      onChange={v => onChange((property.type === 'number' || property.type === 'integer') && v && !v.includes('{{') && !Number.isNaN(Number(v)) ? Number(v) : v)} />
  </Labelled>
}

type FormFieldKind = 'text' | 'select' | 'checkbox' | 'checkboxes' | 'radio' | 'date' | 'datepicker' | 'combobox'
type FormFieldRow = { element?: unknown; kind: FormFieldKind; value?: unknown; label?: string; required?: boolean; format?: string }

const FORM_FIELD_KINDS: { kind: FormFieldKind; label: string; hint: string }[] = [
  { kind: 'text', label: 'Text or text area', hint: 'The text to type.' },
  { kind: 'select', label: 'Dropdown', hint: 'The option to choose, as the dropdown shows it.' },
  { kind: 'checkbox', label: 'Checkbox', hint: 'Yes or no (true or false).' },
  { kind: 'checkboxes', label: 'Checkbox group', hint: 'The boxes to tick, comma-separated. Others are unticked.' },
  { kind: 'radio', label: 'Radio buttons', hint: 'The option to choose, as its label reads. Pick the group.' },
  { kind: 'date', label: 'Date or date and time', hint: 'Such as 2026-11-09, 9 Nov 2026 or 2026-11-09 18:30.' },
  { kind: 'datepicker', label: 'Calendar the site draws', hint: 'The date to pick. Pick the field that opens the calendar.' },
  { kind: 'combobox', label: 'Search and choose', hint: 'What to type; the suggestion that matches is chosen.' },
]

/** Same test as the step's check before saving: picked on the page, or mapped. */
const located = (element: unknown) => typeof element === 'string' ? element.includes('{{')
  : Array.isArray((element as { strategies?: unknown[] } | undefined)?.strategies) && (element as { strategies: unknown[] }).strategies.length > 0

const asRows = (value: unknown): FormFieldRow[] =>
  Array.isArray(value) ? value.filter((v): v is FormFieldRow => !!v && typeof v === 'object') : []

/** Fill form: one row per field, each an element, what kind of field it is, and its value. */
function FillFormFields({ value, browsers, deviceId, mapping, missing, onChange }: {
  value: unknown; browsers?: BrowserAgent[]; deviceId?: string; mapping: MappingContext; missing?: boolean
  onChange: (value: unknown) => void
}) {
  const rows = asRows(value)
  const set = (index: number, patch: Partial<FormFieldRow>) => {
    const next = rows.map((row, i) => {
      if (i !== index) return row
      const merged: FormFieldRow = { ...row, ...patch }
      for (const key of Object.keys(merged) as (keyof FormFieldRow)[]) {
        if (merged[key] === undefined || merged[key] === '' || merged[key] === false) delete merged[key]
      }
      return merged
    })
    onChange(next)
  }
  return (
    <Section title="Form fields">
      <p className="text-[11px] text-gray-500">Each field is filled in turn. Map values from earlier steps to fill them at run time; an optional field whose value comes out empty is left as it is.</p>
      {missing && rows.length === 0 && <p className="text-xs text-red-600">Add at least one field.</p>}
      {rows.map((row, index) => {
        const kind = FORM_FIELD_KINDS.find(k => k.kind === row.kind) ?? FORM_FIELD_KINDS[0]
        const label = row.label || (row.element as { label?: string } | undefined)?.label || `Field ${index + 1}`
        return (
          <div key={index} className="space-y-2 rounded-lg border border-gray-200 p-2 dark:border-gray-800">
            <div className="flex items-center gap-2">
              <span className="min-w-0 flex-1 truncate text-xs font-semibold text-gray-700 dark:text-gray-200">{label}</span>
              <label className="flex shrink-0 items-center gap-1 text-[11px] text-gray-600 dark:text-gray-300"
                title="Mandatory: if its value comes out empty, ask me to fill it in before the step runs">
                <input type="checkbox" aria-label={`${label} is mandatory: if its value comes out empty, ask me to fill it in before the step runs`}
                  checked={row.required === true} onChange={e => set(index, { required: e.target.checked })} />
                Mandatory
              </label>
              <button type="button" aria-label={`Remove ${label}`} onClick={() => onChange(rows.filter((_, i) => i !== index))}
                className="text-gray-400 hover:text-red-600"><Trash2 className="h-3.5 w-3.5" /></button>
            </div>
            <Labelled group label="Value" hint={kind.hint}>
              <MappedField aria-label={`Value of ${label}`} mode="template" context={mapping}
                value={Array.isArray(row.value) ? row.value.join(', ') : row.value == null ? '' : String(row.value)}
                onChange={v => set(index, { value: v })} />
            </Labelled>
            <MoreOptions summary={kind.label} hasError={missing && !located(row.element)}>
            <ElementField label={row.kind === 'checkboxes' || row.kind === 'radio' ? 'Group (or one box in it)' : 'Field'}
              value={row.element} deviceId={deviceId} browsers={browsers} hasEarlierOpen mapping={mapping} required
              missing={missing && !located(row.element)} onChange={element => set(index, { element })} />
            <div className="grid grid-cols-2 gap-2">
              <Labelled label="Kind">
                <select aria-label={`Kind of ${label}`} className={fieldClass} value={kind.kind}
                  onChange={e => set(index, { kind: e.target.value as FormFieldKind })}>
                  {FORM_FIELD_KINDS.map(k => <option key={k.kind} value={k.kind}>{k.label}</option>)}
                </select>
              </Labelled>
              <Labelled label="Name on approvals">
                <input aria-label={`Name of ${label}`} className={fieldClass} value={row.label ?? ''} placeholder={label}
                  onChange={e => set(index, { label: e.target.value })} />
              </Labelled>
            </div>
            {row.kind === 'date' && (
              <Labelled label="Date format (text fields only)" hint="Such as DD/MM/YYYY. Date pickers built into the browser need none.">
                <input aria-label={`Date format of ${label}`} className={fieldClass} value={row.format ?? ''}
                  onChange={e => set(index, { format: e.target.value })} />
              </Labelled>
            )}
            </MoreOptions>
          </div>
        )
      })}
      <Button type="button" variant="secondary" size="sm" onClick={() => onChange([...rows, { kind: 'text' }])}>
        <Plus className="h-3.5 w-3.5" /> Fill or click one thing
      </Button>
    </Section>
  )
}

type LocatorStrategy = { by: string; value?: string; role?: string; name?: string; exact?: boolean }

export function ElementField({ label: fieldLabel = 'Element', value, deviceId, browsers, hasEarlierOpen, mapping, required, missing, onChange }: {
  label?: string; value: unknown; deviceId?: string; browsers?: BrowserAgent[]; hasEarlierOpen?: boolean
  mapping: MappingContext; required?: boolean; missing?: boolean; onChange: (value: unknown) => void
}) {
  const template = typeof value === 'string' ? value : ''
  const bundle = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
  const strategies = Array.isArray(bundle.strategies) ? bundle.strategies as LocatorStrategy[] : []
  const [editing, setEditing] = useState(false)
  const preview = typeof bundle.preview === 'string' ? bundle.preview : undefined
  const pagePreview = typeof bundle.pagePreview === 'string' ? bundle.pagePreview : undefined
  const online = (browsers ?? []).filter(b => b.online)
  const [chosen, setChosen] = useState('')
  const pickDevice = deviceId ?? (online.length === 1 ? online[0].id : chosen)
  const browserName = browsers?.find(b => b.id === pickDevice)?.family
  const family = browserName ? browserName[0].toUpperCase() + browserName.slice(1) : 'your browser'
  const [pageUrl, setPageUrl] = useState('')
  const [picking, setPicking] = useState<string>()
  const [pickError, setPickError] = useState('')
  const latestChange = useRef(onChange)
  latestChange.current = onChange
  useEffect(() => {
    if (!picking) return
    let stop = false
    const timer = setInterval(async () => {
      try {
        const r = await webApi.pickResult(picking)
        if (stop || r.status === 'waiting') return
        setPicking(undefined)
        if (r.status === 'done' && r.element) {
          latestChange.current({ ...r.element, ...(r.preview ? { preview: r.preview } : {}), ...(r.pagePreview ? { pagePreview: r.pagePreview } : {}) })
        } else if (r.error?.kind !== 'cancelled_by_person') {
          setPickError(r.error?.detail ?? 'Picking did not finish.')
        }
      } catch (e) {
        if (stop) return
        setPicking(undefined)
        setPickError(e instanceof Error ? e.message : 'Picking did not finish.')
      }
    }, 1000)
    return () => { stop = true; clearInterval(timer) }
  }, [picking])
  const startPick = async () => {
    setPickError('')
    try {
      const started = await webApi.pick({ deviceId: pickDevice, url: pageUrl.trim() || undefined })
      setPicking(started.commandId)
    } catch (e) {
      const problem = (e as { problem?: { detail?: string; title?: string } }).problem
      setPickError(problem?.detail ?? problem?.title ?? (e instanceof Error ? e.message : 'Could not start picking.'))
    }
  }
  // Once picked, picking again sits beside Edit selectors: one line instead of three.
  const inline = !template && strategies.length > 0 && !!pickDevice
  const cancelPick = () => {
    const id = picking
    setPicking(undefined)
    if (id && pickDevice) devicesApi.cancelCommand(pickDevice, id).catch(() => {})
  }
  return <Labelled group label={fieldLabel} required={required} missing={missing}>
    {template ? (
      <MappedField aria-label={fieldLabel} mode="template" context={mapping} mono value={template} onChange={onChange} />
    ) : (
      <div className="rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex items-center gap-2">
          <span className="min-w-0 flex-1 truncate text-xs text-gray-700 dark:text-gray-300">
            {typeof bundle.label === 'string' && bundle.label ? bundle.label : strategies.length ? `${strategies.length} selector${strategies.length === 1 ? '' : 's'}` : 'No element selected'}
          </span>
          {inline && <Button type="button" variant="secondary" size="sm" disabled={!pickDevice} onClick={startPick}>Pick on page</Button>}
          <Button type="button" variant="secondary" size="sm" onClick={() => setEditing(true)}>Edit selectors</Button>
        </div>
        {(preview || pagePreview) && pickDevice && <div className="mt-2 flex items-start gap-2">
          <ElementPreview deviceId={pickDevice} preview={preview} pagePreview={pagePreview} className="max-h-32 rounded border border-gray-200" />
          {pagePreview && <span className="text-[11px] text-gray-500">Click the picture to see it on the page.</span>}
        </div>}
      </div>
    )}
    {picking ? (
      <div className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        <span className="flex-1">Click the element in {family}… Press Esc in {family} to cancel</span>
        <Button type="button" variant="secondary" size="sm" onClick={cancelPick}>Cancel</Button>
      </div>
    ) : !inline && <>
      {!deviceId && online.length > 1 && (
        <select aria-label="Browser to pick in" className={fieldClass} value={chosen} onChange={e => setChosen(e.target.value)}>
          <option value="">Choose a browser…</option>
          {online.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
        </select>
      )}
      {!hasEarlierOpen && (
        <input aria-label="Open this page first" className={fieldClass} placeholder="Open this page first (optional), e.g. https://example.com"
          value={pageUrl} onChange={e => setPageUrl(e.target.value)} />
      )}
      <div className="flex items-center gap-2">
        <Button type="button" variant="secondary" size="sm" disabled={!pickDevice} onClick={startPick}>Pick on page</Button>
        {!pickDevice && <span className="text-[11px] text-gray-400">{online.length === 0 ? 'No browser agent is online.' : 'Choose a browser to pick in.'}</span>}
      </div>
    </>}
    {pickError && <p className="text-xs text-red-600">{pickError}</p>}
    {editing && <SelectorDialog bundle={bundle} mapping={mapping} onClose={() => setEditing(false)} onSave={next => { onChange(next); setEditing(false) }} />}
  </Labelled>
}

function SelectorDialog({ bundle, mapping, onClose, onSave }: {
  bundle: Record<string, unknown>; mapping: MappingContext; onClose: () => void; onSave: (value: Record<string, unknown>) => void
}) {
  const [label, setLabel] = useState(typeof bundle.label === 'string' ? bundle.label : '')
  const [rows, setRows] = useState<LocatorStrategy[]>(() =>
    Array.isArray(bundle.strategies) ? structuredClone(bundle.strategies as LocatorStrategy[]) : [])
  type FrameLevel = { strategies: LocatorStrategy[] }
  const [frames, setFrames] = useState<FrameLevel[]>(() =>
    Array.isArray(bundle.frames) ? structuredClone(bundle.frames as FrameLevel[]) : [])
  const [shadow, setShadow] = useState<LocatorStrategy[]>(() =>
    Array.isArray(bundle.shadow) ? structuredClone(bundle.shadow as LocatorStrategy[]) : [])
  const update = (i: number, patch: Partial<LocatorStrategy>) => setRows(r => r.map((row, j) => j === i ? { ...row, ...patch } : row))
  const setFrame = (i: number, value: string) => setFrames(f => f.map((level, j) => j === i
    ? { strategies: [{ ...(level.strategies[0] ?? { by: 'css' }), by: level.strategies[0]?.by ?? 'css', value }, ...level.strategies.slice(1)] } : level))
  const save = () => {
    const next: Record<string, unknown> = { ...bundle, label: label || undefined, strategies: rows }
    const keptFrames = frames.filter(level => level.strategies[0]?.value?.trim())
    const keptShadow = shadow.filter(host => host.value?.trim())
    if (keptFrames.length) next.frames = keptFrames
    else delete next.frames
    if (keptShadow.length) next.shadow = keptShadow
    else delete next.shadow
    onSave(next)
  }
  const move = (i: number, by: number) => setRows(r => {
    const next = [...r]
    const [row] = next.splice(i, 1)
    next.splice(i + by, 0, row)
    return next
  })
  return <div role="dialog" aria-modal="true" aria-label="Edit selectors" className="fixed inset-0 z-[80] grid place-items-center bg-black/30 p-4">
    <div className="flex max-h-[80vh] w-full max-w-xl flex-col gap-3 overflow-y-auto rounded-xl bg-white p-4 shadow-xl dark:bg-gray-900">
      <div className="flex items-center"><h3 className="flex-1 text-sm font-semibold">Edit selectors</h3><button aria-label="Close selectors" onClick={onClose}><X className="h-4 w-4" /></button></div>
      <Labelled label="Element label"><input aria-label="Element label" className={fieldClass} value={label} onChange={e => setLabel(e.target.value)} /></Labelled>
      {rows.map((row, i) => <div key={i} className="rounded-lg border border-gray-200 p-2 dark:border-gray-700">
        <div className="flex gap-1">
          <select aria-label={`Selector strategy ${i + 1}`} className={fieldClass} value={row.by} onChange={e => update(i, { by: e.target.value })}>
            {['testid', 'role', 'label', 'placeholder', 'name', 'near', 'css', 'text', 'xpath'].map(by => <option key={by}>{by}</option>)}
          </select>
          <button aria-label={`Move selector ${i + 1} up`} disabled={i === 0} onClick={() => move(i, -1)}><ChevronUp className="h-4 w-4" /></button>
          <button aria-label={`Move selector ${i + 1} down`} disabled={i === rows.length - 1} onClick={() => move(i, 1)}><ChevronDown className="h-4 w-4" /></button>
          <button aria-label={`Remove selector ${i + 1}`} onClick={() => setRows(r => r.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></button>
        </div>
        {row.by === 'role' ? <div className="mt-2 grid grid-cols-2 gap-2">
          <MappedField aria-label={`Role ${i + 1}`} mode="template" context={mapping} value={row.role ?? ''} onChange={role => update(i, { role })} />
          <MappedField aria-label={`Role name ${i + 1}`} mode="template" context={mapping} value={row.name ?? ''} onChange={name => update(i, { name })} />
        </div> : <div className="mt-2"><MappedField aria-label={`Selector value ${i + 1}`} mode="template" context={mapping} mono
          value={row.value ?? ''} onChange={v => update(i, { value: v })} /></div>}
        {['role', 'label', 'text', 'near'].includes(row.by) && <label className="mt-2 flex items-center gap-2 text-xs">
          <input type="checkbox" checked={row.exact === true} onChange={e => update(i, { exact: e.target.checked || undefined })} /> Exact match
        </label>}
      </div>)}
      <button type="button" className="inline-flex items-center gap-1 self-start text-xs text-brand-600" onClick={() => setRows(r => [...r, { by: 'css', value: '' }])}><Plus className="h-3.5 w-3.5" /> Add selector</button>

      <div className="flex flex-col gap-2 border-t border-gray-100 pt-3 dark:border-gray-800">
        <h4 className="text-xs font-semibold text-gray-700 dark:text-gray-300">Inside frames</h4>
        <p className="text-[11px] text-gray-500">When the element is inside an iframe, list the iframes from the page down, as CSS selectors.</p>
        {frames.map((level, i) => <div key={i} className="flex gap-1">
          <MappedField aria-label={`Frame ${i + 1}`} mode="template" context={mapping} mono value={level.strategies[0]?.value ?? ''} onChange={v => setFrame(i, v)} />
          <button aria-label={`Remove frame ${i + 1}`} onClick={() => setFrames(f => f.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></button>
        </div>)}
        <button type="button" className="inline-flex items-center gap-1 self-start text-xs text-brand-600" onClick={() => setFrames(f => [...f, { strategies: [{ by: 'css', value: '' }] }])}><Plus className="h-3.5 w-3.5" /> Add frame</button>

        <h4 className="text-xs font-semibold text-gray-700 dark:text-gray-300">Inside shadow roots</h4>
        <p className="text-[11px] text-gray-500">For web components: the host elements, outermost first, as CSS selectors.</p>
        {shadow.map((host, i) => <div key={i} className="flex gap-1">
          <MappedField aria-label={`Shadow host ${i + 1}`} mode="template" context={mapping} mono value={host.value ?? ''} onChange={v => setShadow(h => h.map((x, j) => j === i ? { ...x, by: x.by || 'css', value: v } : x))} />
          <button aria-label={`Remove shadow host ${i + 1}`} onClick={() => setShadow(h => h.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></button>
        </div>)}
        <button type="button" className="inline-flex items-center gap-1 self-start text-xs text-brand-600" onClick={() => setShadow(h => [...h, { by: 'css', value: '' }])}><Plus className="h-3.5 w-3.5" /> Add shadow host</button>

      </div>

      <div className="flex justify-end gap-2"><Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={save}>Save selectors</Button></div>
    </div>
  </div>
}

function WebTestResultView({ result, deviceId }: { result: WebTestResult; deviceId?: string }) {
  const candidates = result.error?.candidates
  const count = Array.isArray(candidates) ? candidates.length
    : candidates && typeof candidates === 'object' ? Object.keys(candidates as object).length : 0
  return <div className="rounded-lg bg-gray-50 p-2 text-xs dark:bg-gray-950">
    <p className="font-medium">{result.status}</p>
    {result.error && <p className="text-red-600">{result.error.kind ? `${result.error.kind}: ` : ''}{result.error.detail ?? result.message}{count ? ` (${count} candidates)` : ''}</p>}
    {!result.error && result.message && <p>{result.message}</p>}
    {result.output != null && <pre className="mt-1 max-h-40 overflow-auto text-[11px]">{JSON.stringify(result.output, null, 2)}</pre>}
    {deviceId && result.files?.map(file => <img key={file} alt="Test screenshot" className="mt-2 max-h-48 rounded border" src={devicesApi.fileUrl(deviceId, file)} />)}
  </div>
}

/** The tools the devices' apps added themselves, each once, with the first device offering it. */
function ownTools(devices: Device[]): { device: Device; spec: DeviceActionSpec }[] {
  const seen = new Set<string>()
  const out: { device: Device; spec: DeviceActionSpec }[] = []
  for (const d of devices) for (const t of d.tools ?? []) {
    if (seen.has(t.action)) continue
    seen.add(t.action)
    out.push({ device: d, spec: t })
  }
  return out
}

/** An input marked x-items: a list of the device's saved items, or a mapped value instead. */
function DeviceItemField({ label, required, hint, value, options, mapping, onChange }: {
  label: string; required: boolean; hint: string; value: string; options: (DeviceItem & { device?: string })[]
  mapping: MappingContext; onChange: (v: string) => void
}) {
  const [other, setOther] = useState(() => value !== '' && !options.some(o => o.id === value))
  return (
    <Labelled group label={label} required={required} hint={other ? TEMPLATE_HINT : hint}>
      <select aria-label={label} className={fieldClass} value={other ? '__other' : value}
        onChange={e => {
          if (e.target.value === '__other') { setOther(true); return }
          setOther(false)
          onChange(e.target.value)
        }}>
        <option value="">Choose…</option>
        {options.map(o => <option key={`${o.device ?? ''}:${o.id}`} value={o.id}>{o.name}{o.device ? ` (${o.device})` : ''}</option>)}
        <option value="__other">Something else, or data from an earlier step…</option>
      </select>
      {other && <div className="mt-1"><MappedField aria-label={`${label} value`} mode="template" context={mapping} value={value} onChange={onChange} /></div>}
    </Labelled>
  )
}

/** Which device, which action from the catalog or the device's own tools, and one mapped field per input it names. */
function DeviceActionFields({ node, catalog, setConfig, isMissing, mapping }: {
  node: CanvasNode; catalog?: DeviceActionSpec[]; setConfig: (patch: Record<string, unknown>) => void; isMissing: (f: string) => boolean; mapping: MappingContext
}) {
  const { data: devices } = useQuery({ queryKey: ['devices'], queryFn: devicesApi.list }, queryClient)
  const action = typeof node.config.action === 'string' ? node.config.action : ''
  const target = typeof node.config.target === 'string' ? node.config.target : 'any'
  const device = devices?.find((d: Device) => d.id === target)
  // The devices this step could go to: their own tools and saved items are offered too.
  const reachable = device ? [device] : (devices ?? []).filter(d => target !== 'phone' || d.platform === 'IOS' || d.platform === 'ANDROID')
  const own = ownTools(reachable)
  const spec = catalog?.find(a => a.action === action) ?? own.find(t => t.spec.action === action)?.spec
  const extra = action.startsWith('x-')
  const itemsOf = (kind: string) => reachable.flatMap(d => (d.items?.[kind] ?? []).map(i => ({ ...i, device: device ? undefined : d.name })))
  const inputs = (node.config.inputs && typeof node.config.inputs === 'object' && !Array.isArray(node.config.inputs)
    ? node.config.inputs : {}) as Record<string, unknown>
  const schema = (spec?.input ?? {}) as { properties?: Record<string, { description?: string; type?: string; enum?: unknown[]; 'x-items'?: string }>; required?: string[] }
  const properties = Object.entries(schema.properties ?? {})
  const required = new Set(schema.required ?? [])
  const offered = (d: Device) => d.actions.some(a => a === action || a.startsWith(`${action}@`)) || action === 'system.ping'
  const groups = [...new Set((catalog ?? []).map(a => a.capability))]
  return <>
    <Labelled label="Device" hint={devices && devices.length === 0 ? 'No devices yet. Pair one under Settings › Devices.'
      : device && action && !offered(device) ? `${device.name} has not offered this action. Update its Jizza Agent app or choose another device.` : 'Any device picks one of yours that can do it, online first.'}>
      <select className={fieldClass} value={target}
        onChange={e => setConfig({ target: e.target.value, deviceName: devices?.find(d => d.id === e.target.value)?.name ?? (e.target.value === 'phone' ? 'your phone' : undefined) })}>
        <option value="any">Any of my devices</option>
        <option value="phone">My phone</option>
        {target !== 'any' && target !== 'phone' && !device && <option value={target}>{String(node.config.deviceName ?? 'Current device')}</option>}
        {devices?.map(d => <option key={d.id} value={d.id}>{d.name}{d.online ? '' : ' (offline)'}</option>)}
      </select>
    </Labelled>
    <Labelled label="Action" required missing={isMissing('action')}>
      <select className={fieldClass} value={action}
        onChange={e => {
          const a = catalog?.find(x => x.action === e.target.value) ?? own.find(t => t.spec.action === e.target.value)?.spec
          setConfig({ action: e.target.value || undefined, actionTitle: a?.title, inputs: undefined })
        }}>
        <option value="">Choose an action…</option>
        {action && !spec && <option value={action}>{String(node.config.actionTitle ?? action)}</option>}
        {groups.map(g => (
          <optgroup key={g} label={g}>
            {catalog?.filter(a => a.capability === g).map(a => <option key={a.action} value={a.action}>{a.title}</option>)}
          </optgroup>
        ))}
        {[...new Set(own.map(t => t.device))].map(d => (
          <optgroup key={d.id} label={`Added by ${d.name}`}>
            {own.filter(t => t.device === d).map(t => <option key={t.spec.action} value={t.spec.action}>{t.spec.title}</option>)}
          </optgroup>
        ))}
      </select>
    </Labelled>
    {spec?.description && <p className="text-[11px] text-gray-500">{spec.description}</p>}
    {spec && extra && spec.actionType === 'MONEY' && <p className="text-[11px] text-amber-700 dark:text-amber-300">The device's app added this tool itself. Until you say what it does under Settings › Devices, it counts as moving money, so your guardrails won't let it run on its own.</p>}
    {spec && !(extra && spec.actionType === 'MONEY') && spec.actionType !== 'READ' && <p className="text-[11px] text-amber-700 dark:text-amber-300">This action changes things on the device, so your guardrails may ask you before it runs.</p>}
    {spec && properties.map(([key, p]) => p['x-items'] && itemsOf(p['x-items']).length > 0 ? (
      <DeviceItemField key={`${node.id}:${key}`} label={key} required={required.has(key)} hint={p.description ?? `Saved on the device as ${p['x-items']}.`}
        value={typeof inputs[key] === 'string' ? inputs[key] as string : ''} options={itemsOf(p['x-items'])} mapping={mapping}
        onChange={v => {
          const next = { ...inputs }
          if (v === '') delete next[key]
          else next[key] = v
          setConfig({ inputs: next })
        }} />
    ) : (
      <Labelled group key={key} label={key} required={required.has(key)} hint={[p.description, p.enum ? `One of: ${p.enum.join(', ')}` : ''].filter(Boolean).join(' ') || TEMPLATE_HINT}>
        <MappedField aria-label={key} mode="template" context={mapping} mono={p.type !== 'string'}
          value={inputs[key] === undefined || inputs[key] === null ? '' : typeof inputs[key] === 'string' ? inputs[key] as string : JSON.stringify(inputs[key])}
          onChange={v => {
            const next = { ...inputs }
            if (v === '') delete next[key]
            else next[key] = p.type === 'number' || p.type === 'integer' ? (v.trim() !== '' && !Number.isNaN(Number(v)) ? Number(v) : v)
              : p.type === 'boolean' ? (v === 'true' ? true : v === 'false' ? false : v) : v
            setConfig({ inputs: next })
          }} />
      </Labelled>
    ))}
    {spec && (
      <MoreOptions summary={typeof node.config.timeoutSec === 'number' ? `time limit ${node.config.timeoutSec} s` : `time limit ${spec.defaultTimeoutSec} s`}>
      <Labelled label="Time limit (seconds)" hint={`How long the device has to finish, including waiting for it to come online. Default ${spec.defaultTimeoutSec} s.`}>
        <input className={fieldClass} type="number" min={1} value={typeof node.config.timeoutSec === 'number' ? node.config.timeoutSec : ''}
          onChange={e => setConfig({ timeoutSec: e.target.value === '' ? undefined : Number(e.target.value) })} />
      </Labelled>
      </MoreOptions>
    )}
  </>
}

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

export function Labelled({ label, hint, required, missing, group, children }: {
  label: string; hint?: string
  /** Wraps a mapped field: not a <label>, whose clicks would go to the field's insert button. */
  group?: boolean
  /** A mandatory field: marked with *, and Save waits for it. */
  required?: boolean
  /** Whether a mandatory field is still empty or unusable. */
  missing?: boolean
  children: ReactNode
}) {
  const Wrapper = group ? 'div' : 'label'
  return (
    <Wrapper className="flex min-w-0 flex-col gap-1">
      <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
        {label}{required && <span className="ml-0.5 text-red-500" aria-hidden>*</span>}
        {required && <span className="sr-only"> (required)</span>}
      </span>
      {children}
      {hint && <span className="text-[11px] text-gray-400">{hint}</span>}
      {missing && <span className="text-[11px] text-red-500">Required</span>}
    </Wrapper>
  )
}

