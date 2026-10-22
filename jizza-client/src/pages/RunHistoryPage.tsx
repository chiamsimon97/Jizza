import { useCallback, useMemo } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  AlertTriangle, Bot, CalendarClock, ChevronRight, GitFork, Hand, History, RefreshCw, Webhook, Zap,
  type LucideIcon,
} from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { SlideOver } from '@/components/ui/SlideOver'
import { MonitorTabs } from '@/components/history/MonitorTabs'
import { TimeRangePicker } from '@/components/history/TimeRangePicker'
import { Timeline } from '@/components/history/Timeline'
import { SearchBox } from '@/components/history/SearchBox'
import { FilterChip, toggled } from '@/components/history/FilterChip'
import { RunTraceView } from '@/components/history/RunTraceView'
import { RUN_STATUS, TRIGGER_LABEL } from '@/components/history/palette'
import { runsApi } from '@/api/runs'
import { dayKey, dayLabel, formatDuration, rangeParams, readRange, writeRange, type TimeRange } from '@/lib/timeRange'
import type { RunStatus, RunSummary, TriggerKind } from '@/types'

const PAGE = 50
const DEFAULT_RANGE: TimeRange = { preset: 'last_7d' }
const STATUSES: RunStatus[] = ['DONE', 'FAILED', 'RUNNING', 'WAITING', 'PAUSED_WAITING_INSTRUCTION', 'CANCELLED']
const TRIGGERS: TriggerKind[] = ['manual', 'webhook', 'schedule', 'agent', 'subworkflow', 'event', 'other']
const TRIGGER_ICON: Record<TriggerKind, LucideIcon> = {
  manual: Hand, webhook: Webhook, schedule: CalendarClock, agent: Bot, subworkflow: GitFork, event: Zap, other: Zap,
}

