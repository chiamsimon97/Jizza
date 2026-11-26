import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Lock, Pencil, Plus, Trash2 } from 'lucide-react'
import { financeApi } from '@/api/finance'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { SlideOver } from '@/components/ui/SlideOver'
import { formatINRShort } from '@/lib/money'
import type { AssetClass, AssetKind, FinAsset, FinAssetInput, FinPerson, NetWorthSlice } from '@/types/finance'
import { AsOf, formatDay, formatMonth } from '../components/AsOf'
import { DataTable } from '../components/DataTable'
import { AmountField, CheckField, SelectField, TextField } from '../components/Fields'
import { Money } from '../components/Money'
import { SkeletonRows } from '../components/SkeletonRows'
import { TrendLine } from '../components/TrendLine'
import { ASSET_CLASSES, ASSET_KINDS } from '../labels'
import { errorText, useAssets, useNetWorth, useSample } from '../data'
import { financeKeys } from '../queryKeys'

const CLASS_WORDS: Record<string, string> = { ...ASSET_CLASSES, lent: 'Money lent', unassigned: 'Not classed' }

/**
 * Net worth and assets (7.25): the trend from monthly snapshots, what you own by class and by
 * person with each value's "valued on" date, pledged assets with a lock, and what you owe.
 */
export function NetWorth({ people, onOpen }: { people: FinPerson[]; onOpen: (view: string) => void }) {
  const sample = useSample()
  const { data, isLoading, error, refetch } = useNetWorth()
  const { data: assets } = useAssets()
  const [editing, setEditing] = useState<FinAsset | 'new' | null>(null)
  if (isLoading) return <SkeletonRows rows={5} />
  if (error || !data) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
        <p className="text-sm text-red-700 dark:text-red-300">Could not load your net worth.</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>Try again</Button>
      </div>
    )
  }
  const names = Object.fromEntries(people.map(p => [p.id, p.kind === 'self' ? 'You' : p.displayName]))
  const owned = data.byClass.filter(s => s.assets > 0)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Net worth and assets</h2>
        <AsOf date={data.asOf} />
        <span className="flex-1" />
        {!sample && <Button size="sm" variant="secondary" onClick={() => setEditing('new')}><Plus className="h-4 w-4" /> Add asset</Button>}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <section className="flex min-w-0 flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900 lg:col-span-2">
          <p className="text-2xl font-semibold text-gray-900 dark:text-gray-100"><Money amount={data.netWorth} short /></p>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            You own <Money amount={data.assets} short />{data.pledged > 0 && <> (<Money amount={data.pledged} short /> of it pledged)</>} and owe <Money amount={data.liabilities} short />.
          </p>
          {data.trend.length >= 2 ? (
            <TrendLine title="Month by month" description="Net worth on the 1st of each month."
              points={data.trend.map(t => ({ label: formatMonth(t.asOf), value: t.netWorth }))} />
          ) : (
            <p className="text-xs text-gray-500 dark:text-gray-400">Kai saves a snapshot on the 1st of each month; the trend line starts with the second one.</p>
          )}
        </section>

        <div className="flex min-w-0 flex-col gap-4">
          <Shares title="By type" slices={owned} total={data.assets} label={k => CLASS_WORDS[k] ?? k} />
          {data.byPerson.filter(s => s.assets > 0).length > 1 && (
            <Shares title="By person" slices={data.byPerson.filter(s => s.assets > 0)} total={data.assets} label={k => names[k] ?? 'Someone you manage'} />
          )}
          <section className="flex flex-col gap-1 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
            <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Owed</h3>
            <p className="text-sm text-gray-700 dark:text-gray-300"><Money amount={data.liabilities} /></p>
            <button type="button" className="self-start text-sm text-brand-600 hover:underline dark:text-brand-300" onClick={() => onOpen('loans')}>Loans</button>
          </section>
        </div>
      </div>

      <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Assets</h3>
        <DataTable<FinAsset> rows={assets ?? []} rowKey={a => a.id} caption="Assets" empty="No assets yet. Add one, or answer the savings question in Quick start."
          columns={[
            { header: 'Asset', cell: a => (
              <span className="flex flex-wrap items-center gap-1.5">
                {a.name || ASSET_KINDS[a.kind]}
                {a.isEmergency && <Badge variant="info">Emergency</Badge>}
                {a.pledged && <Badge><Lock className="mr-1 h-3 w-3" aria-hidden />Pledged</Badge>}
              </span>
            ) },
            { header: 'Whose', cell: a => names[a.personId ?? ''] ?? '—' },
            { header: 'Value', numeric: true, cell: a => <Money amount={a.value} /> },
            { header: 'Valued', cell: a => <>{formatDay(a.valueAsOf)}{a.isEstimate ? <span className="block text-xs text-gray-500">your estimate</span> : null}</> },
            ...(sample ? [] : [{ header: '', cell: (a: FinAsset) => <Button size="sm" variant="ghost" onClick={() => setEditing(a)} aria-label={`Revalue ${a.name || ASSET_KINDS[a.kind]}`}><Pencil className="h-3.5 w-3.5" /></Button> }]),
          ]} />
      </section>

      {editing && <AssetForm asset={editing === 'new' ? null : editing} people={people} onClose={() => setEditing(null)} />}
    </div>
  )
}

