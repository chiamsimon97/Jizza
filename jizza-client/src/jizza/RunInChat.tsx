import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpRight, Check, CircleDot, Hand, Loader2, Square, X } from 'lucide-react'
import { runsApi } from '@/api/runs'
import { notificationsApi } from '@/api/notifications'
import { NotificationItem } from '@/components/notifications/NotificationItem'
import { isOpen } from '@/components/notifications/inbox'
import { useInboxActions } from '@/components/notifications/useInboxActions'
import type { NotificationDto, RunStatus, RunTrace } from '@/types'
import { runLogPath } from './assistantStore'

const GOING: RunStatus[] = ['RUNNING', 'WAITING', 'PAUSED_WAITING_INSTRUCTION']
/** How many finished steps the progress line keeps in view. */
const SHOWN_DONE = 3

export interface Progress {
  done: string[]
  /** The step it is on now, or waiting at. */
  current?: string
  waitingForYou: boolean
  status: RunStatus
  error?: string
}

/** Where a run has got to, in the words of its steps: what is done, and what it is on now. */
export function progressOf(trace: RunTrace): Progress {
  const label = (id?: string) => trace.nodes.find(n => n.id === id)?.label || undefined
  const done = trace.steps.filter(s => s.status === 'DONE' && s.type !== 'TRIGGER' && s.type !== 'END')
    .map(s => s.label || label(s.nodeId) || 'A step')
  const status = trace.run.status
  const waitingForYou = status === 'PAUSED_WAITING_INSTRUCTION'
  const current = GOING.includes(status) ? label(trace.waitingOn) : undefined
  return { done, current, waitingForYou, status, error: trace.run.error }
}

/**
 * A run started from this chat, followed where it was asked for: a live line of its steps, and the
 * cards it raises (a choice from the page, a CAPTCHA, a code, a hand-over) right here, answered the
 * same as in Notifications. Stop ends it.
 */
export function RunInChat({ runId, name }: { runId: string; name: string }) {
  const qc = useQueryClient()
  const actions = useInboxActions()
  const [stopping, setStopping] = useState(false)
  const [stopError, setStopError] = useState('')
  const { data: trace } = useQuery({
    queryKey: ['run-trace', runId],
    queryFn: () => runsApi.trace(runId),
    refetchInterval: q => (q.state.data && !GOING.includes(q.state.data.run.status) ? false : 3_000),
  })
  const going = !trace || GOING.includes(trace.run.status)
  const { data: notifications } = useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.list(),
    refetchInterval: going ? 4_000 : 60_000,
  })
  const cards = useMemo(() => (notifications ?? []).filter((n: NotificationDto) => n.runId === runId && isOpen(n)), [notifications, runId])
  const progress = trace ? progressOf(trace) : null
  const now = Date.now()

  async function stop() {
    setStopping(true)
    setStopError('')
    try {
      await runsApi.stop(runId)
      void qc.invalidateQueries({ queryKey: ['run-trace', runId] })
      void qc.invalidateQueries({ queryKey: ['notifications'] })
    } catch (e) {
      setStopError(e instanceof Error ? e.message : 'Could not stop it')
    } finally {
      setStopping(false)
    }
  }

  return (
    <div data-testid="run-in-chat" className="rounded-2xl border border-gray-200 bg-white p-3 shadow-sm dark:border-gray-800 dark:bg-gray-900">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="inline-flex min-w-0 items-center gap-1.5 text-sm font-medium text-gray-900 dark:text-gray-100">
          <StatusIcon status={progress?.status} />
          <span className="truncate">{name}</span>
          <span className="text-xs font-normal text-gray-500 dark:text-gray-400">{statusWords(progress)}</span>
        </p>
        <div className="flex items-center gap-3 text-xs">
          {going && (
            <button type="button" onClick={() => void stop()} disabled={stopping}
              className="inline-flex items-center gap-1 font-medium text-gray-500 hover:text-red-600 disabled:opacity-50 dark:text-gray-400 dark:hover:text-red-400">
              <Square className="h-3 w-3" />{stopping ? 'Stopping…' : 'Stop'}
            </button>
          )}
          <Link to={runLogPath(runId)} className="inline-flex items-center gap-0.5 font-medium text-brand-600 hover:underline dark:text-brand-300">
            Run log <ArrowUpRight className="h-3 w-3" aria-hidden />
          </Link>
        </div>
      </div>
      {progress && (progress.done.length > 0 || progress.current) && (
        <ol className="mt-2 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-gray-600 dark:text-gray-300" aria-label="Progress">
          {progress.done.length > SHOWN_DONE && <li className="text-gray-400">{progress.done.length - SHOWN_DONE} earlier steps ·</li>}
          {progress.done.slice(-SHOWN_DONE).map((label, i) => (
            <li key={i} className="inline-flex items-center gap-1"><Check className="h-3 w-3 text-emerald-600 dark:text-emerald-400" />{label}<span className="text-gray-300 dark:text-gray-700">·</span></li>
          ))}
          {progress.current && (
            <li className="inline-flex items-center gap-1 font-medium text-gray-900 dark:text-gray-100">
              {progress.waitingForYou ? <Hand className="h-3 w-3 text-amber-600" /> : <Loader2 className="h-3 w-3 animate-spin text-brand-600" />}
              {progress.waitingForYou ? `Waiting for you: ${progress.current}` : progress.current}
            </li>
          )}
        </ol>
      )}
      {progress?.status === 'FAILED' && progress.error && (
        <p className="mt-2 text-xs text-red-600 dark:text-red-400">{progress.error}</p>
      )}
      {stopError && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{stopError}</p>}
      {cards.length > 0 && (
        <div className="mt-3 flex flex-col gap-2" data-testid="run-cards">
          {cards.map(n => (
            <NotificationItem key={n.id} n={n} now={now} actions={actions} focused={false}
              onFocus={() => {}} command={null} onCommandHandled={() => {}} />
          ))}
        </div>
      )}
    </div>
  )
}

function StatusIcon({ status }: { status?: RunStatus }) {
  if (!status || status === 'RUNNING' || status === 'WAITING') return <Loader2 className="h-3.5 w-3.5 animate-spin text-brand-600 dark:text-brand-300" />
  if (status === 'PAUSED_WAITING_INSTRUCTION') return <Hand className="h-3.5 w-3.5 text-amber-600" />
  if (status === 'DONE') return <Check className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />
  if (status === 'FAILED') return <X className="h-3.5 w-3.5 text-red-600" />
  return <CircleDot className="h-3.5 w-3.5 text-gray-400" />
}

function statusWords(p: Progress | null): string {
  if (!p) return 'Starting…'
  switch (p.status) {
    case 'RUNNING': return 'Running'
    case 'WAITING': return 'Waiting, carries on by itself'
    case 'PAUSED_WAITING_INSTRUCTION': return 'Waiting for you'
    case 'DONE': return 'Finished'
    case 'FAILED': return 'Failed'
    case 'CANCELLED': return 'Stopped'
  }
}