export function RunHistoryPage() {
  const [params, setParams] = useSearchParams()
  const range = readRange(params, DEFAULT_RANGE.preset!)
  const statuses = params.getAll('status') as RunStatus[]
  const triggers = params.getAll('trigger') as TriggerKind[]
  const workflowId = params.get('wf') ?? undefined
  const q = params.get('q') ?? ''
  const openRun = params.get('run')

  const update = useCallback((change: (p: URLSearchParams) => void, push = false) => {
    setParams(prev => { const next = new URLSearchParams(prev); change(next); return next }, { replace: !push })
  }, [setParams])
  const setList = (key: string, values: string[]) => update(p => { p.delete(key); values.forEach(v => p.append(key, v)) })

  const query = useInfiniteQuery({
    queryKey: ['runs', 'history', range, statuses, triggers, workflowId, q],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => runsApi.search({
      ...rangeParams(range), statuses, triggers, workflowId, q, limit: PAGE, offset: pageParam,
    }),
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((n, p) => n + p.items.length, 0)
      return loaded < last.total ? loaded : undefined
    },
    // Runs still going change under you; check again while any are on screen.
    refetchInterval: q => (q.state.data?.pages.some(p => p.items.some(r => r.status === 'RUNNING')) ? 4000 : false),
  })

  const first = query.data?.pages[0]
  const runs = useMemo(() => query.data?.pages.flatMap(p => p.items) ?? [], [query.data])
  const byDay = useMemo(() => {
    const map = new Map<string, RunSummary[]>()
    runs.forEach(r => { const k = dayKey(r.startedAt); map.set(k, [...(map.get(k) ?? []), r]) })
    return [...map]
  }, [runs])
  const longest = Math.max(1, ...runs.map(r => r.durationMs))
  const filtered = statuses.length + triggers.length > 0 || Boolean(workflowId) || q !== ''
  const stats = first?.stats
  const openSummary = runs.find(r => r.id === openRun)

  return (
    <div className="page max-w-7xl">
      <MonitorTabs />
      <PageHeader
        icon={History}
        title="Run history"
        description="Every time a workflow ran: what started it, each step's input and output, and where it broke."
        actions={<Button variant="secondary" size="sm" onClick={() => void query.refetch()} loading={query.isRefetching && !query.isFetchingNextPage}><RefreshCw className="h-4 w-4" />Refresh</Button>}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <TimeRangePicker value={range} defaultRange={DEFAULT_RANGE} onChange={r => update(p => writeRange(p, r))} />
        <select
          value={workflowId ?? ''}
          onChange={e => update(p => { if (e.target.value) p.set('wf', e.target.value); else p.delete('wf') })}
          aria-label="Workflow"
          className="rounded-lg border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 shadow-sm dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200"
        >
          <option value="">All workflows</option>
          {(first?.workflows ?? []).map(w => (
            <option key={w.id} value={w.id}>{w.name} ({w.runs}{w.failed ? `, ${w.failed} failed` : ''})</option>
          ))}
          {workflowId && !first?.workflows.some(w => w.id === workflowId) && <option value={workflowId}>This workflow</option>}
        </select>
        <SearchBox value={q} onChange={v => update(p => { if (v) p.set('q', v); else p.delete('q') })} placeholder="Search by workflow, error or run id" />
      </div>

      <div className="mb-4 flex flex-wrap gap-1.5">
        {STATUSES.map(s => (
          <FilterChip key={s} active={statuses.includes(s)} color={RUN_STATUS[s].color} count={stats?.byStatus[s] ?? 0}
            onClick={() => setList('status', toggled(statuses, s))}>
            {RUN_STATUS[s].label}
          </FilterChip>
        ))}
        <span className="mx-1 w-px self-stretch bg-gray-200 dark:bg-gray-800" />
        {TRIGGERS.filter(t => (first?.triggers[t] ?? 0) > 0 || triggers.includes(t)).map(t => {
          const Icon = TRIGGER_ICON[t]
          return (
            <FilterChip key={t} active={triggers.includes(t)} count={first?.triggers[t] ?? 0} onClick={() => setList('trigger', toggled(triggers, t))}>
              <Icon className="h-3 w-3" />{TRIGGER_LABEL[t]}
            </FilterChip>
          )
        })}
        {filtered && (
          <button onClick={() => update(p => ['status', 'trigger', 'wf', 'q'].forEach(k => p.delete(k)))} className="ml-1 text-xs text-brand-600 hover:underline dark:text-brand-300">Clear filters</button>
        )}
      </div>

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-[repeat(4,minmax(0,1fr))_minmax(0,2.2fr)]">
        <Kpi label="Runs" value={stats ? stats.total.toLocaleString() : '…'} />
        <Kpi label="Success rate" value={stats ? (stats.byStatus.DONE || stats.byStatus.FAILED ? `${Math.round(stats.successRate * 100)}%` : '—') : '…'}
          ring={stats ? stats.successRate : undefined} />
        <Kpi label="Typical run" value={stats ? formatDuration(stats.avgDurationMs) : '…'} sub="average" />
        <Kpi label="Slowest 5%" value={stats ? formatDuration(stats.p95DurationMs) : '…'} sub="take longer than" />
        <Card padding="sm" className="col-span-2 lg:col-span-1">
          {first ? (
            <Timeline
              height={64}
              noun="runs"
              unit={first.bucketUnit}
              series={[
                { key: 'done', label: 'Succeeded', color: RUN_STATUS.DONE.color },
                { key: 'failed', label: 'Failed', color: RUN_STATUS.FAILED.color },
                { key: 'other', label: 'Running or stopped', color: '#a5b4fc' },
              ]}
              buckets={first.buckets.map(b => ({ start: b.start, values: { done: b.done, failed: b.failed, other: b.other } }))}
              onZoom={(from, to) => update(p => writeRange(p, { from, to }))}
            />
          ) : <div className="h-24" />}
        </Card>
      </div>

      {first?.truncated && (
        <p className="mb-3 flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
          <AlertTriangle className="h-4 w-4" />This range has more than 5,000 runs; the numbers cover the newest 5,000. Narrow the range for exact figures.
        </p>
      )}

      {query.isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {query.isError && <EmptyState icon={AlertTriangle} title="Runs could not be loaded" description={(query.error as Error).message} />}
      {!query.isLoading && !query.isError && runs.length === 0 && (
        <EmptyState icon={History} title="No runs" description={filtered ? 'No runs match these filters in this range.' : 'When a workflow runs, it shows up here with every step it took.'} />
      )}

      <div className="space-y-5">
        {byDay.map(([day, list]) => {
          const failed = list.filter(r => r.status === 'FAILED').length
          return (
            <section key={day}>
              <header className="sticky top-0 z-10 mb-2 flex items-center gap-2 bg-gray-50/90 py-1.5 backdrop-blur dark:bg-gray-950/90">
                <h2 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{dayLabel(day)}</h2>
                <span className="text-xs text-gray-400">{list.length} run{list.length === 1 ? '' : 's'}{failed ? ` · ${failed} failed` : ''}</span>
                <DayStrip runs={list} />
              </header>
              <Card padding="none" className="overflow-hidden">
                <ul className="divide-y divide-gray-100 dark:divide-gray-800/70">
                  {list.map(r => (
                    <RunRow key={r.id} run={r} longest={longest} selected={r.id === openRun}
                      onOpen={() => update(p => p.set('run', r.id), true)} />
                  ))}
                </ul>
              </Card>
            </section>
          )
        })}
      </div>

      {query.hasNextPage && (
        <div className="mt-4 flex justify-center">
          <Button variant="secondary" size="sm" loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            Show more ({(first!.total - runs.length).toLocaleString()} left)
          </Button>
        </div>
      )}

      <SlideOver
        open={Boolean(openRun)}
        wide
        title={openSummary?.workflowName ?? 'Run'}
        description="Click a step on the map or in the list to see what it took in and gave back."
        onClose={() => update(p => p.delete('run'))}
      >
        {openRun && <RunTraceView runId={openRun} onOpenRun={id => update(p => p.set('run', id), true)} />}
      </SlideOver>
    </div>
  )
}

