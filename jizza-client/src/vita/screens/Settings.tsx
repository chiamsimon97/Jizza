import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ExternalLink, RotateCcw } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { JsonValue } from '@/types/finance'
import type { VitaConfigItem } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { FigureLabel, NeedsVerification } from '../components/FigureLabel'
import { errorText, unitsOf, useVitaConfig } from '../data'
import { configLabel, describeFigure, FIGURE_GROUPS } from '../labels'
import { vitaKeys } from '../queryKeys'

/**
 * Settings (7.18): units, emergency numbers, and every figure Vita uses with where it comes from. A
 * figure with no source shows "Not set: ask your doctor" and has no number to edit. Reminder times
 * and the document reader arrive with the updates that use them.
 */
export function Settings({ onOpen }: { onOpen: (view: string) => void }) {
  const { data, isLoading, error, refetch } = useVitaConfig()
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Settings" sub="Units, emergency numbers, and the figures Vita uses">
        <Button size="sm" variant="secondary" onClick={() => onOpen('profile')}>Profile</Button>
      </ViewTitle>
      {isLoading && <SkeletonRows rows={6} />}
      {error && <LoadFailed text="Could not load Vita’s settings." onRetry={() => refetch()} />}
      {data && <SettingsBody items={data} />}
    </div>
  )
}

function SettingsBody({ items }: { items: VitaConfigItem[] }) {
  const units = unitsOf(items)
  const numbers = items.find(i => i.key === 'emergency.numbers')
  return (
    <>
      <div className="grid gap-4 md:grid-cols-2">
        <Units units={units} />
        {numbers && <EmergencyNumbers item={numbers} />}
        <Panel title="Reminders">
          <p className="text-sm text-gray-600 dark:text-gray-300">Reminder times (morning, afternoon, evening, night), the daily-check time, the missed-dose wait and refill reminders come with the medicines update. Medicine names stay out of notifications unless you turn them on.</p>
        </Panel>
        <Panel title="Which AI provider reads documents">
          <p className="text-sm text-gray-600 dark:text-gray-300">Documents arrive with a later update. When they do, they are read only by the AI provider you choose in <Link className="underline" to="/settings/ai-models">AI models</Link>.</p>
        </Panel>
      </div>
      <Panel title="Figures Vita uses">
        <p className="text-sm text-gray-600 dark:text-gray-300">
          Three kinds of label: “Vita default” with <NeedsVerification /> until someone checks it against its source; “from Dr …” when a doctor set it for a person (added with the doctors update); and “Not set: ask your doctor” where no source was found, so Vita works nothing out from it.
        </p>
      </Panel>
      {FIGURE_GROUPS.map(g => {
        const rows = items.filter(i => i.key.startsWith(g.prefix))
        if (!rows.length) return null
        return (
          <Panel key={g.prefix} title={g.title}>
            <p className="-mt-1 text-xs text-gray-500 dark:text-gray-400">{g.about}</p>
            <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
              {rows.map(i => <FigureRow key={i.key} item={i} />)}
            </ul>
          </Panel>
        )
      })}
    </>
  )
}

function FigureRow({ item }: { item: VitaConfigItem }) {
  const qc = useQueryClient()
  const reset = useMutation({ mutationFn: () => vitaApi.resetConfig(item.key), onSuccess: () => qc.invalidateQueries({ queryKey: vitaKeys.all }) })
  return (
    <li className="flex flex-col gap-1 py-2.5 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <div className="min-w-0">
        <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{configLabel(item.key)}</p>
        <p className={`text-sm ${item.state === 'not_set' ? 'text-gray-500 dark:text-gray-400' : 'tabular-nums text-gray-800 dark:text-gray-200'}`}>
          {item.state === 'not_set' ? 'No number' : describeFigure(item.key, item.value)}
        </p>
        {item.sourceUrl && <p className="text-xs text-gray-500 dark:text-gray-400">Source: {/^https?:/.test(item.sourceUrl)
          ? <a className="inline-flex items-center gap-0.5 underline" href={item.sourceUrl} target="_blank" rel="noreferrer">{item.sourceUrl}<ExternalLink className="h-3 w-3" aria-hidden /></a>
          : item.sourceUrl}</p>}
      </div>
      <div className="flex shrink-0 flex-col items-start gap-1 sm:items-end">
        {item.isOverride ? <span className="text-xs font-medium text-brand-700 dark:text-brand-300">Your own value</span> : <FigureLabel state={item.state} label={item.label} />}
        {item.isOverride && (
          <Button size="sm" variant="ghost" loading={reset.isPending} onClick={() => reset.mutate()}><RotateCcw className="h-3 w-3" aria-hidden />Reset to Vita default</Button>
        )}
      </div>
    </li>
  )
}

function useSave(key: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (value: JsonValue) => vitaApi.putConfig(key, value),
    onSuccess: () => qc.invalidateQueries({ queryKey: vitaKeys.all }),
  })
}

function Units({ units }: { units: ReturnType<typeof unitsOf> }) {
  const save = useSave('display.units')
  const set = (patch: Partial<typeof units>) => save.mutate({ ...units, ...patch })
  return (
    <Panel title="Units">
      <div className="grid gap-3 sm:grid-cols-3">
        <SelectField label="Weight" value={units.weight} onChange={weight => set({ weight })} options={[{ value: 'kg', label: 'kg' }, { value: 'lb', label: 'lb' }]} />
        <SelectField label="Height" value={units.height} onChange={height => set({ height })} options={[{ value: 'cm', label: 'cm' }, { value: 'ft_in', label: 'feet and inches' }]} />
        <SelectField label="Temperature" value={units.temperature} onChange={temperature => set({ temperature })} options={[{ value: 'C', label: '°C' }, { value: 'F', label: '°F' }]} />
      </div>
      <p className="text-xs text-gray-500 dark:text-gray-400">Values are always stored in metric units and shown in yours. Sugar is in mg/dL.</p>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the units.')}</p>}
    </Panel>
  )
}

function EmergencyNumbers({ item }: { item: VitaConfigItem }) {
  const value = (item.value && typeof item.value === 'object' && !Array.isArray(item.value) ? item.value : {}) as Record<string, JsonValue>
  const [ambulance, setAmbulance] = useState(String(value.ambulanceWhereTheyLive ?? ''))
  const save = useSave(item.key)
  const changed = ambulance.trim() !== String(value.ambulanceWhereTheyLive ?? '')
  return (
    <Panel title="Emergency numbers">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-gray-600 dark:text-gray-300">Emergency</span>
        <b className="font-mono">{String(value.emergency ?? '—')}</b>
        <FigureLabel state={item.state} label={item.label} />
      </div>
      <form className="flex items-end gap-2" onSubmit={e => { e.preventDefault(); save.mutate({ ...value, ambulanceWhereTheyLive: ambulance.trim() }) }}>
        <TextField className="flex-1" label="Ambulance number where they live" value={ambulance} maxLength={30} placeholder="Not set" onChange={setAmbulance} />
        <Button size="sm" type="submit" disabled={!changed} loading={save.isPending}>Save</Button>
      </form>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the number.')}</p>}
    </Panel>
  )
}
