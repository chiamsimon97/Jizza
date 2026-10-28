import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  AlertTriangle, ArrowUpRight, CheckCircle2, CircleDashed, Clock, Copy, CornerDownRight, GitFork,
  Hourglass, Pencil, RotateCw, Wrench, XCircle,
} from 'lucide-react'
import { Spinner } from '@/components/ui/Spinner'
import { JsonView } from './JsonView'
import { RUN_STATUS, TRIGGER_LABEL } from './palette'
import { layoutMap, waterfall, type StepState } from './traceModel'
import { NODE_META, type NodeType } from '@/components/workflow/canvasModel'
import { runsApi } from '@/api/runs'
import { webApi } from '@/api/web'
import { devicesApi } from '@/api/devices'
import { errorKindWords, reviewFixLink } from '@/components/web/fixModel'
import { formatDuration } from '@/lib/timeRange'
import type { RunSummary, RunTrace, TraceStep } from '@/types'

// Fill and text come as classes so the map follows the light and dark themes.
const STATE_STYLE: Record<StepState, { fill: string; stroke: string; fillCls: string; textCls: string; label: string }> = {
  done: { fill: '#ecfdf5', stroke: '#10b981', fillCls: 'fill-emerald-50 dark:fill-emerald-950', textCls: 'fill-emerald-800 dark:fill-emerald-200', label: 'Done' },
  failed: { fill: '#fef2f2', stroke: '#ef4444', fillCls: 'fill-red-50 dark:fill-red-950', textCls: 'fill-red-800 dark:fill-red-200', label: 'Failed' },
  skipped: { fill: '#f8fafc', stroke: '#cbd5e1', fillCls: 'fill-slate-50 dark:fill-slate-800', textCls: 'fill-slate-500 dark:fill-slate-400', label: 'Skipped' },
  waiting: { fill: '#fffbeb', stroke: '#f59e0b', fillCls: 'fill-amber-50 dark:fill-amber-950', textCls: 'fill-amber-800 dark:fill-amber-200', label: 'Waiting here' },
  unreached: { fill: 'transparent', stroke: '#cbd5e1', fillCls: 'fill-white/60 dark:fill-gray-900/60', textCls: 'fill-slate-400 dark:fill-slate-500', label: 'Not reached' },
}

const meta = (type?: string) => NODE_META[(type ?? '') as NodeType]

/** Every run as a story: where it went on the workflow, how long each step took, and what each one saw. */
export function RunTraceView({ runId, onOpenRun }: { runId: string; onOpenRun: (id: string) => void }) {
  const { data: trace, isLoading, error } = useQuery({
    queryKey: ['runs', 'trace', runId],
    queryFn: () => runsApi.trace(runId),
    refetchInterval: q => {
      const s = q.state.data?.run.status
      return s === 'RUNNING' ? 2000 : false
    },
  })
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    if (!trace) return
    // Open on the step that matters most: where it failed or waits, else the last one.
    const pick = trace.waitingOn
      ?? trace.steps.find(s => s.status === 'FAILED')?.nodeId
      ?? trace.steps[trace.steps.length - 1]?.nodeId
      ?? null
    setSelected(prev => (prev && trace.nodes.some(n => n.id === prev) ? prev : pick))
  }, [trace])

  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (error || !trace) return <p className="text-sm text-red-600">{(error as Error)?.message ?? 'This run could not be loaded.'}</p>

  const step = trace.steps.find(s => s.nodeId === selected)
  const node = trace.nodes.find(n => n.id === selected)

  return (
    <div className="space-y-5">
      <RunHeader trace={trace} onOpenRun={onOpenRun} />
      <RunMap trace={trace} selected={selected} onSelect={setSelected} />
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <Waterfall trace={trace} selected={selected} onSelect={setSelected} />
        <StepPanel trace={trace} step={step} nodeId={selected} nodeLabel={node?.label} nodeType={node?.type} />
      </div>
      {trace.children.length > 0 && (
        <section>
          <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-gray-900 dark:text-gray-100"><GitFork className="h-4 w-4" />Runs this one started</h3>
          <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
            {trace.children.map(c => <ChildRow key={c.id} run={c} onOpen={() => onOpenRun(c.id)} />)}
          </ul>
        </section>
      )}
      <section className="grid gap-4 md:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900 dark:text-gray-100">What the run was started with</h3>
          <JsonView value={trace.input} empty="The run started with no data." />
        </div>
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900 dark:text-gray-100">What it produced</h3>
          <JsonView value={trace.result} empty={trace.run.status === 'DONE' ? 'It finished without a result.' : 'Only a finished run has a result.'} />
        </div>
      </section>
    </div>
  )
}

