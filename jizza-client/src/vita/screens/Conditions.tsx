import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Clock, HelpCircle, Lock, Pencil, Plus, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { CheckField, SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { Condition, ConditionInput, ConditionStatus, ConditionTemplate } from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { errorText } from '../data'
import { COMMON_CONDITIONS, CONDITION_STATUS, TEMPLATES } from '../labels'
import { vitaKeys } from '../queryKeys'

const STATUS_ICON = { active: CheckCircle2, resolved: Clock, suspected: HelpCircle }
const STATUS_TONE = { active: 'brand', resolved: 'neutral', suspected: 'warn' } as const

export function StatusChip({ status }: { status: ConditionStatus | null }) {
  const s = status ?? 'active'
  const Icon = STATUS_ICON[s]
  return <Pill tone={STATUS_TONE[s]}><Icon className="h-3 w-3" aria-hidden />{CONDITION_STATUS[s]}</Pill>
}

/** "None known" as a recorded answer, not an empty list (7.5, 7.11). */
export function NoneKnown({ marked, disabled, onMark, onUnmark, pending }: {
  marked: boolean; disabled?: boolean; onMark: () => void; onUnmark: () => void; pending?: boolean
}) {
  return marked
    ? <span className="inline-flex items-center gap-2"><Pill tone="ok"><CheckCircle2 className="h-3 w-3" aria-hidden />None known</Pill>
      {!disabled && <Button size="sm" variant="ghost" loading={pending} onClick={onUnmark}>Change</Button>}</span>
    : <Button size="sm" variant="secondary" disabled={disabled} loading={pending} onClick={onMark}>Mark “None known”</Button>
}

/** Conditions (7.5): the list with status chips, "None known", and quick picks. Condition pages arrive in 1a. */
export function Conditions({ personId, readOnly }: { personId: string; readOnly: boolean }) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.conditions(personId), queryFn: () => vitaApi.conditions(personId) })
  const profile = useQuery({ queryKey: vitaKeys.profile(personId), queryFn: () => vitaApi.profile(personId) })
  const [editing, setEditing] = useState<Condition | ConditionInput | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeCondition(personId, id), onSuccess: refresh })
  const none = useMutation({ mutationFn: (v: boolean) => vitaApi.saveProfile(personId, { conditionsNoneKnown: v }), onSuccess: refresh })
  const marked = profile.data?.profile.conditionsNoneKnown === true
  const names = new Set((data ?? []).map(c => c.name.toLowerCase()))

  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title="Conditions" sub="Each long-term condition gets a page of its own with the next update">
        {!readOnly && <Button size="sm" onClick={() => setEditing({ status: 'active', template: 'generic' })}><Plus className="h-3.5 w-3.5" aria-hidden />Add condition</Button>}
      </ViewTitle>
      {editing && <ConditionForm personId={personId} start={editing} onDone={() => { setEditing(null); refresh() }} />}
      <Panel title="Recorded">
        {isLoading && <SkeletonRows rows={2} />}
        {error && <LoadFailed text="Could not load the conditions." onRetry={() => refetch()} />}
        {data && !data.length && (
          <div className="flex flex-wrap items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
            {!marked && <span>No conditions recorded. Pick a common one below, add your own, or</span>}
            <NoneKnown marked={marked} disabled={readOnly} pending={none.isPending} onMark={() => none.mutate(true)} onUnmark={() => none.mutate(false)} />
          </div>
        )}
        {data && data.length > 0 && (
          <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
            {data.map(c => (
              <li key={c.id} className="flex flex-wrap items-start justify-between gap-2 py-2.5">
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                    {c.name}<StatusChip status={c.status} />
                    {c.private && <Pill><Lock className="h-3 w-3" aria-hidden />Not on the card</Pill>}
                  </p>
                  <p className="text-xs text-gray-500 dark:text-gray-400">
                    {c.since ? `Since ${formatDay(c.since)}` : 'Since: not recorded'}
                    {c.template && c.template !== 'generic' ? ` · ${TEMPLATES[c.template]} page with the next update` : ''}
                  </p>
                  {c.notes && <p className="mt-1 text-sm text-gray-700 dark:text-gray-300">{c.notes}</p>}
                </div>
                {!readOnly && (
                  <div className="flex gap-1">
                    <Button size="sm" variant="ghost" aria-label={`Edit ${c.name}`} onClick={() => setEditing(c)}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                    <Button size="sm" variant="ghost" aria-label={`Remove ${c.name}`} onClick={() => remove.mutate(c.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
        {(remove.error || none.error) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error ?? none.error, 'Could not save that.')}</p>}
      </Panel>
      {!readOnly && (
        <Panel title="Common conditions">
          <div className="flex flex-wrap gap-2">
            {COMMON_CONDITIONS.map(c => (
              <Chip key={c.name} on={names.has(c.name.toLowerCase())} onClick={() => !names.has(c.name.toLowerCase()) && setEditing({ name: c.name, template: c.template, status: 'active' })}>{c.name}</Chip>
            ))}
            <Chip on={false} onClick={() => setEditing({ status: 'active', template: 'generic' })}>Other</Chip>
          </div>
        </Panel>
      )}
    </div>
  )
}

function ConditionForm({ personId, start, onDone }: { personId: string; start: Condition | ConditionInput; onDone: () => void }) {
  const id = 'id' in start ? start.id : null
  const [v, setV] = useState<ConditionInput>({
    name: start.name ?? '', template: start.template ?? 'generic', status: start.status ?? 'active', since: start.since ?? '',
    private: start.private ?? false, notes: start.notes ?? '',
  })
  const save = useMutation({
    mutationFn: () => {
      const body: ConditionInput = { ...v, name: v.name?.trim(), since: v.since || null, notes: v.notes?.trim() || null }
      return id ? vitaApi.updateCondition(personId, id, body) : vitaApi.addCondition(personId, body)
    },
    onSuccess: onDone,
  })
  return (
    <Panel title={id ? 'Edit condition' : 'Add a condition'}>
      <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Name" value={v.name} maxLength={120} onChange={name => setV({ ...v, name })} placeholder="Thyroid" />
          <SelectField label="Page" value={(v.template ?? 'generic') as ConditionTemplate} onChange={template => setV({ ...v, template })}
            options={Object.entries(TEMPLATES).map(([value, label]) => ({ value: value as ConditionTemplate, label }))}
            hint="Diabetes and blood pressure get their own pages with the next update." />
          <SelectField label="Status" value={(v.status ?? 'active') as ConditionStatus} onChange={status => setV({ ...v, status })}
            options={Object.entries(CONDITION_STATUS).map(([value, label]) => ({ value: value as ConditionStatus, label }))} />
          <TextField label="Since (optional)" type="date" value={v.since ?? ''} onChange={since => setV({ ...v, since })} />
          <TextField className="sm:col-span-2" label="Notes (optional)" value={v.notes ?? ''} maxLength={2000} onChange={notes => setV({ ...v, notes })} />
        </div>
        <CheckField label="Keep it off the emergency card" checked={v.private === true} onChange={p => setV({ ...v, private: p })} />
        <div className="flex gap-2">
          <Button size="sm" type="submit" loading={save.isPending} disabled={!v.name?.trim()}>Save</Button>
          <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
        </div>
        {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the condition.')}</p>}
      </form>
    </Panel>
  )
}
