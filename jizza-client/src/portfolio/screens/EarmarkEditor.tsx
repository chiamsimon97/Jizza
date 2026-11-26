import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import type { ApiError } from '@/lib/api'
import type { HoldingDetail } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { StackedBar } from '../components/StackedBar'
import { ShortGoalSharesDialog, isShortGoalWarning } from '../components/ShortGoalShares'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'

/** Goal colours for the bar, in order; Unassigned is always grey. */
const COLOURS = ['bg-brand-500', 'bg-emerald-500', 'bg-amber-500', 'bg-sky-500', 'bg-rose-500', 'bg-violet-500', 'bg-teal-500']

/** Units to millionths, so 0.1 + 0.2 adds up exactly (units go to six decimals). */
export function toMicro(text: string): number | null {
  const t = text.trim()
  if (t === '') return 0
  if (!/^\d+(\.\d{0,6})?$/.test(t)) return null
  const [whole, frac = ''] = t.split('.')
  return Number(whole) * 1_000_000 + Number(frac.padEnd(6, '0'))
}

export function fromMicro(n: number): string {
  const whole = Math.floor(n / 1_000_000)
  const frac = String(n % 1_000_000).padStart(6, '0').replace(/0+$/, '')
  return frac ? `${whole}.${frac}` : String(whole)
}

/** What is wrong with a split, in the user's words; null when it adds up. */
export function splitProblem(typed: Record<string, string>, held: number): string | null {
  let total = 0
  for (const v of Object.values(typed)) {
    const m = toMicro(v)
    if (m == null) return 'Units are numbers with up to six decimals.'
    total += m
  }
  const h = Math.round(held * 1_000_000)
  if (total === h) return null
  return `The goals add up to ${fromMicro(total)} units; the account holds ${fromMicro(h)}.`
}

/**
 * The earmark editor (7.3): which goal each unit of one holding is for. A stacked bar, a units box
 * per goal, "Total X / Y units", and the FIFO lots a sale would use. Moving units between goals
 * is not a trade and changes no tax; a sale always uses the oldest lot first, whatever goal sells.
 */
export function EarmarkEditor({ accountId, instrumentId, onSaved }: { accountId: string; instrumentId: string; onSaved?: () => void }) {
  const sample = useSample()
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: financeKeys.holding(accountId, instrumentId),
    queryFn: () => financeApi.holding(accountId, instrumentId),
  })
  if (isLoading) return <SkeletonRows rows={4} />
  if (error || !data) return <LoadFailed text="Could not load this holding." onRetry={() => refetch()} />
  return <Editor key={data.earmarks.map(e => `${e.goalId}:${e.units}`).join('|')} holding={data} readOnly={sample}
    onSaved={() => { qc.invalidateQueries({ queryKey: financeKeys.all }); onSaved?.() }} />
}