function RunHeader({ trace, onOpenRun }: { trace: RunTrace; onOpenRun: (id: string) => void }) {
  const r = trace.run
  const status = RUN_STATUS[r.status]
  const done = trace.steps.filter(s => s.status === 'DONE').length
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <span className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold text-white" style={{ backgroundColor: status.color }}>
          {r.status === 'RUNNING' && <RotateCw className="h-3 w-3 animate-spin" />}{status.label}
        </span>
        <span className="text-sm text-gray-500">{new Date(r.startedAt).toLocaleString()}</span>
        <span className="text-sm text-gray-400">·</span>
        <span className="text-sm text-gray-500">{TRIGGER_LABEL[r.triggerKind]}</span>
        <span className="text-sm text-gray-400">·</span>
        <span className="text-sm text-gray-500">version {r.version}</span>
        <Link to={`/workflows?open=${r.workflowId}`} className="ml-auto inline-flex items-center gap-1 text-sm text-brand-600 hover:underline dark:text-brand-300">
          <Pencil className="h-3.5 w-3.5" />Open workflow
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label="Took" value={r.endedAt ? formatDuration(r.durationMs) : `${formatDuration(r.durationMs)} so far`} />
        <Stat label="Steps done" value={`${done} of ${trace.nodes.length}`} />
        <Stat label="Failed attempts" value={String(trace.steps.reduce((n, s) => n + (s.status === 'FAILED' ? s.attempts : Math.max(0, s.attempts - 1)), 0))} />
        <Stat label="Run id" value={<CopyId id={r.id} />} />
      </div>
      {r.error && (
        <div className="flex gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900/50 dark:bg-red-900/20 dark:text-red-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span><strong>{r.failedNode ? `Stopped at ${trace.nodes.find(n => n.id === r.failedNode)?.label ?? r.failedNode}: ` : ''}</strong>{r.error}</span>
        </div>
      )}
      {r.parentRunId && (
        <button onClick={() => onOpenRun(r.parentRunId!)} className="inline-flex items-center gap-1 text-xs text-brand-600 hover:underline dark:text-brand-300">
          <CornerDownRight className="h-3.5 w-3.5 rotate-180" />Started by another run, open it
        </button>
      )}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-gray-200 px-3 py-2 dark:border-gray-800">
      <p className="text-[11px] uppercase tracking-wide text-gray-400">{label}</p>
      <div className="text-sm font-semibold text-gray-900 dark:text-gray-100">{value}</div>
    </div>
  )
}

function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button onClick={() => { void navigator.clipboard?.writeText(id); setCopied(true); setTimeout(() => setCopied(false), 1200) }} className="inline-flex items-center gap-1 font-mono text-xs font-normal text-gray-600 hover:text-gray-900 dark:text-gray-300" title={id}>
      {copied ? 'Copied' : `${id.slice(0, 8)}…`}<Copy className="h-3 w-3" />
    </button>
  )
}

const NODE_W = 150
const NODE_H = 44
const GAP_X = 46
const GAP_Y = 16

