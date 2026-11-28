import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlarmClock, Check, CheckCircle2, CircleDashed, Clock, SkipForward, XCircle } from 'lucide-react'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { Pill } from '@/portfolio/components/Panel'
import { useAuthStore } from '@/store/authStore'
import type { DoseAction, DoseSlot, DoseStatus } from '@/types/vita'
import { errorText, formatWhen } from '../data'
import { DOSE_STATUS, WITH_FOOD, clock, doseWords } from '../medicines'
import { vitaKeys } from '../queryKeys'

const ICON = { due: CircleDashed, taken: CheckCircle2, skipped: SkipForward, missed: XCircle, partial: Clock, snoozed: AlarmClock }
const TONE = { due: 'neutral', taken: 'ok', skipped: 'neutral', missed: 'bad', partial: 'warn', snoozed: 'info' } as const

export function DoseStatusPill({ status, late }: { status: DoseStatus; late?: boolean }) {
  const Icon = ICON[status]
  return <Pill tone={TONE[status]}><Icon className="h-3 w-3" aria-hidden />{DOSE_STATUS[status]}{late ? ', late' : ''}</Pill>
}

const SNOOZE_MINUTES = 30

/**
 * One time slot on Home (7.2): "All taken", Skip and Snooze for the whole slot, each medicine can be
 * ticked on its own, and a marked slot says who marked it.
 */
export function DoseSlotCard({ personId, slot, readOnly }: { personId: string; slot: DoseSlot; readOnly: boolean }) {
  const qc = useQueryClient()
  const me = useAuthStore(s => s.userId)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const all = useMutation({ mutationFn: (body: DoseAction) => vitaApi.actSlot(personId, slot.dueAt, body), onSuccess: refresh })
  const one = useMutation({ mutationFn: ({ id, body }: { id: string; body: DoseAction }) => vitaApi.actDose(id, body), onSuccess: refresh })
  const open = slot.doses.some(d => d.status === 'due' || d.status === 'snoozed' || d.status === 'missed')
  const who = slot.markedBy ? (slot.markedByYou ?? slot.markedBy === me ? 'you' : 'someone else in the household') : null
  const error = all.error ?? one.error

  return (
    <section aria-label={`Medicines at ${clock(slot.localTime)}`} className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-gray-900">
      <header className="flex flex-wrap items-center gap-2">
        <h4 className="font-mono text-base font-semibold text-gray-900 dark:text-gray-100">{clock(slot.localTime)}</h4>
        <DoseStatusPill status={slot.status} />
        {slot.status === 'snoozed' && slot.snoozedUntil && <span className="text-xs text-gray-500 dark:text-gray-400">until {formatWhen(slot.snoozedUntil).split(', ').pop()}</span>}
        {who && slot.markedAt && <span className="ml-auto text-xs text-gray-500 dark:text-gray-400">Marked by {who}, {formatWhen(slot.markedAt).split(', ').pop()}</span>}
      </header>
      <ul className="flex flex-col gap-1">
        {slot.doses.map(d => {
          const done = d.status === 'taken' || d.status === 'skipped'
          return (
            <li key={d.id} className="flex flex-wrap items-center gap-2 text-sm">
              {!readOnly && (
                <button type="button" disabled={done || one.isPending} aria-pressed={d.status === 'taken'}
                  aria-label={d.status === 'taken' ? `${d.medicineName} taken` : `Mark ${d.medicineName} taken`}
                  onClick={() => one.mutate({ id: d.id, body: { action: 'taken' } })}
                  className={`flex h-5 w-5 shrink-0 items-center justify-center rounded border ${d.status === 'taken' ? 'border-green-600 bg-green-600 text-white' : 'border-gray-400 bg-white dark:border-gray-600 dark:bg-gray-900'} disabled:cursor-default`}>
                  {d.status === 'taken' && <Check className="h-3.5 w-3.5" aria-hidden />}
                </button>
              )}
              <span className="font-medium text-gray-900 dark:text-gray-100">{d.medicineName}{d.strength ? ` ${d.strength}` : ''}</span>
              <span className="text-xs text-gray-500 dark:text-gray-400">{doseWords(d.doseUnits, d.doseUnit)}{d.withFood && d.withFood !== 'any' ? `, ${WITH_FOOD[d.withFood]}` : ''}</span>
              {d.status !== 'due' && <DoseStatusPill status={d.status} late={d.late} />}
            </li>
          )
        })}
      </ul>
      {!readOnly && open && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" loading={all.isPending && all.variables?.action === 'taken'} onClick={() => all.mutate({ action: 'taken' })}>All taken</Button>
          <Button size="sm" variant="secondary" loading={all.isPending && all.variables?.action === 'skipped'} onClick={() => all.mutate({ action: 'skipped' })}>Skip</Button>
          {slot.status !== 'missed' && (
            <Button size="sm" variant="ghost" loading={all.isPending && all.variables?.action === 'snoozed'} onClick={() => all.mutate({ action: 'snoozed', snoozeMinutes: SNOOZE_MINUTES })}>
              <AlarmClock className="h-3.5 w-3.5" aria-hidden />Snooze {SNOOZE_MINUTES} min
            </Button>
          )}
        </div>
      )}
      {error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(error, 'Could not mark that.')}</p>}
    </section>
  )
}
