import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Bell, CheckCheck, Inbox, Mail, Search, X } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { notificationsApi } from '@/api/notifications'
import { useInboxActions } from '@/components/notifications/useInboxActions'
import { NotificationItem, type InboxActions, type ItemCommand } from '@/components/notifications/NotificationItem'
import {
  KIND_META, NO_FILTERS, VIEWS, agentOf, buildInbox, countsByView, type InboxFilters, type InboxView,
} from '@/components/notifications/inbox'
import type { NotificationKind } from '@/types'

const KINDS: NotificationKind[] = ['PERMISSION_REQUEST', 'WORKFLOW_REVIEW', 'INSTRUCTION_REQUIRED', 'INFO']

/**
 * Where Jizza and its agents talk to the user: approvals, suggested procedures, questions and
 * updates, as a prioritised inbox the user can act on without leaving the page.
 */
export function NotificationsPage() {
  const qc = useQueryClient()
  const [view, setView] = useState<InboxView>('needs_you')
  const [filters, setFilters] = useState<InboxFilters>(NO_FILTERS)
  const [focusedId, setFocusedId] = useState<string | null>(null)
  const [command, setCommand] = useState<ItemCommand>(null)
  const [now, setNow] = useState(() => Date.now())
  const [banner, setBanner] = useState<string | null>(null)

  // Expiry countdowns and snoozes that run out move on their own.
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(t)
  }, [])

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.list(),
    refetchInterval: 60_000,
  })
  const all = useMemo(() => data ?? [], [data])

  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] })

  const actions: InboxActions = useInboxActions()

  async function markAllRead() {
    try {
      const res = await notificationsApi.markAllRead()
      setBanner(res?.message ?? 'All caught up.')
    } catch (e) {
      setBanner(e instanceof Error ? e.message : 'Could not mark everything read.')
    } finally {
      void refresh()
    }
  }

  const counts = useMemo(() => countsByView(all, now), [all, now])
  const sections = useMemo(() => buildInbox(all, view, filters, now), [all, view, filters, now])
  const visible = useMemo(() => sections.flatMap(s => s.items), [sections])
  const agents = useMemo(() => [...new Set(all.map(agentOf))].sort(), [all])
  const urgent = useMemo(() => buildInbox(all, 'needs_you', { ...NO_FILTERS, urgentOnly: true }, now)
    .reduce((sum, s) => sum + s.items.length, 0), [all, now])
  const unread = all.filter(n => n.status === 'UNREAD' && !n.archivedAt).length
  const filtered = JSON.stringify(filters) !== JSON.stringify(NO_FILTERS)

  // Keep a sensible item focused as the list changes under it.
  useEffect(() => {
    if (!visible.some(n => n.id === focusedId)) setFocusedId(visible[0]?.id ?? null)
  }, [visible, focusedId])

  // j/k to move, a approve, d decline, e archive, u read/unread: the Gmail and Linear habits.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (e.metaKey || e.ctrlKey || e.altKey || target.closest('input, textarea, select, [contenteditable]')) return
      const i = visible.findIndex(n => n.id === focusedId)
      const move = (d: number) => {
        const next = visible[Math.min(visible.length - 1, Math.max(0, i + d))]
        if (next) setFocusedId(next.id)
      }
      const keys: Record<string, () => void> = {
        j: () => move(1), ArrowDown: () => move(1),
        k: () => move(-1), ArrowUp: () => move(-1),
        a: () => setCommand('approve'),
        d: () => setCommand('decline'),
        e: () => setCommand('archive'),
        u: () => setCommand('toggleRead'),
      }
      const run = keys[e.key]
      if (!run || !focusedId) return
      e.preventDefault()
      run()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [visible, focusedId])

  const toggleKind = (k: NotificationKind) => setFilters(f => ({
    ...f, kinds: f.kinds.includes(k) ? f.kinds.filter(x => x !== k) : [...f.kinds, k],
  }))

  const chip = (active: boolean) => `inline-flex items-center gap-1 rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
    active
      ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-500/15 dark:text-brand-300'
      : 'border-gray-200 text-gray-600 hover:border-gray-300 hover:text-gray-900 dark:border-gray-700 dark:text-gray-300 dark:hover:border-gray-600'
  }`

  return (
    <div className="page">
      <PageHeader
        icon={Bell}
        title="Notifications"
        description="Approvals, suggestions, questions and updates from Jizza, most important first."
        actions={unread > 0 ? (
          <Button size="sm" variant="secondary" onClick={() => void markAllRead()}>
            <CheckCheck className="h-4 w-4" />Mark all read
          </Button>
        ) : undefined}
      />

      {/* At a glance */}
      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Stat icon={Inbox} label="Waiting on you" value={counts.needs_you} tone={counts.needs_you > 0 ? 'brand' : 'muted'}
          onClick={() => { setView('needs_you'); setFilters(NO_FILTERS) }} />
        <Stat icon={AlertTriangle} label="Urgent" value={urgent} tone={urgent > 0 ? 'danger' : 'muted'}
          onClick={() => { setView('needs_you'); setFilters({ ...NO_FILTERS, urgentOnly: true }) }} />
        <Stat icon={Mail} label="Unread" value={unread} tone="muted"
          onClick={() => setFilters({ ...NO_FILTERS, unreadOnly: true })} />
      </div>

      {banner && (
        <div className="mb-4 flex items-center justify-between rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300">
          {banner}
          <button onClick={() => setBanner(null)} aria-label="Dismiss" className="text-gray-400 hover:text-gray-700"><X className="h-4 w-4" /></button>
        </div>
      )}

      {/* Tabs */}
      <div className="mb-3 flex gap-1 overflow-x-auto border-b border-gray-200 dark:border-gray-800" role="tablist">
        {VIEWS.map(v => (
          <button
            key={v.id}
            role="tab"
            aria-selected={view === v.id}
            onClick={() => setView(v.id)}
            className={`-mb-px flex shrink-0 items-center gap-2 border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
              view === v.id
                ? 'border-brand-500 text-brand-700 dark:text-brand-300'
                : 'border-transparent text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'
            }`}
          >
            {v.label}
            {counts[v.id] > 0 && v.id !== 'done' && (
              <span className={`rounded-full px-1.5 text-xs ${
                v.id === 'needs_you' ? 'bg-brand-500 text-white' : 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
              }`}>{counts[v.id]}</span>
            )}
          </button>
        ))}
      </div>

      {/* Filters */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="relative min-w-[12rem] flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            value={filters.q}
            onChange={e => setFilters(f => ({ ...f, q: e.target.value }))}
            placeholder="Search"
            className="w-full rounded-lg border border-gray-300 bg-white py-1.5 pl-8 pr-3 text-sm text-gray-900 placeholder-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
          />
        </div>
        {KINDS.map(k => (
          <button key={k} className={chip(filters.kinds.includes(k))} onClick={() => toggleKind(k)}>{KIND_META[k].plural}</button>
        ))}
        <button className={chip(filters.urgentOnly)} onClick={() => setFilters(f => ({ ...f, urgentOnly: !f.urgentOnly }))}>Urgent</button>
        <button className={chip(filters.unreadOnly)} onClick={() => setFilters(f => ({ ...f, unreadOnly: !f.unreadOnly }))}>Unread</button>
        {agents.length > 1 && (
          <select
            value={filters.agent ?? ''}
            onChange={e => setFilters(f => ({ ...f, agent: e.target.value || null }))}
            className="rounded-full border border-gray-200 bg-white px-3 py-1 text-xs font-medium text-gray-600 focus:outline-none dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300"
            aria-label="From"
          >
            <option value="">From anyone</option>
            {agents.map(a => <option key={a} value={a}>{a}</option>)}
          </select>
        )}
        {filtered && (
          <button onClick={() => setFilters(NO_FILTERS)} className="text-xs font-medium text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100">
            Clear filters
          </button>
        )}
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}

      {isError && (
        <EmptyState icon={Bell} title="Couldn't load notifications" description="Check your connection and try again."
          action={{ label: 'Try again', onClick: () => void refetch() }} />
      )}

      {!isLoading && !isError && sections.length === 0 && (
        <EmptyState
          icon={view === 'needs_you' ? CheckCheck : Bell}
          title={filtered ? 'Nothing matches' : view === 'needs_you' ? "You're all caught up" : 'Nothing here'}
          description={filtered ? 'Try clearing the filters.' : VIEWS.find(v => v.id === view)!.empty}
        />
      )}

      <div className="space-y-6">
        {sections.map(section => (
          <section key={section.title ?? 'all'}>
            {section.title && (
              <h2 className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                {section.title}<span className="font-normal normal-case tracking-normal text-gray-400">{section.items.length}</span>
              </h2>
            )}
            <div className="space-y-2">
              {section.items.map(n => (
                <NotificationItem
                  key={n.id}
                  n={n}
                  now={now}
                  actions={actions}
                  focused={n.id === focusedId}
                  onFocus={() => setFocusedId(n.id)}
                  command={n.id === focusedId ? command : null}
                  onCommandHandled={() => setCommand(null)}
                />
              ))}
            </div>
          </section>
        ))}
      </div>

      {visible.length > 0 && (
        <p className="mt-6 hidden text-center text-xs text-gray-400 sm:block">
          <Kbd>j</Kbd>/<Kbd>k</Kbd> move · <Kbd>a</Kbd> approve · <Kbd>d</Kbd> decline · <Kbd>e</Kbd> archive · <Kbd>u</Kbd> read/unread
        </p>
      )}
    </div>
  )
}

function Kbd({ children }: { children: React.ReactNode }) {
  return <kbd className="rounded border border-gray-300 bg-white px-1 font-mono text-[10px] text-gray-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300">{children}</kbd>
}

function Stat({ icon: Icon, label, value, tone, onClick }: {
  icon: typeof Bell; label: string; value: number; tone: 'brand' | 'danger' | 'muted'; onClick: () => void
}) {
  const color = tone === 'danger' ? 'text-red-600 dark:text-red-400' : tone === 'brand' ? 'text-brand-600 dark:text-brand-400' : 'text-gray-500 dark:text-gray-400'
  return (
    <button onClick={onClick}
      className="flex items-center gap-3 rounded-xl border border-gray-200 bg-white px-4 py-3 text-left shadow-sm transition-colors hover:border-gray-300 dark:border-gray-800 dark:bg-gray-900 dark:hover:border-gray-700">
      <Icon className={`h-5 w-5 ${color}`} />
      <div>
        <p className={`text-xl font-semibold leading-none ${tone === 'muted' ? 'text-gray-900 dark:text-gray-100' : color}`}>{value}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{label}</p>
      </div>
    </button>
  )
}