/** The workflow drawn as the run walked it: green where it went, red where it broke, grey where it never got. */
function RunMap({ trace, selected, onSelect }: { trace: RunTrace; selected: string | null; onSelect: (id: string) => void }) {
  const layout = useMemo(() => layoutMap(trace), [trace])
  if (layout.nodes.length === 0) {
    return <p className="rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-400 dark:bg-gray-950/60">The workflow this run used can no longer be drawn; its steps are listed below.</p>
  }
  const pos = new Map(layout.nodes.map(n => [n.id, { x: 8 + n.col * (NODE_W + GAP_X), y: 8 + n.row * (NODE_H + GAP_Y) }]))
  const width = 16 + layout.cols * NODE_W + (layout.cols - 1) * GAP_X
  const height = 16 + layout.rows * NODE_H + (layout.rows - 1) * GAP_Y
  const reached = new Set(trace.steps.map(s => s.nodeId))

  return (
    <section>
      <div className="mb-2 flex flex-wrap items-center gap-3 text-xs text-gray-500">
        <h3 className="mr-auto text-sm font-semibold text-gray-900 dark:text-gray-100">Path taken</h3>
        {(['done', 'failed', 'waiting', 'skipped', 'unreached'] as StepState[]).filter(s => layout.nodes.some(n => n.state === s)).map(s => (
          <span key={s} className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-sm border" style={{ backgroundColor: STATE_STYLE[s].fill, borderColor: STATE_STYLE[s].stroke, borderStyle: s === 'unreached' || s === 'skipped' ? 'dashed' : 'solid' }} />
            {STATE_STYLE[s].label}
          </span>
        ))}
      </div>
      <div className="overflow-x-auto rounded-xl border border-gray-200 bg-[radial-gradient(circle,rgb(var(--gray-200))_1px,transparent_1px)] [background-size:16px_16px] dark:border-gray-800 dark:bg-[radial-gradient(circle,rgb(var(--gray-800))_1px,transparent_1px)]">
        <svg width={width} height={height} className="block" role="group" aria-label="Workflow map">
          {trace.edges.map((e, i) => {
            const a = pos.get(e.from)
            const b = pos.get(e.to)
            if (!a || !b) return null
            const x1 = a.x + NODE_W
            const y1 = a.y + NODE_H / 2
            const x2 = b.x
            const y2 = b.y + NODE_H / 2
            const walked = reached.has(e.from) && reached.has(e.to)
            const mid = (x1 + x2) / 2
            return (
              <g key={i}>
                <path d={`M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2},${y2}`} fill="none"
                  stroke={walked ? '#10b981' : '#cbd5e1'} strokeWidth={walked ? 2 : 1.25} strokeDasharray={walked ? undefined : '4 4'}
                  className={walked ? 'opacity-80' : 'opacity-70'} />
                {e.branch && (
                  <text x={mid} y={(y1 + y2) / 2 - 4} textAnchor="middle" className="fill-gray-400 text-[9px]">{e.branch}</text>
                )}
              </g>
            )
          })}
          {layout.nodes.map(n => {
            const p = pos.get(n.id)!
            const st = STATE_STYLE[n.state]
            const isSel = selected === n.id
            return (
              <g key={n.id} transform={`translate(${p.x},${p.y})`} className="cursor-pointer" onClick={() => onSelect(n.id)} role="button" aria-label={`${n.label}: ${st.label}`} tabIndex={0}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onSelect(n.id) }}>
                {n.state === 'waiting' && <rect x={-4} y={-4} width={NODE_W + 8} height={NODE_H + 8} rx={12} fill="none" stroke="#f59e0b" strokeWidth={2} className="animate-pulse" />}
                <rect width={NODE_W} height={NODE_H} rx={9} stroke={isSel ? 'rgb(var(--brand-500))' : st.stroke}
                  strokeWidth={isSel ? 2.5 : 1.5} strokeDasharray={n.state === 'unreached' || n.state === 'skipped' ? '4 3' : undefined}
                  className={st.fillCls} />
                <text x={12} y={18} className={`text-[11px] font-semibold ${st.textCls}`}>{n.label.length > 20 ? `${n.label.slice(0, 19)}…` : n.label}</text>
                <text x={12} y={33} className="text-[9px]" fill="#94a3b8">{meta(n.type)?.label ?? n.type}</text>
                {n.state === 'done' && <circle cx={NODE_W - 12} cy={12} r={4} fill="#10b981" />}
                {n.state === 'failed' && <circle cx={NODE_W - 12} cy={12} r={4} fill="#ef4444" />}
              </g>
            )
          })}
        </svg>
      </div>
    </section>
  )
}

