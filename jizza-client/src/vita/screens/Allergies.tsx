import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, TriangleAlert } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { DataTable } from '@/portfolio/components/DataTable'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import type { Allergy, AllergyCategory, AllergyInput, AllergySeverity } from '@/types/vita'
import { ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { ALLERGY_KINDS, SEVERITIES } from '../labels'
import { vitaKeys } from '../queryKeys'
import { NoneKnown } from './Conditions'

const RANK: Record<string, number> = { severe: 0, moderate: 1, mild: 2, unknown: 3 }

export function SeverityPill({ severity }: { severity: AllergySeverity | null }) {
  const s = severity ?? 'unknown'
  return <Pill tone={s === 'severe' ? 'bad' : s === 'moderate' ? 'warn' : 'neutral'}>{s === 'severe' && <TriangleAlert className="h-3 w-3" aria-hidden />}{SEVERITIES[s]}</Pill>
}

/** Allergies (7.11): a simple table with an add row; "None known" is a recorded answer. */
export function Allergies({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.allergies(personId), queryFn: () => vitaApi.allergies(personId) })
  const profile = useQuery({ queryKey: vitaKeys.profile(personId), queryFn: () => vitaApi.profile(personId) })
  const [adding, setAdding] = useState(false)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeAllergy(personId, id), onSuccess: refresh })
  const none = useMutation({ mutationFn: (v: boolean) => vitaApi.saveProfile(personId, { allergiesNoneKnown: v }), onSuccess: refresh })
  const marked = profile.data?.profile.allergiesNoneKnown === true
  const rows = [...(data ?? [])].sort((a, b) => RANK[a.severity ?? 'unknown'] - RANK[b.severity ?? 'unknown'])

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Allergies" sub="Shown first on the emergency card, most severe first">
        {!readOnly && <Button size="sm" onClick={() => setAdding(true)}><Plus className="h-3.5 w-3.5" aria-hidden />Add allergy</Button>}
      </ViewTitle>
      {adding && <AllergyForm personId={personId} onDone={() => { setAdding(false); refresh() }} />}
      <Panel title="Recorded">
        {isLoading && <SkeletonRows rows={2} />}
        {error && <LoadFailed text="Could not load the allergies." onRetry={() => refetch()} />}
        {data && !data.length && (
          <div className="flex flex-wrap items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
            {!marked && <span>No allergies recorded.</span>}
            <NoneKnown marked={marked} disabled={readOnly} pending={none.isPending} onMark={() => none.mutate(true)} onUnmark={() => none.mutate(false)} />
          </div>
        )}
        {rows.length > 0 && (
          <DataTable<Allergy> rows={rows} rowKey={a => a.id} caption="Allergies" columns={[
            { header: 'Substance', cell: a => <b>{a.substance}</b> },
            { header: 'Kind', cell: a => (a.category ? ALLERGY_KINDS[a.category] : '—') },
            { header: 'Reaction', cell: a => a.reaction ?? '—' },
            { header: 'Severity', cell: a => <SeverityPill severity={a.severity} /> },
            { header: 'Confirmed by a doctor', cell: a => (a.confirmedByDoctor ? 'Yes' : 'No') },
            ...(readOnly ? [] : [{ header: '', cell: (a: Allergy) => (
              <Button size="sm" variant="ghost" aria-label={`Remove ${a.substance}`} onClick={() => remove.mutate(a.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
            ) }]),
          ]} />
        )}
        {(remove.error || none.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error ?? none.error, 'Could not save that.')}</p>}
      </Panel>
    </div>
  )
}

function AllergyForm({ personId, onDone }: { personId: string; onDone: () => void }) {
  const [v, setV] = useState<AllergyInput>({ substance: '', category: 'drug', reaction: '', severity: 'unknown', confirmedByDoctor: false })
  const save = useMutation({
    mutationFn: () => vitaApi.addAllergy(personId, { ...v, substance: v.substance?.trim(), reaction: v.reaction?.trim() || null }),
    onSuccess: onDone,
  })
  return (
    <Panel title="Add an allergy">
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Substance" value={v.substance} maxLength={120} placeholder="Penicillin" onChange={substance => setV({ ...v, substance })} />
          <SelectField label="Kind" value={(v.category ?? 'drug') as AllergyCategory} onChange={category => setV({ ...v, category })}
            options={Object.entries(ALLERGY_KINDS).map(([value, label]) => ({ value: value as AllergyCategory, label }))} />
          <TextField label="Reaction (optional)" value={v.reaction ?? ''} maxLength={300} placeholder="Skin rash" onChange={reaction => setV({ ...v, reaction })} />
          <SelectField label="Severity" value={(v.severity ?? 'unknown') as AllergySeverity} onChange={severity => setV({ ...v, severity })}
            options={Object.entries(SEVERITIES).map(([value, label]) => ({ value: value as AllergySeverity, label }))} />
        </div>
        <CheckField label="Confirmed by a doctor" checked={v.confirmedByDoctor === true} onChange={c => setV({ ...v, confirmedByDoctor: c })} />
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={!v.substance?.trim()}>Save</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
        </div>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the allergy.')}</p>}
      </form>
    </Panel>
  )
}
