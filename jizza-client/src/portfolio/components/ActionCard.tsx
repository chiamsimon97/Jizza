import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { Button } from '@/components/ui/Button'
import { fieldClass } from '@/components/ui/SlideOver'
import type { ActionLayer, FinAction } from '@/types/finance'
import { errorText, useSample } from '../data'
import { financeKeys } from '../queryKeys'

export const LAYER_LABELS: Record<ActionLayer, string> = {
  survival: 'Survival', important: 'Important', aspirational: 'Aspirational', hygiene: 'Hygiene',
}

const LAYER_STYLES: Record<ActionLayer, string> = {
  survival: 'bg-red-50 text-red-700 dark:bg-red-900/30 dark:text-red-300',
  important: 'bg-brand-50 text-brand-700 dark:bg-brand-900/30 dark:text-brand-200',
  aspirational: 'bg-cyan-50 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300',
  hygiene: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300',
}

/** A date a number of days from today, as the API takes it. */
export function inDays(days: number, today = new Date()): string {
  const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * One action from the plan (7.17): title, the reason with its number, the points it adds, and
 * Done, Snooze (30 days) or Not for me with a reason. Kai never acts on it; the user does.
 */
export function ActionCard({ action, index }: { action: FinAction; index?: number }) {
  const qc = useQueryClient()
  const sample = useSample()
  const [declining, setDeclining] = useState(false)
  const [reason, setReason] = useState('')
  const update = useMutation({
    mutationFn: (body: Parameters<typeof financeApi.updateAction>[1]) => financeApi.updateAction(action.id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: financeKeys.all }),
  })
  const live = ['open', 'later', 'snoozed'].includes(action.status)

  return (
    <article className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-gray-900" data-testid="action">
      <div className="flex flex-wrap items-start gap-2">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${LAYER_STYLES[action.layer]}`}>{LAYER_LABELS[action.layer]}</span>
        <h3 className="min-w-0 flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">
          {index != null ? `${index}. ` : ''}{action.title}
        </h3>
        {action.impactPoints != null && action.impactPoints > 0 && (
          <span className="rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-900/30 dark:text-green-300">
            +{action.impactPoints} points
          </span>
        )}
      </div>
      <p className="text-sm text-gray-600 dark:text-gray-300">{action.reason}</p>
      {action.status === 'snoozed' && action.snoozedUntil && (
        <p className="text-xs text-gray-500">Snoozed until {action.snoozedUntil}</p>
      )}
      {live && !sample && (declining ? (
        <div className="flex flex-wrap items-center gap-2">
          <input className={`${fieldClass} !w-auto min-w-[12rem] flex-1 !py-1 text-sm`} maxLength={200} value={reason}
            onChange={e => setReason(e.target.value)} placeholder="Why it isn't for you" aria-label="Why it isn't for you" />
          <Button size="sm" variant="secondary" disabled={!reason.trim()} loading={update.isPending}
            onClick={() => update.mutate({ status: 'dismissed', reason: reason.trim() })}>Not for me</Button>
          <Button size="sm" variant="ghost" onClick={() => setDeclining(false)}>Cancel</Button>
        </div>
      ) : (
        <div className="flex flex-wrap gap-1">
          <Button size="sm" variant="secondary" loading={update.isPending && update.variables?.status === 'done'}
            onClick={() => update.mutate({ status: 'done' })}>Done</Button>
          <Button size="sm" variant="ghost" loading={update.isPending && update.variables?.status === 'snoozed'}
            onClick={() => update.mutate({ status: 'snoozed', snoozedUntil: inDays(30) })}>Snooze 30 days</Button>
          <Button size="sm" variant="ghost" onClick={() => setDeclining(true)}>Not for me</Button>
        </div>
      ))}
      {update.error && <p className="text-sm text-red-600">{errorText(update.error, 'Could not save.')}</p>}
    </article>
  )
}
