import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Eye, Plus, ShoppingCart, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { fieldClass } from '@/components/ui/SlideOver'
import type { Instrument, ListItem, ListType } from '@/types/finance'
import { formatDay } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { AmountField, SelectField, TextField } from '../components/Fields'
import { Money } from '../components/Money'
import { LoadFailed, Panel, Pill } from '../components/Panel'
import { SkeletonRows } from '../components/SkeletonRows'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'
import { GoalPicker } from './GoalPicker'

const UNIT_ACCOUNTS = new Set(['demat', 'mf_folio', 'retirement'])

/**
 * Lists (7.11): a watchlist and a "next to buy" list, both the user's own. Kai never adds to them
 * and never names a fund. Watch moves to Next; Next becomes a holding once the user records the
 * purchase they made themselves.
 */
export function Lists() {
  const sample = useSample()
  const [type, setType] = useState<ListType>('watch')
  const [adding, setAdding] = useState(false)
  const [buying, setBuying] = useState<ListItem | null>(null)
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: [...financeKeys.lists, type], queryFn: () => financeApi.lists(type), enabled: !sample })
  const done = () => qc.invalidateQueries({ queryKey: financeKeys.all })
  const promote = useMutation({ mutationFn: (id: string) => financeApi.promoteListItem(id), onSuccess: done })
  const remove = useMutation({ mutationFn: financeApi.deleteListItem, onSuccess: done })

  if (sample) return <EmptyState icon={Eye} title="Lists are your own" description="Leave the sample household to keep a watchlist." />

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Lists</h2>
        <div role="tablist" className="ml-2 flex gap-1">
          {(['watch', 'next'] as const).map(t => (
            <button key={t} role="tab" type="button" aria-selected={type === t}
              className={`rounded-full px-3 py-1 text-sm ${type === t ? 'bg-brand-600 text-white' : 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'}`}
              onClick={() => setType(t)}>{t === 'watch' ? 'Watching' : 'Next to buy'}</button>
          ))}
        </div>
        <span className="flex-1" />
        <Button size="sm" onClick={() => setAdding(true)}><Plus className="h-4 w-4" /> Add</Button>
      </div>
      {(promote.error || remove.error) && <p className="text-sm text-red-600" role="alert">{errorText(promote.error ?? remove.error, 'Could not change the list.')}</p>}
      {isLoading ? <SkeletonRows rows={4} /> : error || !data ? <LoadFailed text="Could not load your list." onRetry={() => refetch()} /> : (
        <Panel title={type === 'watch' ? 'Watching' : 'Next to buy'}>
          <DataTable caption="List" rowKey={i => i.id} rows={data} empty={type === 'watch' ? 'Nothing on your watchlist yet.' : 'Nothing lined up to buy.'} columns={[
            { header: 'Name', cell: i => <>{i.name}{i.symbol && <span className="block text-xs text-gray-500">{i.symbol}</span>}</> },
            { header: 'Price', numeric: true, cell: i => (i.price == null ? 'No price yet' : <><Money amount={i.price} />{i.priceDate && <span className="block text-xs text-gray-500">{formatDay(i.priceDate)}</span>}</>) },
            { header: 'Change', numeric: true, cell: i => (i.changePct == null ? '' : <Pill tone={i.changePct < 0 ? 'bad' : 'ok'}>{i.changePct > 0 ? '+' : ''}{i.changePct.toFixed(2)}%</Pill>) },
            { header: 'For', cell: i => i.goalName ?? '' },
            { header: 'Notes', cell: i => i.notes ?? '' },
            {
              header: 'Actions', cell: i => (
                <div className="flex flex-wrap justify-end gap-1">
                  {i.listType === 'watch'
                    ? <Button size="sm" variant="ghost" onClick={() => promote.mutate(i.id)}><ArrowRight className="h-4 w-4" /> Next to buy</Button>
                    : <Button size="sm" variant="ghost" onClick={() => setBuying(i)}><ShoppingCart className="h-4 w-4" /> I bought it</Button>}
                  <Button size="sm" variant="ghost" aria-label={`Remove ${i.name}`} onClick={() => remove.mutate(i.id)}><Trash2 className="h-4 w-4" /></Button>
                </div>
              ),
            },
          ]} />
        </Panel>
      )}
      {adding && <AddDialog listType={type} onClose={() => setAdding(false)} onDone={done} />}
      {buying && <BoughtDialog item={buying} onClose={() => setBuying(null)} onDone={done} />}
    </div>
  )
}

