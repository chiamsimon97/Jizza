import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ClipboardCheck, Pencil, Trash2 } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel, Pill } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { CheckInput, CheckKind, VitaCheck } from '@/types/vita'
import { errorText } from '../data'
import { CHECK_KINDS } from '../labs'
import { vitaKeys } from '../queryKeys'

export function checkName(c: Pick<VitaCheck, 'kind' | 'customName'>): string {
  return c.kind === 'custom' ? c.customName || CHECK_KINDS.custom : CHECK_KINDS[c.kind]
}

/**
 * Checks (7.10): eye, foot and other checks with when each was done and the next date the doctor
 * gave. A check's "due" on a condition page comes from that date or from a configured interval.
 */
export function Checks({ personId, readOnly, conditionId, adding: startAdding = false, onAddDone }: {
  personId: string
  readOnly: boolean
  /** Shows only this condition's checks, and new ones are linked to it. */
  conditionId?: string | null
  adding?: boolean
  onAddDone?: () => void
}) {
  const qc = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.checks(personId), queryFn: () => vitaApi.checks(personId) })
  const conditions = useQuery({ queryKey: vitaKeys.conditions(personId), queryFn: () => vitaApi.conditions(personId) })
  const [editing, setEditing] = useState<VitaCheck | null>(null)
  const [adding, setAdding] = useState(false)
  const open = adding || startAdding
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const remove = useMutation({ mutationFn: (id: string) => vitaApi.removeCheck(personId, id), onSuccess: refresh })
  const rows = (data ?? []).filter(c => !conditionId || c.conditionId === conditionId)
    .sort((a, b) => (b.doneOn ?? b.nextDueOn ?? '').localeCompare(a.doneOn ?? a.nextDueOn ?? ''))
  const conditionName = (id: string | null) => conditions.data?.find(c => c.id === id)?.name
  const close = () => { setAdding(false); setEditing(null); onAddDone?.(); refresh() }

  return (
    <Panel title={<><ClipboardCheck className="h-4 w-4 text-gray-400" aria-hidden />Checks</>}>
      {isLoading && <SkeletonRows rows={2} />}
      {error && <LoadFailed text="Could not load the checks." onRetry={() => refetch()} />}
      {data && !rows.length && !open && <p className="text-sm text-gray-500 dark:text-gray-400">No checks recorded. Record an eye, foot or dental check when it is done, or the date the doctor gave for the next one.</p>}
      {(open || editing) && (
        <CheckForm personId={personId} start={editing} conditionId={conditionId ?? null}
          conditions={(conditions.data ?? []).map(c => ({ id: c.id, name: c.name }))} onDone={close} />
      )}
      {rows.length > 0 && (
        <ul className="flex flex-col divide-y divide-gray-100 dark:divide-gray-800">
          {rows.map(c => (
            <li key={c.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2">
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{checkName(c)}</span>
                <span className="text-xs text-gray-600 dark:text-gray-300">
                  {[c.doneOn ? `Done ${formatDay(c.doneOn)}` : 'Not done yet', c.nextDueOn ? `next by ${formatDay(c.nextDueOn)} (the doctor’s date)` : null,
                    !conditionId ? conditionName(c.conditionId) : null].filter(Boolean).join(' · ')}
                </span>
                {c.resultNote && <span className="text-xs text-gray-500 dark:text-gray-400">{c.resultNote}</span>}
              </div>
              {c.nextDueOn && c.nextDueOn <= new Date().toISOString().slice(0, 10) && <Pill tone="warn">Due</Pill>}
              {!readOnly && (
                <span className="flex gap-1">
                  <Button size="sm" variant="ghost" aria-label={`Edit ${checkName(c)}`} onClick={() => { setAdding(false); setEditing(c) }}><Pencil className="h-3.5 w-3.5" aria-hidden /></Button>
                  <Button size="sm" variant="ghost" aria-label={`Delete ${checkName(c)}`} onClick={() => remove.mutate(c.id)}><Trash2 className="h-3.5 w-3.5" aria-hidden /></Button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {!readOnly && !open && !editing && <div><Button size="sm" variant="secondary" onClick={() => setAdding(true)}>Record a check</Button></div>}
      {remove.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(remove.error, 'Could not delete the check.')}</p>}
    </Panel>
  )
}

function CheckForm({ personId, start, conditionId, conditions, onDone }: {
  personId: string; start: VitaCheck | null; conditionId: string | null; conditions: { id: string; name: string }[]; onDone: () => void
}) {
  const [v, setV] = useState({
    kind: (start?.kind ?? 'eye') as CheckKind, customName: start?.customName ?? '', conditionId: start?.conditionId ?? conditionId ?? '',
    doneOn: start?.doneOn ?? '', nextDueOn: start?.nextDueOn ?? '', resultNote: start?.resultNote ?? '',
  })
  const body: CheckInput = {
    kind: v.kind, customName: v.kind === 'custom' ? v.customName.trim() || null : null, conditionId: v.conditionId || null,
    doneOn: v.doneOn || null, nextDueOn: v.nextDueOn || null, resultNote: v.resultNote.trim() || null,
  }
  const save = useMutation({
    mutationFn: () => (start ? vitaApi.updateCheck(personId, start.id, body) : vitaApi.addCheck(personId, body)),
    onSuccess: onDone,
  })
  const missingDate = !v.doneOn && !v.nextDueOn
  const missingName = v.kind === 'custom' && !v.customName.trim()
  return (
    <form aria-label={start ? 'Edit the check' : 'Record a check'} className="flex flex-col gap-3 rounded-xl bg-gray-50 p-3 dark:bg-gray-800/50" onSubmit={e => { e.preventDefault(); save.mutate() }}>
      <div className="grid gap-3 sm:grid-cols-2">
        <SelectField label="Check" value={v.kind} onChange={kind => setV({ ...v, kind })}
          options={(Object.keys(CHECK_KINDS) as CheckKind[]).map(k => ({ value: k, label: CHECK_KINDS[k] }))} />
        {v.kind === 'custom' && <TextField label="Name of the check" value={v.customName} maxLength={120} onChange={customName => setV({ ...v, customName })} />}
        {!conditionId && (
          <SelectField label="For the condition (optional)" value={v.conditionId} onChange={c => setV({ ...v, conditionId: c })}
            options={[{ value: '', label: 'Not linked' }, ...conditions.map(c => ({ value: c.id, label: c.name }))]} />
        )}
        <TextField label="Done on" type="date" value={v.doneOn} onChange={doneOn => setV({ ...v, doneOn })} />
        <TextField label="Next by (the doctor’s date, optional)" type="date" value={v.nextDueOn} onChange={nextDueOn => setV({ ...v, nextDueOn })} />
        <TextField label="Result or note (optional)" value={v.resultNote} maxLength={500} onChange={resultNote => setV({ ...v, resultNote })} />
      </div>
      {missingDate && <p className="text-xs text-gray-600 dark:text-gray-300">Give the date it was done, or the date the doctor gave for it.</p>}
      <div className="flex gap-2">
        <Button size="sm" type="submit" disabled={missingDate || missingName} loading={save.isPending}>Save check</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onDone}>Cancel</Button>
      </div>
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the check.')}</p>}
    </form>
  )
}
