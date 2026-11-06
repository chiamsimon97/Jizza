import type { NotificationDto, NotificationKind, NotificationPriority } from '@/types'

/**
 * The Notifications page as an inbox, like Linear's or GitHub's: what is waiting on the user comes
 * first, most urgent and soonest to expire at the top; updates follow; what is snoozed, answered or
 * archived is kept out of the way but one tab away.
 */
export type InboxView = 'needs_you' | 'updates' | 'snoozed' | 'done'

export const VIEWS: { id: InboxView; label: string; empty: string }[] = [
  { id: 'needs_you', label: 'Needs you', empty: 'Nothing is waiting on you. Jizza will ask here when it needs a decision.' },
  { id: 'updates', label: 'Updates', empty: 'No updates yet.' },
  { id: 'snoozed', label: 'Snoozed', empty: 'Nothing snoozed.' },
  { id: 'done', label: 'History', empty: 'Answered, expired and archived items show up here.' },
]

export interface KindMeta {
  label: string
  /** What the filter chip says. */
  plural: string
}

export const KIND_META: Record<NotificationKind, KindMeta> = {
  PERMISSION_REQUEST: { label: 'Approval', plural: 'Approvals' },
  WORKFLOW_REVIEW: { label: 'Suggested procedure', plural: 'Suggestions' },
  INSTRUCTION_REQUIRED: { label: 'Question', plural: 'Questions' },
  INFO: { label: 'Update', plural: 'Updates' },
}

const PRIORITY_RANK: Record<NotificationPriority, number> = { URGENT: 0, NORMAL: 1, LOW: 2 }

export function priorityOf(n: NotificationDto): NotificationPriority {
  return n.priority ?? 'NORMAL'
}

/** Still waiting on the user: a request nobody has answered and that has not expired. */
export function isOpen(n: NotificationDto): boolean {
  return n.kind !== 'INFO' && (n.status === 'UNREAD' || n.status === 'READ')
}

/**
 * The answers to a failed step that act on the run by themselves. The server recognises these exact
 * words, from the inbox, the phone app or the run view alike.
 */
export const RUN_CHOICES: { answer: string; explain: string; tone: 'primary' | 'secondary' | 'danger' }[] = [
  { answer: 'Try again', explain: 'Try again runs the step once more.', tone: 'primary' },
  { answer: 'Skip this step', explain: 'Skip this step carries on without it.', tone: 'secondary' },
  { answer: 'Stop the run', explain: 'Stop the run ends it now; steps already done stay done.', tone: 'danger' },
]

/** A question raised because a workflow step failed, which the run choices can answer. */
export function isFailedStep(n: NotificationDto): boolean {
  if (n.kind !== 'INSTRUCTION_REQUIRED' || !n.runId) return false
  if (n.context?.failedStep === true) return true
  // Asked before the server marked these questions.
  return /could not complete:/.test(n.body ?? '')
}

export function isSnoozed(n: NotificationDto, now: number): boolean {
  return !!n.snoozedUntil && Date.parse(n.snoozedUntil) > now
}

export function viewOf(n: NotificationDto, now: number): InboxView {
  if (n.archivedAt) return 'done'
  if (isSnoozed(n, now)) return 'snoozed'
  if (isOpen(n)) return 'needs_you'
  if (n.kind === 'INFO') return 'updates'
  return 'done'
}

const time = (iso?: string | null) => (iso ? Date.parse(iso) : NaN)

/**
 * The priority queue. Waiting items: most urgent first, then whichever expires soonest, then the
 * newest. Updates: unread first, then newest. Everything else: most recent activity first.
 */
export function compareFor(view: InboxView) {
  return (a: NotificationDto, b: NotificationDto): number => {
    if (view === 'needs_you') {
      const p = PRIORITY_RANK[priorityOf(a)] - PRIORITY_RANK[priorityOf(b)]
      if (p !== 0) return p
      const ea = time(a.expiresAt), eb = time(b.expiresAt)
      if (!isNaN(ea) || !isNaN(eb)) {
        if (isNaN(ea)) return 1
        if (isNaN(eb)) return -1
        if (ea !== eb) return ea - eb
      }
    }
    if (view === 'updates') {
      const ua = a.status === 'UNREAD' ? 0 : 1, ub = b.status === 'UNREAD' ? 0 : 1
      if (ua !== ub) return ua - ub
    }
    if (view === 'snoozed') {
      return time(a.snoozedUntil) - time(b.snoozedUntil)
    }
    const la = view === 'done' ? time(a.archivedAt ?? a.answeredAt ?? a.createdAt) : time(a.createdAt)
    const lb = view === 'done' ? time(b.archivedAt ?? b.answeredAt ?? b.createdAt) : time(b.createdAt)
    return lb - la
  }
}

export interface InboxFilters {
  q: string
  kinds: NotificationKind[]
  agent: string | null
  urgentOnly: boolean
  unreadOnly: boolean
}

export const NO_FILTERS: InboxFilters = { q: '', kinds: [], agent: null, urgentOnly: false, unreadOnly: false }

export function agentOf(n: NotificationDto): string {
  return n.sourceAgentName ?? 'Jizza'
}

export function matches(n: NotificationDto, f: InboxFilters): boolean {
  if (f.kinds.length > 0 && !f.kinds.includes(n.kind)) return false
  if (f.agent && agentOf(n) !== f.agent) return false
  if (f.urgentOnly && priorityOf(n) !== 'URGENT') return false
  if (f.unreadOnly && n.status !== 'UNREAD') return false
  const q = f.q.trim().toLowerCase()
  if (q) {
    const ctx = n.context ?? {}
    const hay = [n.body, agentOf(n), KIND_META[n.kind].label, ctx.tool, ctx.reason, ctx.what, ctx.why]
      .filter(v => typeof v === 'string').join(' ').toLowerCase()
    if (!hay.includes(q)) return false
  }
  return true
}

