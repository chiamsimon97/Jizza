import { useQuery } from '@tanstack/react-query'
import { PieChart } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { EmptyState } from '@/components/ui/EmptyState'
import type { AllocationRow } from '@/types/finance'
import { AsOf } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { fromMicro } from './EarmarkEditor'

const pct = (n: number | undefined) => (n == null ? '' : `${n.toFixed(1)}%`)

function Status({ row }: { row: AllocationRow }) {
  if (row.status === 'above') return <Pill tone="warn">Above band</Pill>
  if (row.status === 'below') return <Pill tone="warn">Below band</Pill>
  if (row.status === 'inside') return <Pill tone="ok">Inside band</Pill>
  return <Pill>No target</Pill>
}

/** Target, band and actual on one line: a bar with the band shaded and a tick at the target. */
function DriftBar({ row }: { row: AllocationRow }) {
  const clamp = (n: number) => Math.max(0, Math.min(100, n))
  return (
    <div className="relative h-2.5 w-40 rounded-full bg-gray-100 dark:bg-gray-800" aria-hidden>
      {row.bandLow != null && row.bandHigh != null && (
        <div className="absolute inset-y-0 rounded-full bg-emerald-200 dark:bg-emerald-900/60"
          style={{ left: `${clamp(row.bandLow)}%`, width: `${clamp(row.bandHigh) - clamp(row.bandLow)}%` }} />
      )}
      {row.targetPct != null && <div className="absolute inset-y-[-2px] w-0.5 bg-gray-700 dark:bg-gray-200" style={{ left: `${clamp(row.targetPct)}%` }} />}
      <div className={`absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full ${row.status === 'above' || row.status === 'below' ? 'bg-amber-500' : 'bg-brand-500'}`}
        style={{ left: `${clamp(row.actualPct)}%` }} />
    </div>
  )
}

/**
 * Allocation and drift (7.7): one goal's actual mix against its tree, each part with its band
 * (5/25 unless a rule sets another). "All goals" shows the whole picture by asset class, without
 * targets. Kai only shows drift here; proposals come in a later phase.
 */
export function Allocation({ goal, onOpen }: { goal: string | null; onOpen: (view: string) => void }) {
  const sample = useSample()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: financeKeys.allocation(goal),
    queryFn: () => financeApi.allocation(goal),
    enabled: !sample,
  })
  if (sample) {
    return <EmptyState icon={PieChart} title="Allocation needs your own holdings" description="Leave the sample household to see your goals' mix." />
  }
  if (isLoading) return <SkeletonRows rows={6} />
  if (error || !data) return <LoadFailed text="Could not load the allocation." onRetry={() => refetch()} />

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Allocation: {data.goalName}</h2>
        {data.priceAsOf && <AsOf date={data.priceAsOf} />}
        <span className="flex-1" />
        <span className="text-sm tabular-nums text-gray-700 dark:text-gray-300">Total <Money amount={data.total} /></span>
      </div>
      {data.goalId && !data.treeSaved && (
        <p className="text-sm text-gray-600 dark:text-gray-400">
          These targets are Kai&apos;s starting mix. <button type="button" className="text-brand-700 underline dark:text-brand-300" onClick={() => onOpen('asset-tree')}>Set the asset tree</button> to make them yours.
        </p>
      )}
      {data.goalId && data.outsideBand > 0 && (
        <p className="text-sm text-amber-700 dark:text-amber-300" role="status">
          {data.outsideBand === 1 ? 'One part is' : `${data.outsideBand} parts are`} outside the rebalance band. Kai only watches for now.
        </p>
      )}
      <Panel title={data.goalId ? 'Target, band and actual' : 'All goals by asset class'}>
        <DataTable caption="Allocation" rowKey={r => r.id} rows={data.rows} empty="Nothing earmarked to this goal yet." columns={[
          { header: 'Part', cell: r => <span style={{ paddingLeft: `${r.depth * 1.25}rem` }} className={r.depth === 0 ? 'font-medium' : ''}>{r.name}{r.yourChoice && <span className="ml-2"><Pill tone="info">Kept by your choice</Pill></span>}</span> },
          { header: 'Actual', numeric: true, cell: r => pct(r.actualPct) },
          ...(data.goalId ? [
            { header: 'Target', numeric: true, cell: (r: AllocationRow) => pct(r.targetPct) },
            { header: 'Band', numeric: true, cell: (r: AllocationRow) => (r.bandLow == null ? '' : `${r.bandLow.toFixed(1)}–${r.bandHigh?.toFixed(1)}%`) },
            { header: 'Drift', cell: (r: AllocationRow) => <div className="flex items-center gap-2"><DriftBar row={r} /><Status row={r} /></div> },
          ] : []),
          { header: 'Value', numeric: true, cell: r => <Money amount={r.value} /> },
        ]} />
      </Panel>
      {data.offTree.length > 0 && (
        <Panel title="Not in the tree">
          <p className="text-sm text-gray-600 dark:text-gray-400">These have no place in the goal&apos;s tree, so they count in the total but in no part.</p>
          {data.offTree.map(o => <div key={o.instrumentId ?? o.assetId} className="flex justify-between text-sm"><span>{o.name}</span><Money amount={o.value} /></div>)}
        </Panel>
      )}
      {data.shortGoalShares.length > 0 && (
        <Panel title="Shares in a goal under 3 years away">
          {data.shortGoalShares.map(s => (
            <div key={s.instrumentId} className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span>{s.name} · {fromMicro(Math.round(s.units * 1_000_000))} units</span>
              {s.confirmed ? <Pill tone="info">Kept by your choice</Pill> : <Pill tone="warn">Not confirmed</Pill>}
            </div>
          ))}
        </Panel>
      )}
    </div>
  )
}