const ICON: Record<TraceStep['status'], typeof CheckCircle2> = { DONE: CheckCircle2, FAILED: XCircle, SKIPPED: CircleDashed }

/** Each step as a bar on the run's own clock, so the slow one stands out. */
function Waterfall({ trace, selected, onSelect }: { trace: RunTrace; selected: string | null; onSelect: (id: string) => void }) {
  const start = new Date(trace.run.startedAt).getTime()
  const end = trace.run.endedAt ? new Date(trace.run.endedAt).getTime() : Date.now()
  const steps = trace.steps.filter(s => s.status !== 'SKIPPED')
  const skipped = trace.steps.filter(s => s.status === 'SKIPPED')
  const unreached = trace.nodes.filter(n => !trace.steps.some(s => s.nodeId === n.id) && n.id !== trace.waitingOn)
  const waiting = trace.waitingOn ? trace.nodes.find(n => n.id === trace.waitingOn) : undefined

  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Steps in order</h3>
        <span className="text-xs text-gray-400">0 → {formatDuration(end - start)}</span>
      </div>
      <ol className="space-y-1">
        {steps.map(s => {
          const bar = waterfall(s, start, end)
          const Icon = ICON[s.status]
          const tone = meta(s.type)?.tone ?? 'bg-gray-500 text-white'
          return (
            <li key={s.id}>
              <button onClick={() => onSelect(s.nodeId)} aria-current={selected === s.nodeId}
                className={`flex w-full items-center gap-2 rounded-lg border px-2 py-1.5 text-left transition-all ${selected === s.nodeId ? 'border-brand-400 bg-brand-50/60 shadow-sm dark:border-brand-600 dark:bg-brand-900/20' : 'border-transparent hover:border-gray-200 hover:bg-gray-50 dark:hover:border-gray-800 dark:hover:bg-gray-800/40'}`}>
                <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-md text-[10px] font-bold ${tone}`}>{(meta(s.type)?.label ?? s.label).slice(0, 1)}</span>
                <span className="w-32 min-w-0 shrink-0">
                  <span className="block truncate text-xs font-medium text-gray-900 dark:text-gray-100">{s.label}</span>
                  <span className="flex items-center gap-1 text-[10px] text-gray-400">
                    <Icon className={`h-3 w-3 ${s.status === 'DONE' ? 'text-emerald-500' : 'text-red-500'}`} />
                    {formatDuration(s.durationMs)}{s.attempts > 1 ? ` · ${s.attempts} tries` : ''}
                  </span>
                </span>
                <span className="relative h-3 flex-1 rounded-full bg-gray-100 dark:bg-gray-800">
                  <span className={`absolute top-0 h-3 rounded-full ${s.status === 'FAILED' ? 'bg-red-400' : 'bg-emerald-400'}`}
                    style={{ left: `${bar.left * 100}%`, width: `max(4px, ${bar.width * 100}%)` }} />
                </span>
              </button>
            </li>
          )
        })}
        {waiting && (
          <li>
            <button onClick={() => onSelect(waiting.id)} className={`flex w-full items-center gap-2 rounded-lg border border-dashed px-2 py-1.5 text-left ${selected === waiting.id ? 'border-amber-400 bg-amber-50 dark:bg-amber-900/20' : 'border-amber-300'}`}>
              <Hourglass className="h-4 w-4 animate-pulse text-amber-500" />
              <span className="text-xs font-medium text-amber-700 dark:text-amber-300">Waiting at {waiting.label}</span>
            </button>
          </li>
        )}
      </ol>
      {(skipped.length > 0 || unreached.length > 0) && (
        <p className="mt-3 text-xs text-gray-400">
          {skipped.length > 0 && <>Skipped: {skipped.map(s => s.label).join(', ')}. </>}
          {unreached.length > 0 && <>Not reached: {unreached.map(n => n.label).join(', ')}.</>}
        </p>
      )}
    </section>
  )
}

type Tab = 'input' | 'output' | 'error'

/** What the picked step was handed and what it gave back. */
function StepPanel({ trace, step, nodeId, nodeLabel, nodeType }: { trace: RunTrace; step?: TraceStep; nodeId: string | null; nodeLabel?: string; nodeType?: string }) {
  const [tab, setTab] = useState<Tab>('output')
  useEffect(() => { setTab(step?.status === 'FAILED' ? 'error' : 'output') }, [step?.id, step?.status])

  if (!nodeId) return <div className="rounded-xl border border-dashed border-gray-300 p-6 text-center text-sm text-gray-400 dark:border-gray-700">Pick a step to see its data.</div>

  const label = step?.label ?? nodeLabel ?? nodeId
  const type = step?.type ?? nodeType
  const input = step?.input as { settings?: unknown; received?: Record<string, unknown> | unknown; truncated?: boolean; preview?: string } | undefined
  const tabs: { key: Tab; label: string; show: boolean }[] = [
    { key: 'input', label: 'Input', show: true },
    { key: 'output', label: 'Output', show: true },
    { key: 'error', label: 'Error', show: Boolean(step?.error) },
  ]
  const labelOf = (id: string) => trace.nodes.find(n => n.id === id)?.label ?? id

  return (
    <section className="rounded-xl border border-gray-200 dark:border-gray-800">
      <header className="flex flex-wrap items-center gap-2 border-b border-gray-200 px-4 py-3 dark:border-gray-800">
        <span className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${meta(type)?.tone ?? 'bg-gray-500 text-white'}`}>{meta(type)?.label ?? type ?? 'Step'}</span>
        <h3 className="min-w-0 flex-1 truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{label}</h3>
        {step && (
          <span className="flex items-center gap-1 text-xs text-gray-400">
            <Clock className="h-3 w-3" />{formatDuration(step.durationMs)}{step.attempts > 1 && ` · ${step.attempts} attempts`}
          </span>
        )}
      </header>
      {!step ? (
        <p className="px-4 py-6 text-sm text-gray-500">
          {trace.waitingOn === nodeId ? 'The run is waiting here. Once it carries on, this step\'s data shows up.' : 'The run never reached this step.'}
        </p>
      ) : (
        <div className="p-4">
          <div className="mb-3 inline-flex rounded-lg bg-gray-100 p-0.5 dark:bg-gray-800" role="tablist">
            {tabs.filter(t => t.show).map(t => (
              <button key={t.key} role="tab" aria-selected={tab === t.key} onClick={() => setTab(t.key)}
                className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${tab === t.key ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-900 dark:text-gray-100' : t.key === 'error' ? 'text-red-600' : 'text-gray-500 hover:text-gray-900 dark:hover:text-gray-100'}`}>
                {t.label}
              </button>
            ))}
          </div>
          {tab === 'input' && (
            input == null ? (
              <p className="text-xs text-gray-400">This run is older than input recording, so only the output is known.</p>
            ) : input.truncated ? (
              <JsonView value={input.preview} />
            ) : (
              <div className="space-y-3">
                <div>
                  <p className="mb-1 text-xs font-medium text-gray-500">Settings, with data filled in</p>
                  <JsonView value={input.settings} empty="This step has no settings." />
                </div>
                <div>
                  <p className="mb-1 text-xs font-medium text-gray-500">{type === 'TRIGGER' ? 'Data the run started with' : 'Data that reached it'}</p>
                  {type !== 'TRIGGER' && input.received && typeof input.received === 'object' ? (
                    <div className="space-y-2">
                      {Object.entries(input.received as Record<string, unknown>).map(([from, value]) => (
                        <div key={from}>
                          <p className="mb-0.5 flex items-center gap-1 text-[11px] text-gray-400"><ArrowUpRight className="h-3 w-3 rotate-90" />from {labelOf(from)}</p>
                          <JsonView value={value} empty="It passed nothing on." />
                        </div>
                      ))}
                    </div>
                  ) : <JsonView value={input.received} empty="Nothing reached it." />}
                </div>
              </div>
            )
          )}
          {tab === 'output' && <JsonView value={step.output} empty={step.status === 'FAILED' ? 'It failed before producing anything.' : 'It produced nothing.'} />}
          {tab === 'error' && (
            <div className="space-y-2">
              {step.failure && <WebFailure trace={trace} step={step} failure={step.failure} />}
              <p className="whitespace-pre-wrap rounded-lg bg-red-50 px-3 py-2 font-mono text-xs text-red-800 dark:bg-red-900/20 dark:text-red-300">{step.error}</p>
            </div>
          )}
          <dl className="mt-3 grid grid-cols-2 gap-1 text-[11px] text-gray-400">
            <dt>Started</dt><dd className="text-gray-600 dark:text-gray-300">{step.startedAt ? new Date(step.startedAt).toLocaleString() : '—'}</dd>
            <dt>Finished</dt><dd className="text-gray-600 dark:text-gray-300">{step.finishedAt ? new Date(step.finishedAt).toLocaleString() : '—'}</dd>
            <dt>Step id</dt><dd className="font-mono text-gray-600 dark:text-gray-300">{step.nodeId}</dd>
          </dl>
        </div>
      )}
    </section>
  )
}

/** A browser step that failed: what went wrong in words, its screenshot, and the fix to review. */
export function WebFailure({ trace, step, failure }: { trace: RunTrace; step: TraceStep; failure: NonNullable<TraceStep['failure']> }) {
  const { data: fixes } = useQuery({
    queryKey: ['web-fixes', 'open'],
    queryFn: () => webApi.fixes('open').catch(() => []),
    enabled: failure.candidates > 0,
    staleTime: 15_000,
  })
  const hasFix = fixes?.some(f => f.workflowId === trace.run.workflowId && f.nodeId === step.nodeId)
  const shot = failure.deviceId && failure.files?.[0] ? devicesApi.fileUrl(failure.deviceId, failure.files[0]) : null
  return (
    <div className="rounded-lg border border-red-200 px-3 py-2 text-xs dark:border-red-900/50">
      <p className="font-medium text-gray-900 dark:text-gray-100">{errorKindWords(failure.kind)}</p>
      {failure.url && <p className="mt-0.5 truncate text-gray-500 dark:text-gray-400">On {failure.url}</p>}
      {shot && (
        <a href={shot} target="_blank" rel="noreferrer" className="mt-2 block">
          <img src={shot} alt="Screenshot of the page when the step failed" className="max-h-56 rounded-md border border-gray-200 dark:border-gray-700" />
        </a>
      )}
      {hasFix && (
        <Link to={reviewFixLink(trace.run.workflowId, step.nodeId)}
          className="mt-2 inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400">
          <Wrench className="h-3.5 w-3.5" />Review fix
        </Link>
      )}
    </div>
  )
}

function ChildRow({ run, onOpen }: { run: RunSummary; onOpen: () => void }) {
  const st = RUN_STATUS[run.status]
  return (
    <li>
      <button onClick={onOpen} className="flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-gray-50 dark:hover:bg-gray-800/40">
        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: st.color }} />
        <span className="flex-1 truncate text-gray-900 dark:text-gray-100">{run.workflowName}</span>
        <span className={`text-xs ${st.text}`}>{st.label}</span>
        <span className="text-xs tabular-nums text-gray-400">{formatDuration(run.durationMs)}</span>
      </button>
    </li>
  )
}
