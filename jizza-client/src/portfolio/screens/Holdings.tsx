import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, RefreshCw, Wallet } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SlideOver } from '@/components/ui/SlideOver'
import type { HoldingRow } from '@/types/finance'
import { AsOf } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill, Row } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { EarmarkEditor, fromMicro } from './EarmarkEditor'

const units = (n: number) => fromMicro(Math.round(n * 1_000_000))

/** How a holding is valued, in words. */
function valuedBy(r: HoldingRow): string {
  if (r.valueSource === 'price' && r.priceDate) return `Price of ${r.priceDate}`
  if (r.valueSource === 'cost') return 'At cost: no price yet'
  return 'No price or cost yet'
}

/**
 * Holdings (7.2): what is held, in which account, and which goal each unit is for. "Needs a look"
 * comes first: holdings whose goals no longer add up, units sold outside Kai, lots with no date,
 * or no price. Opening a holding shows its earmarks and tax lots.
 */
export function Holdings({ person, goal, onOpen }: { person: string | null; goal: string | null; onOpen: (view: string) => void }) {
  const sample = useSample()
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: financeKeys.holdings(person, goal),
    queryFn: () => financeApi.holdings(person, goal),
    enabled: !sample,
  })
  const [open, setOpen] = useState<{ accountId: string; instrumentId: string; name: string } | null>(null)
  const refresh = useMutation({
    mutationFn: financeApi.refreshPrices,
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })

  if (sample) {
    return <EmptyState icon={Wallet} title="Holdings come from your own files"
      description="The sample household has no holdings. Import a fund statement or broker file under Connect." />
  }
  if (isLoading) return <SkeletonRows rows={6} />
  if (error || !data) return <LoadFailed text="Could not load your holdings." onRetry={() => refetch()} />

  const openRow = (r: HoldingRow, accountId = r.accounts[0]?.accountId) => {
    if (accountId) setOpen({ accountId, instrumentId: r.instrumentId, name: r.name })
  }
  const empty = data.rows.length === 0 && data.assets.length === 0

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Holdings</h2>
        {data.priceAsOf && <AsOf date={data.priceAsOf} />}
        {data.stalePrices && <Pill tone="warn">Some prices are more than 5 days old</Pill>}
        <span className="flex-1" />
        <Button size="sm" variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate()}>
          <RefreshCw className="h-4 w-4" /> Refresh fund prices
        </Button>
      </div>
      {refresh.error && <p className="text-sm text-red-600" role="alert">{errorText(refresh.error, 'Could not refresh prices.')}</p>}
      {refresh.data && <p className="text-sm text-gray-600 dark:text-gray-400" role="status">Prices updated for {refresh.data.matched} of your funds.</p>}

      {empty ? (
        <EmptyState icon={Wallet} title="No holdings yet"
          description="Import your mutual fund statement (CAS) or your broker's holdings or tradebook, and Kai lists each holding with its tax lots."
          action={{ label: 'Import a file', onClick: () => onOpen('connect') }} />
      ) : (
        <>
          <div className="grid gap-4 md:grid-cols-3">
            <Panel title="Value"><p className="text-xl font-semibold tabular-nums"><Money amount={data.value} /></p></Panel>
            <Panel title="Cost">
              <p className="text-xl font-semibold tabular-nums">{data.cost == null ? 'Partly unknown' : <Money amount={data.cost} />}</p>
            </Panel>
            <Panel title="Gain or loss">
              <p className="text-xl font-semibold tabular-nums">{data.pnl == null ? 'Unknown' : <Money amount={data.pnl} signed />}</p>
            </Panel>
          </div>

          {data.needsLook.length > 0 && (
            <Panel title={<><AlertTriangle className="h-4 w-4 text-amber-600" aria-hidden /> Needs a look ({data.needsLook.length})</>}>
              <ul className="flex flex-col gap-1.5">
                {data.needsLook.map(n => {
                  const row = data.rows.find(r => r.instrumentId === n.instrumentId)
                  return (
                    <li key={n.instrumentId} className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-medium text-gray-900 dark:text-gray-100">{n.name}</span>
                      <span className="flex-1 text-gray-600 dark:text-gray-400">{n.reason}</span>
                      {row && <Button size="sm" variant="ghost" onClick={() => openRow(row)}>Open</Button>}
                    </li>
                  )
                })}
              </ul>
            </Panel>
          )}

          <Panel title="Shares, funds and bonds">
            <DataTable caption="Holdings" rowKey={r => r.instrumentId} rows={data.rows} empty="No units held." columns={[
              {
                header: 'Holding', cell: r => (
                  <button type="button" className="text-left font-medium text-brand-700 hover:underline dark:text-brand-300" onClick={() => openRow(r)}>
                    {r.name}
                    <span className="block text-xs font-normal text-gray-500 dark:text-gray-400">
                      {r.accounts.map(a => a.label).join(', ')}
                    </span>
                  </button>
                ),
              },
              { header: 'Units', numeric: true, cell: r => units(r.units) },
              { header: 'Value', numeric: true, cell: r => (r.value == null ? '' : <><Money amount={r.value} /><span className="block text-xs text-gray-500">{valuedBy(r)}</span></>) },
              { header: 'Gain', numeric: true, cell: r => (r.pnl == null ? '' : <><Money amount={r.pnl} signed />{r.pnlPct != null && <span className="block text-xs text-gray-500">{r.pnlPct.toFixed(1)}%</span>}</>) },
              {
                header: 'Goals', cell: r => (
                  <div className="flex flex-wrap gap-1">
                    {r.split.map(s => <Pill key={s.goalId} tone={s.unassigned ? 'neutral' : 'brand'}>{s.name} {units(s.units ?? 0)}</Pill>)}
                    {r.needsLook.length > 0 && <Pill tone="warn">Needs a look</Pill>}
                  </div>
                ),
              },
            ]} />
          </Panel>

          {data.assets.length > 0 && (
            <Panel title="Deposits, balances and other assets">
              <DataTable caption="Assets" rowKey={a => a.assetId} rows={data.assets} columns={[
                { header: 'Asset', cell: a => a.name || a.kind },
                { header: 'Value', numeric: true, cell: a => <Money amount={a.value} /> },
                { header: 'Goals', cell: a => (
                  <div className="flex flex-wrap gap-1">
                    {a.split.map(s => <Pill key={s.goalId} tone={s.unassigned ? 'neutral' : 'brand'}>{s.name} <Money amount={s.amount ?? 0} short /></Pill>)}
                    {a.unassignedAmount > 0 && <Pill>Not earmarked <Money amount={a.unassignedAmount} short /></Pill>}
                  </div>
                ) },
              ]} />
            </Panel>
          )}

          <Panel title="By goal">
            {data.goals.map(g => <Row key={g.id} label={g.name}><Money amount={g.value} /></Row>)}
          </Panel>
        </>
      )}

      <SlideOver open={!!open} title={open?.name ?? ''} description="Which goal each unit is for, and the lots a sale would use."
        onClose={() => setOpen(null)} wide>
        {open && <EarmarkEditor accountId={open.accountId} instrumentId={open.instrumentId} />}
      </SlideOver>
    </div>
  )
}