function AddDialog({ listType, onClose, onDone }: { listType: ListType; onClose: () => void; onDone: () => void }) {
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 300)
    return () => clearTimeout(t)
  }, [q])
  const [picked, setPicked] = useState<Instrument | null>(null)
  const [goal, setGoal] = useState<string | null>(null)
  const [notes, setNotes] = useState('')
  const found = useQuery({ queryKey: financeKeys.instruments(debounced), queryFn: () => financeApi.searchInstruments(debounced), enabled: debounced.length >= 2 })
  const add = useMutation({
    mutationFn: async () => {
      const ins = picked ?? await financeApi.addInstrument({ name: q.trim() })
      return financeApi.addListItem({ listType, instrumentId: ins.id, planId: goal, notes: notes || undefined })
    },
    onSuccess: () => { onDone(); onClose() },
  })
  return (
    <Dialog open title={listType === 'watch' ? 'Watch something' : 'Line something up to buy'} onClose={onClose} size="lg">
      <div className="flex flex-col gap-3">
        <TextField label="Name, symbol or ISIN" value={q} onChange={v => { setQ(v); setPicked(null) }} placeholder="Type at least two letters" />
        {found.data && found.data.length > 0 && !picked && (
          <ul className="max-h-48 overflow-y-auto rounded-lg border border-gray-200 dark:border-gray-800">
            {found.data.map(i => (
              <li key={i.id}>
                <button type="button" className="w-full px-3 py-1.5 text-left text-sm hover:bg-gray-50 dark:hover:bg-gray-800" onClick={() => { setPicked(i); setQ(i.name) }}>
                  {i.name} <span className="text-xs text-gray-500">{[i.symbol, i.isin].filter(Boolean).join(' · ')}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {!picked && debounced.length >= 2 && found.data?.length === 0 && (
          <p className="text-sm text-gray-600 dark:text-gray-400">Not found. Adding it will list &ldquo;{q.trim()}&rdquo; by name.</p>
        )}
        <GoalPicker label="For goal" optional value={goal} onChange={setGoal} />
        <TextField label="Notes" value={notes} onChange={setNotes} maxLength={500} />
        {add.error && <p className="text-sm text-red-600" role="alert">{errorText(add.error, 'Could not add it.')}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button disabled={q.trim().length < 2} loading={add.isPending} onClick={() => add.mutate()}>Add</Button>
        </div>
      </div>
    </Dialog>
  )
}

function BoughtDialog({ item, onClose, onDone }: { item: ListItem; onClose: () => void; onDone: () => void }) {
  const accounts = useQuery({ queryKey: financeKeys.accounts, queryFn: financeApi.accounts })
  const options = (accounts.data ?? []).filter(a => UNIT_ACCOUNTS.has(a.kind))
  const [accountId, setAccountId] = useState('')
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10))
  const [units, setUnits] = useState('')
  const [price, setPrice] = useState<number | null>(null)
  useEffect(() => { if (!accountId && options.length) setAccountId(options[0].id) }, [accountId, options])
  const buy = useMutation({
    mutationFn: () => financeApi.promoteListItem(item.id, { accountId, date, units: Number(units), pricePerUnit: price ?? 0 }),
    onSuccess: () => { onDone(); onClose() },
  })
  const ok = accountId && Number(units) > 0 && price != null && price >= 0
  return (
    <Dialog open title={`Record your purchase of ${item.name}`} description="Kai records what you bought; it never places an order." onClose={onClose} size="lg">
      <div className="flex flex-col gap-3">
        {options.length === 0 ? <p className="text-sm text-gray-600 dark:text-gray-400">Add a demat or fund account first, under Connect.</p> : (
          <SelectField label="Account" value={accountId} onChange={setAccountId}
            options={options.map(a => ({ value: a.id, label: a.nickname || a.provider || a.kind }))} />
        )}
        <div className="grid gap-3 sm:grid-cols-3">
          <TextField label="Date" type="date" value={date} onChange={setDate} />
          <div className="flex flex-col gap-1">
            <label htmlFor="bought-units" className="text-sm font-medium text-gray-700 dark:text-gray-300">Units</label>
            <input id="bought-units" inputMode="decimal" className={fieldClass} value={units} onChange={e => setUnits(e.target.value)} />
          </div>
          <AmountField label="Price a unit" value={price} onChange={setPrice} />
        </div>
        {buy.error && <p className="text-sm text-red-600" role="alert">{errorText(buy.error, 'Could not record the purchase.')}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button disabled={!ok} loading={buy.isPending} onClick={() => buy.mutate()}>Record purchase</Button>
        </div>
      </div>
    </Dialog>
  )
}
