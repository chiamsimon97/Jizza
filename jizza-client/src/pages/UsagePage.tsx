import { useCallback, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { AlertTriangle, BarChart2, ChevronRight } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { MonitorTabs } from '@/components/history/MonitorTabs'
import { TimeRangePicker } from '@/components/history/TimeRangePicker'
import { Timeline } from '@/components/history/Timeline'
import { FilterChip, toggled } from '@/components/history/FilterChip'
import { usageApi } from '@/api/usage'
import { rangeParams, readRange, relativeTime, writeRange, type TimeRange } from '@/lib/timeRange'
import type { UsageDimension, UsageGroup } from '@/types'

const DEFAULT_RANGE: TimeRange = { preset: 'last_30d' }
const DIMENSIONS: { key: UsageDimension; label: string }[] = [
  { key: 'MODEL', label: 'Model' },
  { key: 'AREA', label: 'Area' },
  { key: 'PURPOSE', label: 'Purpose' },
  { key: 'DAY', label: 'Day' },
]
type Metric = 'costUnits' | 'tokens' | 'calls'
const METRICS: { key: Metric; label: string }[] = [
  { key: 'costUnits', label: 'Cost' },
  { key: 'tokens', label: 'Tokens' },
  { key: 'calls', label: 'Calls' },
]

const cost = (n: number) => Number(n).toFixed(4)
const compact = (n: number) => Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n)

export function UsagePage() {
  const [params, setParams] = useSearchParams()
  const range = readRange(params, DEFAULT_RANGE.preset!)
  const groupBy = (params.get('by') as UsageDimension) || 'MODEL'
  const thenBy = (params.get('then') as UsageDimension) || (groupBy === 'MODEL' ? 'AREA' : undefined)
  const models = params.getAll('model')
  const areas = params.getAll('area')
  const [metric, setMetric] = useState<Metric>('costUnits')

  const update = useCallback((change: (p: URLSearchParams) => void) => {
    setParams(prev => { const next = new URLSearchParams(prev); change(next); return next }, { replace: true })
  }, [setParams])

  const { data: summary } = useQuery({ queryKey: ['usage', 'summary'], queryFn: () => usageApi.summary() })
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['usage', 'explore', range, groupBy, thenBy, models, areas],
    queryFn: () => usageApi.explore({ ...rangeParams(range), groupBy, thenBy, models, areas }),
  })

  const total = data?.totals
  const metricFormat = metric === 'costUnits' ? cost : compact

  return (
    <div className="page max-w-7xl">
      <MonitorTabs />
      <PageHeader
        icon={BarChart2}
        title="Usage & cost"
        description="Tokens and spend over any stretch of time, broken down the way you want."
        actions={summary?.budgetLimit != null ? (
          <Badge variant={summary.withinBudget ? 'success' : 'danger'}>
            {summary.withinBudget ? 'Within budget' : 'Over budget'} · {cost(summary.totalCostUnits)} of {summary.budgetLimit}
          </Badge>
        ) : undefined}
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <TimeRangePicker value={range} defaultRange={DEFAULT_RANGE} onChange={r => update(p => writeRange(p, r))} />
        <label className="flex items-center gap-1.5 text-xs text-gray-500">
          Group by
          <select value={groupBy} onChange={e => update(p => { p.set('by', e.target.value); p.delete('then') })} className="rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
            {DIMENSIONS.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-1.5 text-xs text-gray-500">
          then by
          <select value={thenBy ?? ''} onChange={e => update(p => p.set('then', e.target.value))} className="rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
            <option value="">Nothing</option>
            {DIMENSIONS.filter(d => d.key !== groupBy).map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
          </select>
        </label>
      </div>

      {data && (data.models.length > 1 || data.areas.length > 1 || models.length + areas.length > 0) && (
        <div className="mb-4 flex flex-wrap items-center gap-1.5">
          {data.models.map(m => (
            <FilterChip key={m} active={models.includes(m)} onClick={() => update(p => { p.delete('model'); toggled(models, m).forEach(v => p.append('model', v)) })}>
              <span className="font-mono">{m}</span>
            </FilterChip>
          ))}
          <span className="mx-1 w-px self-stretch bg-gray-200 dark:bg-gray-800" />
          {data.areas.map(a => (
            <FilterChip key={a} active={areas.includes(a)} onClick={() => update(p => { p.delete('area'); toggled(areas, a).forEach(v => p.append('area', v)) })}>
              {a}
            </FilterChip>
          ))}
        </div>
      )}

      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Kpi label="Model calls" value={total ? total.calls.toLocaleString() : '…'} />
        <Kpi label="Input tokens" value={total ? total.inputTokens.toLocaleString() : '…'} />
        <Kpi label="Output tokens" value={total ? total.outputTokens.toLocaleString() : '…'} />
        <Kpi label="Cost units" value={total ? cost(total.costUnits) : '…'} />
      </div>

      <Card padding="md" className="mb-4">
        <div className="mb-2 flex justify-end">
          <div className="inline-flex rounded-lg bg-gray-100 p-0.5 dark:bg-gray-800" role="radiogroup" aria-label="Timeline shows">
            {METRICS.map(m => (
              <button key={m.key} role="radio" aria-checked={metric === m.key} onClick={() => setMetric(m.key)}
                className={`rounded-md px-2.5 py-0.5 text-xs font-medium ${metric === m.key ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-900 dark:text-gray-100' : 'text-gray-500'}`}>
                {m.label}
              </button>
            ))}
          </div>
        </div>
        {data ? (
          <Timeline
            noun={METRICS.find(m => m.key === metric)!.label.toLowerCase()}
            unit={data.bucketUnit}
            series={[{ key: metric, label: METRICS.find(m => m.key === metric)!.label, color: 'rgb(var(--brand-500))' }]}
            buckets={data.buckets.map(b => ({ start: b.start, values: { [metric]: Number(b[metric]) } }))}
            formatValue={metricFormat}
            onZoom={(from, to) => update(p => writeRange(p, { from, to }))}
          />
        ) : <div className="h-28" />}
      </Card>

      {data?.truncated && (
        <p className="mb-3 flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
          <AlertTriangle className="h-4 w-4" />This range has more than 20,000 model calls; the figures cover the newest 20,000.
        </p>
      )}

      {isLoading && <div className="flex justify-center py-12"><Spinner /></div>}
      {isError && <EmptyState icon={AlertTriangle} title="Usage could not be loaded" description={(error as Error).message} />}
      {data && data.groups.length === 0 && <EmptyState icon={BarChart2} title="No usage" description="No model calls in this range." />}

      {data && data.groups.length > 0 && (
        <div className="grid grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
          <section>
            <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">By {DIMENSIONS.find(d => d.key === groupBy)?.label.toLowerCase()}</h2>
            <Card padding="none" className="overflow-hidden">
              <ul className="divide-y divide-gray-100 dark:divide-gray-800/70">
                {data.groups.map(g => <GroupRow key={g.key} group={g} whole={Number(data.totals.costUnits)} depth={0} />)}
              </ul>
            </Card>
          </section>
          <section>
            <h2 className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-300">Latest calls</h2>
            <Card padding="none" className="overflow-hidden">
              <ul className="max-h-[32rem] divide-y divide-gray-100 overflow-y-auto dark:divide-gray-800/70">
                {data.entries.map(e => (
                  <li key={e.id} className="flex items-center gap-3 px-3 py-2 text-xs">
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-mono text-gray-800 dark:text-gray-200">{e.model}</span>
                      <span className="block truncate text-gray-400">{e.purpose ?? 'unspecified'}</span>
                    </span>
                    <span className="tabular-nums text-gray-500">↑{compact(e.inputTokens)} ↓{compact(e.outputTokens)}</span>
                    <span className="w-14 text-right tabular-nums font-medium text-gray-700 dark:text-gray-300">{cost(e.costUnits)}</span>
                    <span className="w-20 text-right text-gray-400" title={new Date(e.createdAt).toLocaleString()}>{relativeTime(e.createdAt)}</span>
                  </li>
                ))}
              </ul>
            </Card>
          </section>
        </div>
      )}
    </div>
  )
}

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <Card padding="md">
      <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{label}</p>
      <p className="text-2xl font-bold tabular-nums text-gray-900 dark:text-gray-100">{value}</p>
    </Card>
  )
}

