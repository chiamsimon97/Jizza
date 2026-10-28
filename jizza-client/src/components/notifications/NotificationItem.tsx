import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  AlarmClock, Archive, ArchiveRestore, Bell, Check, ChevronDown, Clock, GitBranch, History,
  Info, ListTodo, Mail, MailOpen, MessageCircleQuestion, MoreHorizontal, ShieldCheck, Wrench, X, type LucideIcon,
} from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import type { NotificationDto, NotificationKind } from '@/types'
import {
  KIND_META, agentOf, ago, argsOf, detailsOf, fixLinkOf, isOpen, isSnoozed, outcomeOf, priorityOf, snoozeOptions, timeLeft,
} from './inbox'

const KIND_ICON: Record<NotificationKind, LucideIcon> = {
  PERMISSION_REQUEST: ShieldCheck,
  WORKFLOW_REVIEW: GitBranch,
  INSTRUCTION_REQUIRED: MessageCircleQuestion,
  INFO: Info,
}

/** Ready-made answers to a question, which the user can still edit before sending. */
const QUICK_REPLIES = ['Try again', 'Skip it this time', 'Stop and wait for me']

/** What the inbox can do to an item. Each returns once the server has answered. */
export interface InboxActions {
  approve: (n: NotificationDto) => Promise<unknown>
  approveAlways: (n: NotificationDto) => Promise<unknown>
  decline: (n: NotificationDto, reason: string) => Promise<unknown>
  reply: (n: NotificationDto, text: string) => Promise<unknown>
  markRead: (n: NotificationDto) => Promise<unknown>
  markUnread: (n: NotificationDto) => Promise<unknown>
  archive: (n: NotificationDto) => Promise<unknown>
  unarchive: (n: NotificationDto) => Promise<unknown>
  snooze: (n: NotificationDto, until: Date | null) => Promise<unknown>
}

/** A keyboard shortcut the page passes down to the focused item. */
export type ItemCommand = 'approve' | 'decline' | 'archive' | 'toggleRead' | null

interface Props {
  n: NotificationDto
  now: number
  actions: InboxActions
  focused: boolean
  onFocus: () => void
  command: ItemCommand
  onCommandHandled: () => void
}

type Mode = 'idle' | 'declining' | 'confirmAlways'

