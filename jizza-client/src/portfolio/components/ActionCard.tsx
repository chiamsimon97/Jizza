import { type CSSProperties, useState } from 'react'
import { CheckCircle2, Clock, ThumbsDown, TrendingUp } from 'lucide-react'
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

/** The number badge and the left accent per layer. */
const LAYER_ACCENT: Record<ActionLayer, { num: string; edge: string }> = {
  survival: { num: 'bg-gradient-to-br from-rose-400 to-rose-600 text-white', edge: 'before:bg-rose-500' },
  important: { num: 'bg-gradient-to-br from-brand-400 to-brand-600 text-white', edge: 'before:bg-brand-500' },
  aspirational: { num: 'bg-gradient-to-br from-cyan-400 to-cyan-600 text-white', edge: 'before:bg-cyan-500' },
  hygiene: { num: 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-200', edge: 'before:bg-gray-400' },
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
export function ActionCard({ action, index, style }: { action: FinAction; index?: number; style?: CSSProperties }) {
  const qc = useQueryClient()
  const sample = useSample()
  const [declining, setDeclining] = useState(false)
  const [reason, setReason] = useState('')
  const [celebrate, setCelebrate] = useState(false)
  const update = useMutation({
    mutationFn: (body: Parameters<typeof financeApi.updateAction>[1]) => financeApi.updateAction(action.id, body),
    onSuccess: (_, body) => {
      // Let "Done" show its tick for a moment before the card leaves the list.
      if (body.status === 'done') {
        setCelebrate(true)
        setTimeout(() => qc.invalidateQueries({ queryKey: financeKeys.all }), 700)
      } else qc.invalidateQueries({ queryKey: financeKeys.all })
    },
  })
  const live = ['open', 'later', 'snoozed'].includes(action.status)
  const accent = LAYER_ACCENT[action.layer]

  return (
    <article style={style}
      className={`group relative flex flex-col gap-2 overflow-hidden rounded-2xl border bg-white p-3.5 pl-4 shadow-sm transition-all duration-300 before:absolute before:inset-y-0 before:left-0 before:w-1 hover:shadow-md dark:bg-gray-900 ${accent.edge} ${celebrate
        ? 'border-emerald-300 bg-emerald-50/60 dark:border-emerald-700 dark:bg-emerald-900/20'
        : 'border-gray-200 dark:border-gray-800'}`}
      data-testid="action">
      <div className="flex flex-wrap items-start gap-2.5">
        {index != null && (
          <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold shadow-sm ${celebrate ? 'bg-emerald-500 text-white' : accent.num}`} aria-hidden>
            {celebrate ? <CheckCircle2 className="h-4 w-4 motion-safe:animate-fade-in" /> : index}
          </span>
        )}
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${LAYER_STYLES[action.layer]}`}>{LAYER_LABELS[action.layer]}</span>
            <h3 className="min-w-0 flex-1 text-sm font-semibold text-gray-900 dark:text-gray-100">
              <span className="sr-only">{index != null ? `${index}. ` : ''}</span>{action.title}
            </h3>
            {action.impactPoints != null && action.impactPoints > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700 ring-1 ring-inset ring-emerald-200 dark:bg-emerald-900/30 dark:text-emerald-300 dark:ring-emerald-800">
                <TrendingUp className="h-3 w-3" aria-hidden />+{action.impactPoints} points
              </span>
            )}
          </div>
          <p className="text-sm leading-relaxed text-gray-600 dark:text-gray-300">{action.reason}</p>
        </div>
      </div>
      {celebrate && <p className="text-sm font-medium text-emerald-700 dark:text-emerald-300" role="status">Nicely done. Kai counts it on the next check.</p>}
      {action.status === 'snoozed' && action.snoozedUntil && (
        <p className="text-xs text-gray-500">Snoozed until {action.snoozedUntil}</p>
      )}
      {live && !sample && !celebrate && (declining ? (
        <div className="flex flex-wrap items-center gap-2 motion-safe:animate-fade-in">
          <input className={`${fieldClass} !w-auto min-w-[12rem] flex-1 !py-1 text-sm`} maxLength={200} value={reason} autoFocus
            onChange={e => setReason(e.target.value)} placeholder="Why it isn't for you" aria-label="Why it isn't for you" />
          <Button size="sm" variant="secondary" disabled={!reason.trim()} loading={update.isPending}
            onClick={() => update.mutate({ status: 'dismissed', reason: reason.trim() })}>Not for me</Button>
          <Button size="sm" variant="ghost" onClick={() => setDeclining(false)}>Cancel</Button>
        </div>
      ) : (
        <div className="flex flex-wrap gap-1 pl-0 sm:pl-9">
          <Button size="sm" variant="secondary" loading={update.isPending && update.variables?.status === 'done'}
            onClick={() => update.mutate({ status: 'done' })}><CheckCircle2 className="h-4 w-4 text-emerald-600" aria-hidden />Done</Button>
          <Button size="sm" variant="ghost" loading={update.isPending && update.variables?.status === 'snoozed'}
            onClick={() => update.mutate({ status: 'snoozed', snoozedUntil: inDays(30) })}><Clock className="h-4 w-4" aria-hidden />Snooze 30 days</Button>
          <Button size="sm" variant="ghost" onClick={() => setDeclining(true)}><ThumbsDown className="h-4 w-4" aria-hidden />Not for me</Button>
        </div>
      ))}
      {update.error && <p className="text-sm text-red-600">{errorText(update.error, 'Could not save.')}</p>}
    </article>
  )
}