function Editor({ holding, readOnly, onSaved }: { holding: HoldingDetail; readOnly: boolean; onSaved: () => void }) {
  const initial = useMemo(() => {
    const out: Record<string, string> = {}
    holding.goals.forEach(g => { out[g.id] = '' })
    holding.earmarks.forEach(e => { out[e.goalId] = fromMicro(Math.round((e.units ?? 0) * 1_000_000)) })
    return out
  }, [holding])
  const [typed, setTyped] = useState(initial)
  const [warning, setWarning] = useState<ApiError | null>(null)
  const problem = splitProblem(typed, holding.units)
  const totalMicro = Object.values(typed).reduce((s, v) => s + (toMicro(v) ?? 0), 0)
  const changed = Object.keys(typed).some(k => (toMicro(typed[k]) ?? -1) !== (toMicro(initial[k] ?? '') ?? -1))

  const save = useMutation({
    mutationFn: (keepShares: boolean) => financeApi.putEarmarks({
      accountId: holding.accountId, instrumentId: holding.instrumentId, keepShares,
      split: Object.entries(typed).filter(([, v]) => (toMicro(v) ?? 0) > 0).map(([goalId, v]) => ({ goalId, units: Number(v) })),
    }),
    onSuccess: () => { setWarning(null); onSaved() },
    onError: e => { if (isShortGoalWarning(e)) setWarning(e) },
  })

  const goals = holding.goals
  const colour = (id: string, i: number) => (goals.find(g => g.id === id)?.unassigned ? 'bg-gray-400' : COLOURS[i % COLOURS.length])
  const perUnit = holding.units > 0 && holding.value != null ? holding.value / holding.units : null

  return (
    <div className="flex flex-col gap-4">
      <StackedBar title="Units by goal" format={n => `${fromMicro(Math.round(n * 1_000_000))} units`}
        segments={goals.map((g, i) => ({ label: g.name, value: (toMicro(typed[g.id] ?? '') ?? 0) / 1_000_000, className: colour(g.id, i) }))} />

      <Panel title="Split between goals">
        <div className="flex flex-col gap-2">
          {goals.map(g => {
            const units = toMicro(typed[g.id] ?? '')
            return (
              <div key={g.id} className="flex flex-wrap items-center gap-2">
                <label htmlFor={`em-${g.id}`} className="min-w-0 flex-1 text-sm text-gray-800 dark:text-gray-200">
                  {g.name}
                  {g.short && holding.directShares && <span className="ml-2"><Pill tone="warn">Under 3 years</Pill></span>}
                  {g.yourChoice && <span className="ml-2"><Pill tone="info">Kept by your choice</Pill></span>}
                </label>
                <input id={`em-${g.id}`} inputMode="decimal" disabled={readOnly} aria-label={`Units for ${g.name}`}
                  className="w-32 rounded-lg border border-gray-300 px-2 py-1 text-right text-sm tabular-nums dark:border-gray-700 dark:bg-gray-900"
                  value={typed[g.id] ?? ''} placeholder="0"
                  onChange={e => setTyped(t => ({ ...t, [g.id]: e.target.value }))} />
                <span className="w-24 text-right text-xs tabular-nums text-gray-500 dark:text-gray-400">
                  {perUnit != null && units != null ? <Money amount={(units / 1_000_000) * perUnit} short /> : ''}
                </span>
              </div>
            )
          })}
        </div>
        <p className={`text-sm tabular-nums ${problem ? 'text-red-600' : 'text-gray-600 dark:text-gray-400'}`} data-testid="earmark-total">
          Total {fromMicro(totalMicro)} / {fromMicro(Math.round(holding.units * 1_000_000))} units
        </p>
        {problem && <p className="text-sm text-red-600" role="alert">{problem}</p>}
        <p className="text-xs text-gray-500 dark:text-gray-400">Moving units between goals is not a sale. Nothing is bought or sold, and no tax arises.</p>
        {save.error && !isShortGoalWarning(save.error) && <p className="text-sm text-red-600" role="alert">{errorText(save.error, 'Could not save the split.')}</p>}
        {!readOnly && (
          <div className="flex justify-end">
            <Button disabled={!!problem || !changed} loading={save.isPending && !warning} onClick={() => save.mutate(false)}>Save split</Button>
          </div>
        )}
      </Panel>

      <Panel title="Tax lots, in the order a sale uses them">
        {holding.saleNote && <p className="text-sm text-gray-700 dark:text-gray-300">{holding.saleNote}</p>}
        <DataTable caption="Tax lots" rowKey={l => l.id} rows={holding.lots} columns={[
          { header: 'Bought', cell: l => (l.dateKnown && l.acquiredOn ? formatDay(l.acquiredOn) : 'Date unknown') },
          { header: 'Units left', numeric: true, cell: l => fromMicro(Math.round(l.remaining * 1_000_000)) },
          { header: 'Cost a unit', numeric: true, cell: l => (l.costPerUnit == null ? 'Unknown' : <Money amount={l.costPerUnit} />) },
          { header: 'Term today', cell: l => (l.termToday === 'lt' ? 'Long term' : l.termToday === 'st' ? 'Short term' : 'Unknown') },
          { header: 'Gain today', numeric: true, cell: l => (l.gainToday == null ? '' : <Money amount={l.gainToday} signed />) },
        ]} />
        <p className="text-xs text-gray-500 dark:text-gray-400">Whichever goal sells, the oldest lot goes first. Goals never hold particular lots.</p>
      </Panel>

      <ShortGoalSharesDialog error={warning} busy={save.isPending} onKeep={() => save.mutate(true)}
        onCancel={() => { setWarning(null); save.reset() }} />
    </div>
  )
}