export function NotificationItem({ n, now, actions, focused, onFocus, command, onCommandHandled }: Props) {
  const [mode, setMode] = useState<Mode>('idle')
  const [text, setText] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showInputs, setShowInputs] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const open = isOpen(n)
  const priority = priorityOf(n)
  const Icon = KIND_ICON[n.kind]
  const left = open ? timeLeft(n.expiresAt, now) : null
  const outcome = outcomeOf(n)
  const details = detailsOf(n)
  const inputs = argsOf(n)
  const fixLink = fixLinkOf(n)
  const unread = n.status === 'UNREAD'
  const tool = typeof n.context?.tool === 'string' ? n.context.tool : null
  const canAlwaysAllow = open && n.kind === 'PERMISSION_REQUEST' && !!tool && !!n.sourceAgentId

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key)
    setError(null)
    try {
      await action()
      setMode('idle')
      setText('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That did not work. Try again.')
    } finally {
      setBusy(null)
    }
  }

  useEffect(() => {
    if (focused) ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [focused])

  // Keyboard shortcuts from the page land on whichever item is focused.
  useEffect(() => {
    if (!command) return
    onCommandHandled()
    if (command === 'approve' && open && n.kind !== 'INSTRUCTION_REQUIRED') void run('approve', () => actions.approve(n))
    if (command === 'decline' && open && n.kind !== 'INSTRUCTION_REQUIRED') setMode('declining')
    if (command === 'archive' && n.kind === 'INFO') void run('archive', () => (n.archivedAt ? actions.unarchive(n) : actions.archive(n)))
    if (command === 'toggleRead') void run('read', () => (unread ? actions.markRead(n) : actions.markUnread(n)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [command])

  const accent = !open ? 'before:bg-transparent'
    : priority === 'URGENT' ? 'before:bg-red-500'
    : priority === 'LOW' ? 'before:bg-gray-300 dark:before:bg-gray-700'
    : 'before:bg-brand-500'

  return (
    <div
      ref={ref}
      tabIndex={-1}
      onClick={() => { onFocus(); if (unread && n.kind === 'INFO') void actions.markRead(n) }}
      className={`relative overflow-hidden rounded-xl border bg-white p-4 pl-5 shadow-sm transition-all duration-150 outline-none dark:bg-gray-900
        before:absolute before:inset-y-0 before:left-0 before:w-1 ${accent}
        ${focused ? 'border-brand-400 ring-2 ring-brand-500/20 dark:border-brand-600' : 'border-gray-200 hover:border-gray-300 dark:border-gray-800 dark:hover:border-gray-700'}
        ${!open && !unread ? 'opacity-80' : ''}`}
    >
      <div className="flex items-start gap-3">
        <div className={`mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-lg ${
          priority === 'URGENT' && open ? 'bg-red-100 text-red-600 dark:bg-red-500/15 dark:text-red-400'
            : open ? 'bg-brand-100/70 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300'
            : 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400'
        }`}>
          <Icon className="h-[18px] w-[18px]" />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
            {unread && <span className="h-2 w-2 rounded-full bg-brand-500" aria-label="Unread" />}
            <span className="font-semibold text-gray-700 dark:text-gray-200">{KIND_META[n.kind].label}</span>
            {open && priority === 'URGENT' && <Badge variant="danger">Urgent</Badge>}
            {open && priority === 'LOW' && <Badge>Low</Badge>}
            <span className="text-gray-400">·</span>
            <span className="text-gray-500 dark:text-gray-400">from {agentOf(n)}</span>
            <span className="text-gray-400">·</span>
            <span className="text-gray-500 dark:text-gray-400" title={new Date(n.createdAt).toLocaleString()}>{ago(n.createdAt, now)}</span>
            {left && (
              <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium ${
                left.soon ? 'bg-red-50 text-red-600 dark:bg-red-500/10 dark:text-red-400' : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300'
              }`} title={n.expiresAt ? new Date(n.expiresAt).toLocaleString() : undefined}>
                <Clock className="h-3 w-3" />{left.text}
              </span>
            )}
            {isSnoozed(n, now) && n.snoozedUntil && (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 font-medium text-amber-700 dark:bg-amber-500/10 dark:text-amber-400">
                <AlarmClock className="h-3 w-3" />Back {new Date(n.snoozedUntil).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}
              </span>
            )}
          </div>

          {n.body && <p className={`mt-1.5 text-sm leading-relaxed ${unread || open ? 'font-medium text-gray-900 dark:text-gray-100' : 'text-gray-700 dark:text-gray-300'}`}>{n.body}</p>}

          {details.length > 0 && n.kind !== 'INFO' && (
            <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 rounded-lg bg-gray-50 px-3 py-2 text-xs dark:bg-gray-800/50">
              {details.map(d => (
                <div key={d.label} className="contents">
                  <dt className="text-gray-500 dark:text-gray-400">{d.label}</dt>
                  <dd className="min-w-0 break-words font-medium text-gray-800 dark:text-gray-200">{d.value}</dd>
                </div>
              ))}
            </dl>
          )}

          {inputs && (
            <div className="mt-2">
              <button
                onClick={e => { e.stopPropagation(); setShowInputs(v => !v) }}
                className="inline-flex items-center gap-1 text-xs font-medium text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200"
              >
                <ChevronDown className={`h-3.5 w-3.5 transition-transform ${showInputs ? 'rotate-180' : ''}`} />
                {showInputs ? 'Hide what it will send' : 'Show what it will send'}
              </button>
              {showInputs && (
                <pre className="mt-1.5 max-h-60 overflow-auto rounded-lg bg-gray-900 p-3 text-xs text-gray-100 dark:bg-black/40">{inputs}</pre>
              )}
            </div>
          )}

          {(n.runId || n.workflowId || n.taskId) && (
            <div className="mt-2 flex flex-wrap gap-3 text-xs">
              {n.runId && <Link to={`/runs?run=${n.runId}`} className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400"><History className="h-3.5 w-3.5" />Open the run</Link>}
              {n.workflowId && n.kind === 'WORKFLOW_REVIEW' && <Link to="/procedures" className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400"><GitBranch className="h-3.5 w-3.5" />See it in Procedures</Link>}
              {fixLink && <Link to={fixLink} className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400"><Wrench className="h-3.5 w-3.5" />Review fix</Link>}
              {n.workflowId && n.kind !== 'WORKFLOW_REVIEW' && !fixLink && <Link to={`/workflows?open=${n.workflowId}`} className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400"><GitBranch className="h-3.5 w-3.5" />Open the workflow</Link>}
              {n.taskId && <Link to="/tasks" className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline dark:text-brand-400"><ListTodo className="h-3.5 w-3.5" />Open tasks</Link>}
            </div>
          )}

          {outcome && (
            <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
              <Badge variant={outcome.tone}>{outcome.text}</Badge>
              {n.answeredAt && <span className="text-gray-500 dark:text-gray-400">{ago(n.answeredAt, now)}</span>}
              {n.responseNote && <span className="text-gray-600 dark:text-gray-300">“{n.responseNote}”</span>}
              {n.submittedInstruction && <span className="text-gray-600 dark:text-gray-300">“{n.submittedInstruction}”</span>}
            </div>
          )}

          {/* Answering */}
          <div onClick={e => e.stopPropagation()}>
            {open && n.kind === 'INSTRUCTION_REQUIRED' && (
              <div className="mt-3 space-y-2">
                <div className="flex flex-wrap gap-1.5">
                  {QUICK_REPLIES.map(q => (
                    <button key={q} onClick={() => setText(q)}
                      className="rounded-full border border-gray-200 px-2.5 py-1 text-xs text-gray-600 hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:text-gray-300 dark:hover:border-brand-700 dark:hover:text-brand-300">
                      {q}
                    </button>
                  ))}
                </div>
                <textarea
                  value={text}
                  onChange={e => setText(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && text.trim()) void run('reply', () => actions.reply(n, text.trim())) }}
                  rows={2}
                  placeholder="Tell Jizza how to carry on…"
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
                />
              </div>
            )}

            {open && n.kind !== 'INSTRUCTION_REQUIRED' && mode === 'declining' && (
              <div className="mt-3 space-y-2 rounded-lg border border-red-200 bg-red-50/50 p-3 dark:border-red-900/50 dark:bg-red-500/5">
                <label className="text-xs font-medium text-gray-700 dark:text-gray-300">
                  {n.kind === 'WORKFLOW_REVIEW' ? 'What should change?' : 'Why not? (optional, Jizza will see this)'}
                </label>
                <textarea
                  autoFocus
                  value={text}
                  onChange={e => setText(e.target.value)}
                  rows={2}
                  placeholder={n.kind === 'WORKFLOW_REVIEW' ? 'e.g. Ask me before the payment step' : 'e.g. Wrong amount, check with me first'}
                  className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-red-400 focus:outline-none focus:ring-4 focus:ring-red-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
                />
                <div className="flex justify-end gap-2">
                  <Button size="sm" variant="ghost" onClick={() => { setMode('idle'); setText('') }}>Cancel</Button>
                  <Button size="sm" variant="danger" loading={busy === 'decline'}
                    disabled={n.kind === 'WORKFLOW_REVIEW' && !text.trim()}
                    onClick={() => void run('decline', () => actions.decline(n, text.trim()))}>
                    {n.kind === 'WORKFLOW_REVIEW' ? 'Send back for changes' : 'Decline'}
                  </Button>
                </div>
              </div>
            )}

            {canAlwaysAllow && mode === 'confirmAlways' && (
              <div className="mt-3 space-y-2 rounded-lg border border-amber-200 bg-amber-50/60 p-3 text-sm dark:border-amber-900/50 dark:bg-amber-500/5">
                <p className="text-gray-700 dark:text-gray-300">
                  From now on {agentOf(n)} runs <span className="font-mono text-xs">{tool}</span> without asking. You can change this any time in{' '}
                  <Link to="/guardrails" className="font-medium text-brand-600 hover:underline dark:text-brand-400">Guardrails</Link>.
                </p>
                <div className="flex justify-end gap-2">
                  <Button size="sm" variant="ghost" onClick={() => setMode('idle')}>Cancel</Button>
                  <Button size="sm" loading={busy === 'always'} onClick={() => void run('always', () => actions.approveAlways(n))}>
                    Approve and always allow
                  </Button>
                </div>
              </div>
            )}

            {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}

            {mode === 'idle' && <div className="mt-3 flex flex-wrap items-center gap-2">
              {open && n.kind === 'INSTRUCTION_REQUIRED' && (
                <Button size="sm" disabled={!text.trim()} loading={busy === 'reply'} onClick={() => void run('reply', () => actions.reply(n, text.trim()))}>
                  Send answer
                </Button>
              )}
              {open && n.kind !== 'INSTRUCTION_REQUIRED' && (
                <>
                  <Button size="sm" loading={busy === 'approve'} disabled={busy !== null} onClick={() => void run('approve', () => actions.approve(n))}>
                    <Check className="h-4 w-4" />{n.kind === 'WORKFLOW_REVIEW' ? 'Approve procedure' : 'Approve'}
                  </Button>
                  <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => setMode('declining')}>
                    <X className="h-4 w-4" />{n.kind === 'WORKFLOW_REVIEW' ? 'Request changes' : 'Decline'}
                  </Button>
                  {canAlwaysAllow && (
                    <Button size="sm" variant="ghost" disabled={busy !== null} onClick={() => setMode('confirmAlways')}>
                      Always allow
                    </Button>
                  )}
                </>
              )}
              {!open && n.kind === 'INFO' && unread && (
                <Button size="sm" variant="secondary" loading={busy === 'read'} onClick={() => void run('read', () => actions.markRead(n))}>
                  <MailOpen className="h-4 w-4" />Mark read
                </Button>
              )}
              {n.kind === 'INFO' && !n.archivedAt && (
                <Button size="sm" variant="ghost" loading={busy === 'archive'} onClick={() => void run('archive', () => actions.archive(n))}>
                  <Archive className="h-4 w-4" />Archive
                </Button>
              )}
              {n.archivedAt && (
                <Button size="sm" variant="ghost" loading={busy === 'archive'} onClick={() => void run('archive', () => actions.unarchive(n))}>
                  <ArchiveRestore className="h-4 w-4" />Move back to inbox
                </Button>
              )}
              <div className="ml-auto">
                <ItemMenu n={n} now={now} disabled={busy !== null}
                  onSnooze={until => void run('snooze', () => actions.snooze(n, until))}
                  onToggleRead={() => void run('read', () => (unread ? actions.markRead(n) : actions.markUnread(n)))} />
              </div>
            </div>}
          </div>
        </div>
      </div>
    </div>
  )
}

function ItemMenu({ n, now, disabled, onSnooze, onToggleRead }: {
  n: NotificationDto; now: number; disabled: boolean
  onSnooze: (until: Date | null) => void; onToggleRead: () => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const answered = n.kind !== 'INFO' && !isOpen(n)
  const options = answered || n.archivedAt ? [] : snoozeOptions(n, now)
  const snoozed = isSnoozed(n, now)
  const unread = n.status === 'UNREAD'
  const item = 'flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'

  return (
    <div ref={ref} className="relative">
      <button
        disabled={disabled}
        onClick={() => setOpen(o => !o)}
        aria-label="More actions"
        className="grid h-8 w-8 place-items-center rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-900 disabled:opacity-50 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100"
      >
        <MoreHorizontal className="h-4 w-4" />
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-1 w-56 overflow-hidden rounded-xl border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-800 dark:bg-gray-900">
          {(unread || n.status === 'READ') && (
            <button className={item} onClick={() => { setOpen(false); onToggleRead() }}>
              {unread ? <MailOpen className="h-4 w-4" /> : <Mail className="h-4 w-4" />}
              {unread ? 'Mark as read' : 'Mark as unread'}
            </button>
          )}
          {snoozed && (
            <button className={item} onClick={() => { setOpen(false); onSnooze(null) }}>
              <Bell className="h-4 w-4" />Unsnooze now
            </button>
          )}
          {options.length > 0 && (
            <>
              <p className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">Snooze</p>
              {options.map(o => (
                <button key={o.label} className={item} onClick={() => { setOpen(false); onSnooze(o.until) }}>
                  <AlarmClock className="h-4 w-4" />{o.label}
                </button>
              ))}
            </>
          )}
          {isOpen(n) && options.length === 0 && !snoozed && (
            <p className="px-3 py-2 text-xs text-gray-500">This expires too soon to snooze.</p>
          )}
        </div>
      )}
    </div>
  )
}