/** A group with its share of the whole spend; one with sub-groups unfolds into them. */
function GroupRow({ group, whole, depth }: { group: UsageGroup; whole: number; depth: number }) {
  const [open, setOpen] = useState(depth === 0 && group.children.length > 0 && group.children.length <= 4)
  const share = whole > 0 ? Number(group.totals.costUnits) / whole : 0
  const nested = group.children.length > 0
  return (
    <li>
      <button onClick={() => nested && setOpen(o => !o)} disabled={!nested}
        className={`flex w-full items-center gap-3 py-2.5 pr-4 text-left text-sm transition-colors enabled:hover:bg-gray-50 dark:enabled:hover:bg-gray-800/40 ${depth ? 'pl-10 bg-gray-50/50 dark:bg-gray-950/30' : 'pl-4'}`}>
        <ChevronRight className={`h-4 w-4 shrink-0 text-gray-400 transition-transform ${open ? 'rotate-90' : ''} ${nested ? '' : 'invisible'}`} />
        <span className="min-w-0 flex-1">
          <span className={`block truncate ${depth ? 'text-gray-700 dark:text-gray-300' : 'font-medium text-gray-900 dark:text-gray-100'}`}>{group.label}</span>
          <span className="mt-1 block h-1.5 rounded-full bg-gray-100 dark:bg-gray-800">
            <span className="block h-1.5 rounded-full brand-gradient transition-all duration-500" style={{ width: `${Math.max(1, share * 100)}%` }} />
          </span>
        </span>
        <span className="hidden w-28 text-right text-xs tabular-nums text-gray-500 sm:block">{compact(group.totals.inputTokens + group.totals.outputTokens)} tokens</span>
        <span className="hidden w-16 text-right text-xs tabular-nums text-gray-500 sm:block">{group.totals.calls} calls</span>
        <span className="w-20 text-right tabular-nums font-medium text-gray-800 dark:text-gray-200">{cost(group.totals.costUnits)}</span>
        <span className="w-10 text-right text-xs tabular-nums text-gray-400">{Math.round(share * 100)}%</span>
      </button>
      {open && (
        <ul className="divide-y divide-gray-100 dark:divide-gray-800/70">
          {group.children.map(c => <GroupRow key={c.key} group={c} whole={whole} depth={depth + 1} />)}
        </ul>
      )}
    </li>
  )
}