function Kpi({ label, value, sub, ring }: { label: string; value: string; sub?: string; ring?: number }) {
  return (
    <Card padding="md" className="flex items-center gap-3 max-sm:p-3">
      {ring != null && (
        <svg viewBox="0 0 36 36" className="h-10 w-10 shrink-0 -rotate-90" aria-hidden>
          <circle cx="18" cy="18" r="15" fill="none" strokeWidth="4" className="stroke-gray-100 dark:stroke-gray-800" />
          <circle cx="18" cy="18" r="15" fill="none" strokeWidth="4" strokeLinecap="round" stroke={ring >= 0.9 ? '#10b981' : ring >= 0.6 ? '#f59e0b' : '#ef4444'}
            strokeDasharray={`${ring * 94.2} 94.2`} className="transition-all duration-700" />
        </svg>
      )}
      <div className="min-w-0">
        <p className="text-xs text-gray-500 dark:text-gray-400">{label}</p>
        {sub && <p className="text-[10px] text-gray-400">{sub}</p>}
        <p className="text-xl font-bold tabular-nums text-gray-900 dark:text-gray-100">{value}</p>
      </div>
    </Card>
  )
}

/** One coloured tick per run, oldest to newest, so a bad patch in the day shows at a glance. */
function DayStrip({ runs }: { runs: RunSummary[] }) {
  return (
    <span className="ml-auto flex max-w-[50%] flex-row-reverse flex-wrap items-center gap-0.5" aria-hidden>
      {runs.slice(0, 80).map(r => <span key={r.id} className="h-3 w-1 rounded-full" style={{ backgroundColor: RUN_STATUS[r.status].color }} />)}
    </span>
  )
}

function RunRow({ run, longest, selected, onOpen }: { run: RunSummary; longest: number; selected: boolean; onOpen: () => void }) {
  const st = RUN_STATUS[run.status]
  const Icon = TRIGGER_ICON[run.triggerKind]
  const share = Math.max(0.02, run.durationMs / longest)
  return (
    <li>
      <button onClick={onOpen} className={`group flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors ${selected ? 'bg-brand-50/70 dark:bg-brand-900/20' : 'hover:bg-gray-50 dark:hover:bg-gray-800/40'}`}>
        <span className="relative flex h-2.5 w-2.5 shrink-0">
          {run.status === 'RUNNING' && <span className="absolute inset-0 animate-ping rounded-full opacity-60" style={{ backgroundColor: st.color }} />}
          <span className="relative h-2.5 w-2.5 rounded-full" style={{ backgroundColor: st.color }} />
        </span>
        <time className="w-20 shrink-0 text-xs tabular-nums text-gray-500" dateTime={run.startedAt} title={new Date(run.startedAt).toLocaleString()}>
          {new Date(run.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' })}
        </time>
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{run.workflowName}</span>
            <span className="hidden items-center gap-1 rounded-full bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-500 sm:inline-flex dark:bg-gray-800 dark:text-gray-400">
              <Icon className="h-3 w-3" />{TRIGGER_LABEL[run.triggerKind]}
            </span>
          </span>
          {run.error
            ? <span className="mt-0.5 block truncate text-xs text-red-600 dark:text-red-400">{run.error}</span>
            : <span className="mt-0.5 block text-xs text-gray-400">{run.stepCount} step{run.stepCount === 1 ? '' : 's'}{run.failedSteps ? ` · ${run.failedSteps} with errors` : ''}</span>}
        </span>
        <span className={`hidden w-24 text-xs font-medium sm:block ${st.text}`}>{st.label}</span>
        <span className="hidden w-32 items-center gap-2 md:flex" title={`${formatDuration(run.durationMs)}${run.endedAt ? '' : ' so far'}`}>
          <span className="h-1.5 flex-1 rounded-full bg-gray-100 dark:bg-gray-800">
            <span className="block h-1.5 rounded-full" style={{ width: `${share * 100}%`, backgroundColor: st.color, opacity: 0.75 }} />
          </span>
        </span>
        <span className="w-16 shrink-0 text-right text-xs tabular-nums text-gray-600 dark:text-gray-300">{formatDuration(run.durationMs)}</span>
        <ChevronRight className="h-4 w-4 shrink-0 text-gray-300 transition-transform group-hover:translate-x-0.5" />
      </button>
    </li>
  )
}