export interface InboxSection {
  title: string | null
  items: NotificationDto[]
}

/** The items for one tab, filtered, sorted and, when waiting on the user, grouped by priority. */
export function buildInbox(all: NotificationDto[], view: InboxView, f: InboxFilters, now: number): InboxSection[] {
  const items = all.filter(n => viewOf(n, now) === view && matches(n, f)).sort(compareFor(view))
  if (view !== 'needs_you') return items.length ? [{ title: null, items }] : []
  const groups: [string, NotificationPriority][] = [['Urgent', 'URGENT'], ['Waiting on you', 'NORMAL'], ['When you have a moment', 'LOW']]
  return groups
    .map(([title, p]) => ({ title, items: items.filter(n => priorityOf(n) === p) }))
    .filter(s => s.items.length > 0)
}

export function countsByView(all: NotificationDto[], now: number): Record<InboxView, number> {
  const counts: Record<InboxView, number> = { needs_you: 0, updates: 0, snoozed: 0, done: 0 }
  for (const n of all) {
    const v = viewOf(n, now)
    // Updates count only what is unread, like any inbox badge.
    if (v === 'updates' && n.status !== 'UNREAD') continue
    counts[v]++
  }
  return counts
}

const MIN = 60_000, HOUR = 60 * MIN, DAY = 24 * HOUR

function span(ms: number): string {
  if (ms < HOUR) return `${Math.max(1, Math.round(ms / MIN))} min`
  if (ms < DAY) return `${Math.round(ms / HOUR)}h`
  return `${Math.round(ms / DAY)}d`
}

export function ago(iso: string, now: number): string {
  const ms = now - Date.parse(iso)
  if (ms < MIN) return 'just now'
  return `${span(ms)} ago`
}

/** How long a waiting request has left; `soon` once under an hour. */
export function timeLeft(expiresAt: string | null | undefined, now: number): { text: string; soon: boolean } | null {
  if (!expiresAt) return null
  const ms = Date.parse(expiresAt) - now
  if (ms <= 0) return { text: 'Expiring now', soon: true }
  return { text: `Expires in ${span(ms)}`, soon: ms < HOUR }
}

export interface SnoozeOption { label: string; until: Date }

/** Snooze choices, leaving out any that would outlast the request itself. */
export function snoozeOptions(n: NotificationDto, now: number): SnoozeOption[] {
  const base = new Date(now)
  const tomorrow = new Date(base); tomorrow.setDate(base.getDate() + 1); tomorrow.setHours(9, 0, 0, 0)
  const nextWeek = new Date(base)
  nextWeek.setDate(base.getDate() + ((8 - base.getDay()) % 7 || 7)); nextWeek.setHours(9, 0, 0, 0)
  const options: SnoozeOption[] = [
    { label: 'For 1 hour', until: new Date(now + HOUR) },
    { label: 'For 3 hours', until: new Date(now + 3 * HOUR) },
    { label: 'Until tomorrow, 9:00', until: tomorrow },
    { label: 'Until next Monday, 9:00', until: nextWeek },
  ]
  const limit = isOpen(n) && n.expiresAt ? Date.parse(n.expiresAt) : Infinity
  return options.filter(o => o.until.getTime() < limit)
}

/** The facts worth showing about a request, from whatever its context carries. */
export function detailsOf(n: NotificationDto): { label: string; value: string }[] {
  const c = n.context ?? {}
  const rows: { label: string; value: string }[] = []
  const add = (label: string, v: unknown) => {
    if (typeof v === 'string' && v.trim()) rows.push({ label, value: v })
    else if (typeof v === 'number') rows.push({ label, value: String(v) })
  }
  add('Action', c.tool)
  add('Kind of action', c.actionType)
  add('Asked for', c.what)
  add('Why', c.why ?? c.reason)
  add('Guardrail', c.guardrail)
  add('Workflow step', c.node)
  add('Version', c.version)
  if (typeof c.deadline === 'string') rows.push({ label: 'Deadline', value: new Date(c.deadline).toLocaleString() })
  return rows
}

/** The raw inputs of an action, pretty-printed, when there are any. */
export function argsOf(n: NotificationDto): string | null {
  const args = n.context?.args
  if (args == null || (typeof args === 'object' && Object.keys(args as object).length === 0)) return null
  return JSON.stringify(args, null, 2)
}

/**
 * Where to review a suggested fix for a web step, from a notification the server raised for one.
 * Only a link into the workflow builder is followed.
 */
export function fixLinkOf(n: NotificationDto): string | null {
  const ctx = n.context
  if (ctx?.source !== 'webauto' || ctx?.kind !== 'locator_fix') return null
  const link = ctx.link
  return typeof link === 'string' && /^\/workflows\?open=[0-9a-f-]+(&node=[^&#]+)?$/i.test(link) ? link : null
}

/** What became of a request, in words. */
export function outcomeOf(n: NotificationDto): { text: string; tone: 'success' | 'danger' | 'default' } | null {
  switch (n.status) {
    case 'APPROVED': return { text: n.kind === 'WORKFLOW_REVIEW' ? 'You approved this procedure' : 'You approved this', tone: 'success' }
    case 'DENIED': return { text: n.kind === 'WORKFLOW_REVIEW' ? 'You asked for changes' : 'You declined this', tone: 'danger' }
    case 'SUBMITTED': return { text: 'You answered', tone: 'success' }
    case 'EXPIRED': return { text: n.responseNote || 'Expired before anyone answered', tone: 'default' }
    default: return null
  }
}