function Shares({ title, slices, total, label }: { title: string; slices: NetWorthSlice[]; total: number; label: (key: string) => string }) {
  return (
    <section className="flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-4 shadow-sm transition-shadow duration-200 hover:shadow-md dark:border-gray-800 dark:bg-gray-900">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
      {slices.length === 0 ? <p className="text-sm text-gray-500">Nothing yet.</p> : slices.map(s => {
        const pct = total > 0 ? Math.round((s.assets / total) * 100) : 0
        return (
          <div key={s.key} className="flex flex-col gap-1">
            <div className="flex justify-between text-sm">
              <span className="text-gray-700 dark:text-gray-300">{label(s.key)}</span>
              <span className="tabular-nums text-gray-500 dark:text-gray-400">{formatINRShort(s.assets)} · {pct}%</span>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" aria-hidden>
              <div className="h-full rounded-full bg-brand-500" style={{ width: `${pct}%` }} />
            </div>
          </div>
        )
      })}
    </section>
  )
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function AssetForm({ asset, people, onClose }: { asset: FinAsset | null; people: FinPerson[]; onClose: () => void }) {
  const qc = useQueryClient()
  const self = people.find(p => p.kind === 'self')
  const [form, setForm] = useState<FinAssetInput>({
    kind: asset?.kind ?? 'fd',
    name: asset?.name ?? '',
    assetClass: asset?.assetClass ?? null,
    personId: asset?.personId ?? self?.id,
    value: asset?.value,
    valueAsOf: today(),
    isEmergency: asset?.isEmergency ?? false,
    isEstimate: asset?.isEstimate ?? false,
  })
  const set = (patch: Partial<FinAssetInput>) => setForm(f => ({ ...f, ...patch }))
  const done = () => { qc.invalidateQueries({ queryKey: financeKeys.all }); onClose() }
  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, name: form.name?.trim() || null }
      return asset ? financeApi.updateAsset(asset.id, body) : financeApi.addAsset(body)
    },
    onSuccess: done,
  })
  const remove = useMutation({ mutationFn: () => financeApi.deleteAsset(asset!.id), onSuccess: done })

  return (
    <SlideOver open title={asset ? `Revalue ${asset.name || ASSET_KINDS[asset.kind]}` : 'Add an asset'} onClose={onClose}
      footer={<>
        {asset && !asset.pledged && <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}><Trash2 className="h-4 w-4" /> Remove</Button>}
        <span className="flex-1" />
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button loading={save.isPending} disabled={form.value == null} onClick={() => save.mutate()}>Save</Button>
      </>}>
      <div className="flex flex-col gap-4">
        <SelectField<AssetKind> label="Kind" value={form.kind!} onChange={kind => set({ kind })}
          options={(Object.keys(ASSET_KINDS) as AssetKind[]).map(k => ({ value: k, label: ASSET_KINDS[k] }))} />
        <TextField label="Name (optional)" value={form.name} onChange={name => set({ name })} />
        {form.kind === 'other' && (
          <SelectField<AssetClass> label="Type" value={form.assetClass ?? 'other'} onChange={assetClass => set({ assetClass })}
            options={(Object.keys(ASSET_CLASSES) as AssetClass[]).map(k => ({ value: k, label: ASSET_CLASSES[k] }))} />
        )}
        <SelectField label="Whose" value={form.personId ?? ''} onChange={personId => set({ personId })}
          options={people.filter(p => p.status === 'active').map(p => ({ value: p.id, label: p.kind === 'self' ? 'You' : p.displayName }))} />
        <AmountField label="Value" value={form.value} onChange={v => set({ value: v ?? undefined })} />
        <TextField label="Valued on" type="date" value={form.valueAsOf ?? ''} onChange={valueAsOf => set({ valueAsOf })} />
        <CheckField label="Counts as emergency money" checked={!!form.isEmergency} onChange={isEmergency => set({ isEmergency })} />
        <CheckField label="This is my estimate" checked={!!form.isEstimate} onChange={isEstimate => set({ isEstimate })} />
        {asset?.pledged && <p className="text-xs text-gray-500 dark:text-gray-400">Pledged against a loan: remove the pledge on the loan before removing the asset.</p>}
        {(save.error || remove.error) && <p className="text-sm text-red-600">{errorText(save.error ?? remove.error, 'Could not save.')}</p>}
      </div>
    </SlideOver>
  )
}
