import { useEffect, useState } from 'react'
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

type Pending = { kind: 'slot'; body: DoseAction } | { kind: 'dose'; id: string; body: DoseAction }

const offline = () => typeof navigator !== 'undefined' && navigator.onLine === false

/**
 * One time slot on Home (7.2): "All taken", Skip and Snooze for the whole slot, each medicine can be
 * ticked on its own, and a marked slot says who marked it. `large` is the bigger text of
 * "My medicines today" (7.20). A mark made with no network waits and goes when the network is back.
 */
export function DoseSlotCard({ personId, slot, readOnly, large = false, highlight = false }: {
  personId: string; slot: DoseSlot; readOnly: boolean; large?: boolean; highlight?: boolean
}) {
  const qc = useQueryClient()
  const me = useAuthStore(s => s.userId)
  const refresh = () => qc.invalidateQueries({ queryKey: vitaKeys.person(personId) })
  const all = useMutation({ mutationFn: (body: DoseAction) => vitaApi.actSlot(personId, slot.dueAt, body), onSuccess: refresh })
  const one = useMutation({ mutationFn: ({ id, body }: { id: string; body: DoseAction }) => vitaApi.actDose(id, body), onSuccess: refresh })
  const open = slot.doses.some(d => d.status === 'due' || d.status === 'snoozed' || d.status === 'missed')
  const who = slot.markedBy
    ? (slot.markedByYou ?? slot.markedBy === me ? 'you' : slot.markedByName && slot.markedByName !== 'You' ? slot.markedByName : 'someone else in the household')
    : null
  const error = all.error ?? one.error
  const [pending, setPending] = useState<Pending | null>(null)
  const send = (p: Pending) => {
    if (offline()) { setPending(p); return }
    setPending(null)
    if (p.kind === 'slot') all.mutate(p.body)
    else one.mutate({ id: p.id, body: p.body })
  }
  useEffect(() => {
    if (!pending) return
    const go = () => send(pending)
    window.addEventListener('online', go)
    return () => window.removeEventListener('online', go)
  })
  const text = large ? 'text-lg' : 'text-sm'

  return (
    <section aria-label={`Medicines at ${clock(slot.localTime)}`} id={`slot-${slot.dueAt}`}
      className={`flex flex-col gap-2 rounded-xl border bg-white dark:bg-gray-900 ${large ? 'p-4' : 'p-3'} ${highlight ? 'border-[rgb(var(--agent-health))] ring-2 ring-[rgb(var(--agent-health)/0.3)]' : 'border-gray-200 dark:border-gray-800'}`}>
      <header className="flex flex-wrap items-center gap-2">
        <h4 className={`font-mono ${large ? 'text-2xl' : 'text-base'} font-semibold text-gray-900 dark:text-gray-100`}>{clock(slot.localTime)}</h4>
        <DoseStatusPill status={slot.status} />
        {slot.status === 'snoozed' && slot.snoozedUntil && <span className="text-xs text-gray-500 dark:text-gray-400">until {formatWhen(slot.snoozedUntil).split(', ').pop()}</span>}
        {who && slot.markedAt && <span className={`ml-auto ${large ? 'text-sm' : 'text-xs'} text-gray-500 dark:text-gray-400`}>Marked by {who}, {formatWhen(slot.markedAt).split(', ').pop()}</span>}
      </header>
      <ul className="flex flex-col gap-1">
        {slot.doses.map(d => {
          const done = d.status === 'taken' || d.status === 'skipped'
          return (
            <li key={d.id} className={`flex flex-wrap items-center gap-2 ${text}`}>
              {!readOnly && (
                <button type="button" disabled={done || one.isPending} aria-pressed={d.status === 'taken'}
                  aria-label={d.status === 'taken' ? `${d.medicineName} taken` : `Mark ${d.medicineName} taken`}
                  onClick={() => send({ kind: 'dose', id: d.id, body: { action: 'taken' } })}
                  className={`flex ${large ? 'h-7 w-7' : 'h-5 w-5'} shrink-0 items-center justify-center rounded border ${d.status === 'taken' ? 'border-green-600 bg-green-600 text-white' : 'border-gray-400 bg-white dark:border-gray-600 dark:bg-gray-900'} disabled:cursor-default`}>
                  {d.status === 'taken' && <Check className="h-3.5 w-3.5" aria-hidden />}
                </button>
              )}
              <span className="font-medium text-gray-900 dark:text-gray-100">{d.medicineName}{d.strength ? ` ${d.strength}` : ''}</span>
              <span className={`${large ? 'text-sm' : 'text-xs'} text-gray-500 dark:text-gray-400`}>{doseWords(d.doseUnits, d.doseUnit)}{d.withFood && d.withFood !== 'any' ? `, ${WITH_FOOD[d.withFood]}` : ''}</span>
              {d.status !== 'due' && <DoseStatusPill status={d.status} late={d.late} />}
            </li>
          )
        })}
      </ul>
      {!readOnly && open && !pending && (
        <div className="flex flex-wrap gap-2">
          <Button size={large ? 'md' : 'sm'} loading={all.isPending && all.variables?.action === 'taken'} onClick={() => send({ kind: 'slot', body: { action: 'taken' } })}>All taken</Button>
          <Button size={large ? 'md' : 'sm'} variant="secondary" loading={all.isPending && all.variables?.action === 'skipped'} onClick={() => send({ kind: 'slot', body: { action: 'skipped' } })}>Skip</Button>
          {slot.status !== 'missed' && (
            <Button size={large ? 'md' : 'sm'} variant="ghost" loading={all.isPending && all.variables?.action === 'snoozed'} onClick={() => send({ kind: 'slot', body: { action: 'snoozed', snoozeMinutes: SNOOZE_MINUTES } })}>
              <AlarmClock className="h-3.5 w-3.5" aria-hidden />Snooze {SNOOZE_MINUTES} min
            </Button>
          )}
        </div>
      )}
      {pending && (
        <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
          Waiting to send: there is no network. It goes as soon as you are back online.
        </p>
      )}
      {error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(error, 'Could not mark that.')}</p>}
    </section>
  )
}
