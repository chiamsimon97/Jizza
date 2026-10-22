import { useCallback, useMemo, useState } from 'react'
import { useInfiniteQuery, useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import {
  Activity, AlertTriangle, ChevronDown, ChevronRight, Clock, Fingerprint, History, Layers, Link2,
  ShieldCheck, SlidersHorizontal, User, X,
} from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { MonitorTabs } from '@/components/history/MonitorTabs'
import { TimeRangePicker } from '@/components/history/TimeRangePicker'
import { Timeline } from '@/components/history/Timeline'
import { SearchBox } from '@/components/history/SearchBox'
import { FilterChip, toggled } from '@/components/history/FilterChip'
import { JsonView } from '@/components/history/JsonView'
import { ACTOR_LABEL, SEVERITY, categoryColor, iconFor } from '@/components/history/palette'
import { activityApi } from '@/api/activity'
import { dayKey, dayLabel, rangeParams, readRange, relativeTime, writeRange, type TimeRange } from '@/lib/timeRange'
import type { ActivityCategoryCount, ActivityItem, ActivitySeverity, ActorType } from '@/types'

const PAGE = 100
const DEFAULT_RANGE: TimeRange = { preset: 'last_7d' }
const SEVERITIES: ActivitySeverity[] = ['INFO', 'NOTICE', 'WARNING', 'CRITICAL']
const ACTORS: ActorType[] = ['USER', 'AGENT', 'SYSTEM']

type View = 'time' | 'section' | 'actor'
const VIEWS: { key: View; label: string; icon: typeof Clock }[] = [
  { key: 'time', label: 'By day', icon: Clock },
  { key: 'section', label: 'By section', icon: Layers },
  { key: 'actor', label: 'By who', icon: User },
]

interface Bundle { key: string; label: string; color?: string; items: ActivityItem[]; sub?: Bundle[] }

/** Folds the loaded entries into the groups the chosen view shows. */
function bundle(items: ActivityItem[], view: View, categories: ActivityCategoryCount[]): Bundle[] {
  const by = (key: (i: ActivityItem) => string) => {
    const map = new Map<string, ActivityItem[]>()
    items.forEach(i => { const k = key(i); map.set(k, [...(map.get(k) ?? []), i]) })
    return map
  }
  if (view === 'time') {
    return [...by(i => dayKey(i.createdAt))].map(([k, list]) => ({ key: k, label: dayLabel(k), items: list }))
  }
  if (view === 'actor') {
    return [...by(i => i.actorName ?? ACTOR_LABEL[i.actorType] ?? i.actorType)]
      .sort((a, b) => b[1].length - a[1].length)
      .map(([k, list]) => ({ key: k, label: k, items: list }))
  }
  const byCategory = by(i => i.category)
  return categories.filter(c => byCategory.has(c.key)).map(c => {
    const list = byCategory.get(c.key)!
    const groups = new Map<string, ActivityItem[]>()
    list.forEach(i => groups.set(i.group, [...(groups.get(i.group) ?? []), i]))
    return {
      key: c.key, label: c.label, color: categoryColor(c.key), items: list,
      sub: c.groups.filter(g => groups.has(g.key)).map(g => ({ key: `${c.key}/${g.key}`, label: g.label, items: groups.get(g.key)! })),
    }
  })
}

export function ActivityPage() {
  const [params, setParams] = useSearchParams()
  const range = readRange(params, DEFAULT_RANGE.preset!)
  const categories = params.getAll('cat')
  const groups = params.getAll('grp')
  const severities = params.getAll('sev') as ActivitySeverity[]
  const actors = params.getAll('who') as ActorType[]
  const q = params.get('q') ?? ''
  const view = (params.get('view') as View) || 'time'

  const [filtersOpen, setFiltersOpen] = useState(false)

  const update = useCallback((change: (p: URLSearchParams) => void) => {
    setParams(prev => { const next = new URLSearchParams(prev); change(next); return next }, { replace: true })
  }, [setParams])
  const setList = (key: string, values: string[]) => update(p => { p.delete(key); values.forEach(v => p.append(key, v)) })

  const query = useInfiniteQuery({
    queryKey: ['activity', 'explore', range, categories, groups, severities, actors, q],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => activityApi.explore({
      ...rangeParams(range), categories, groups, severities, actors, q, limit: PAGE, offset: pageParam,
    }),
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((n, p) => n + p.items.length, 0)
      return loaded < last.total ? loaded : undefined
    },
  })

  const first = query.data?.pages[0]
  const items = useMemo(() => query.data?.pages.flatMap(p => p.items) ?? [], [query.data])
  const facets = first?.categories ?? []
  const bundles = useMemo(() => bundle(items, view, facets), [items, view, facets])
  const filtered = categories.length + groups.length + severities.length + actors.length > 0 || q !== ''

  const timelineSeries = facets.map(c => ({ key: c.key, label: c.label, color: categoryColor(c.key) }))
  const timelineBuckets = (first?.buckets ?? []).map(b => ({ start: b.start, values: b.byCategory }))

  const toggleCategory = (key: string) => {
    const next = toggled(categories, key)
    update(p => {
      p.delete('cat'); next.forEach(v => p.append('cat', v))
      // Sub-groups of a section that is no longer picked would hide everything.
      const keep = groups.filter(g => next.length === 0 || next.includes(g.split('/')[0]))
      p.delete('grp'); keep.forEach(v => p.append('grp', v))
    })
  }

  return (
    <div className="page max-w-7xl">
      <MonitorTabs />
      <PageHeader
        icon={Activity}
        title="Activity"
        description="Everything people, agents and Jizza did, sorted into sections you can drill into."
        actions={<VerifyChain />}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <TimeRangePicker value={range} defaultRange={DEFAULT_RANGE} onChange={r => update(p => writeRange(p, r))} />
        <SearchBox value={q} onChange={v => update(p => { if (v) p.set('q', v); else p.delete('q') })} placeholder="Search what happened, to what, or by whom" />
        <button onClick={() => setFiltersOpen(o => !o)} aria-expanded={filtersOpen}
          className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 shadow-sm lg:hidden dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
          <SlidersHorizontal className="h-4 w-4" />Sections & filters
          {categories.length + groups.length + severities.length + actors.length > 0 && (
            <span className="rounded-full bg-brand-500 px-1.5 text-xs text-white">{categories.length + groups.length + severities.length + actors.length}</span>
          )}
        </button>
        <div className="inline-flex rounded-lg border border-gray-200 bg-white p-0.5 dark:border-gray-800 dark:bg-gray-900" role="radiogroup" aria-label="Group by">
          {VIEWS.map(v => (
            <button
              key={v.key}
              role="radio"
              aria-checked={view === v.key}
              onClick={() => update(p => p.set('view', v.key))}
              className={`flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${view === v.key ? 'bg-brand-500 text-white' : 'text-gray-500 hover:text-gray-900 dark:hover:text-gray-100'}`}
            >
              <v.icon className="h-3.5 w-3.5" />{v.label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-[16rem_minmax(0,1fr)]">
        {/* On a phone the sections and filters fold away behind one button. */}
        <aside className={`space-y-5 lg:sticky lg:top-4 lg:block lg:self-start ${filtersOpen ? 'block' : 'hidden'}`}>
          <SectionNav
            facets={facets}
            loading={query.isLoading}
            categories={categories}
            groups={groups}
            onAll={() => update(p => { p.delete('cat'); p.delete('grp') })}
            onCategory={toggleCategory}
            onGroup={key => setList('grp', toggled(groups, key))}
          />
          <div>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Severity</p>
            <div className="flex flex-wrap gap-1.5">
              {SEVERITIES.map(s => (
                <FilterChip key={s} active={severities.includes(s)} color={SEVERITY[s].color} count={first?.severities[s] ?? 0} onClick={() => setList('sev', toggled(severities, s))}>
                  {SEVERITY[s].label}
                </FilterChip>
              ))}
            </div>
          </div>
          <div>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Who</p>
            <div className="flex flex-wrap gap-1.5">
              {ACTORS.map(a => (
                <FilterChip key={a} active={actors.includes(a)} count={first?.actors[a] ?? 0} onClick={() => setList('who', toggled(actors, a))}>
                  {ACTOR_LABEL[a]}
                </FilterChip>
              ))}
            </div>
          </div>
        </aside>

        <section className="min-w-0 space-y-4">
          <Card padding="md">
            {first ? (
              <Timeline
                buckets={timelineBuckets}
                series={timelineSeries}
                unit={first.bucketUnit}
                noun="events"
                onZoom={(from, to) => update(p => writeRange(p, { from, to }))}
              />
            ) : <div className="h-28" />}
          </Card>

          {filtered && (
            <div className="flex flex-wrap items-center gap-2 text-xs text-gray-500">
              <span>{first ? `${first.total.toLocaleString()} matching` : 'Filtering'}</span>
              {[...categories.map(c => ({ k: 'cat', v: c, label: facets.find(f => f.key === c)?.label ?? c })),
                ...groups.map(g => ({ k: 'grp', v: g, label: facets.flatMap(f => f.groups.map(x => ({ key: `${f.key}/${x.key}`, label: x.label }))).find(x => x.key === g)?.label ?? g })),
                ...severities.map(s => ({ k: 'sev', v: s, label: SEVERITY[s].label })),
                ...actors.map(a => ({ k: 'who', v: a, label: ACTOR_LABEL[a] })),
                ...(q ? [{ k: 'q', v: q, label: `“${q}”` }] : []),
              ].map(f => (
                <button key={`${f.k}:${f.v}`} onClick={() => update(p => { const rest = p.getAll(f.k).filter(x => x !== f.v); p.delete(f.k); rest.forEach(x => p.append(f.k, x)) })}
                  className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-gray-700 hover:bg-gray-200 dark:bg-gray-800 dark:text-gray-300">
                  {f.label}<X className="h-3 w-3" />
                </button>
              ))}
              <button onClick={() => update(p => { ['cat', 'grp', 'sev', 'who', 'q'].forEach(k => p.delete(k)) })} className="text-brand-600 hover:underline dark:text-brand-300">Clear all</button>
            </div>
          )}

          {first?.truncated && (
            <p className="flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
              <AlertTriangle className="h-4 w-4" />This range holds more than the newest 10,000 events, so counts cover those. Narrow the range for exact numbers.
            </p>
          )}

          {query.isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
          {query.isError && <EmptyState icon={AlertTriangle} title="Activity could not be loaded" description={(query.error as Error).message} />}
          {!query.isLoading && !query.isError && items.length === 0 && (
            <EmptyState icon={Activity} title="Nothing here" description={filtered ? 'No activity matches these filters in this range.' : 'No activity in this range yet.'} />
          )}

          <div className="space-y-4">
            {bundles.map(b => (
              <BundleBlock key={b.key} bundle={b} onRequest={id => update(p => p.set('q', id))} />
            ))}
          </div>

          {query.hasNextPage && (
            <div className="flex justify-center">
              <Button variant="secondary" size="sm" loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
                Show more ({(first!.total - items.length).toLocaleString()} left)
              </Button>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function SectionNav({ facets, loading, categories, groups, onAll, onCategory, onGroup }: {
  facets: ActivityCategoryCount[]
  loading: boolean
  categories: string[]
  groups: string[]
  onAll: () => void
  onCategory: (key: string) => void
  onGroup: (key: string) => void
}) {
  const total = facets.reduce((n, c) => n + c.count, 0)
  const max = Math.max(1, ...facets.map(c => c.count))
  const [opened, setOpened] = useState<string[]>([])

  return (
    <nav aria-label="Sections">
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Sections</p>
      <button
        onClick={onAll}
        className={`mb-1 flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm transition-colors ${categories.length === 0 && groups.length === 0 ? 'bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200' : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}
      >
        <Layers className="h-4 w-4" />
        <span className="flex-1 text-left">Everything</span>
        <span className="text-xs tabular-nums text-gray-400">{loading ? '…' : total}</span>
      </button>
      <ul className="space-y-0.5">
        {facets.filter(c => c.count > 0 || categories.includes(c.key)).map(c => {
          const Icon = iconFor(c.icon)
          const color = categoryColor(c.key)
          const active = categories.includes(c.key)
          const open = active || opened.includes(c.key) || groups.some(g => g.startsWith(`${c.key}/`))
          return (
            <li key={c.key}>
              <div className={`group flex items-center rounded-lg transition-colors ${active ? 'bg-gray-100 dark:bg-gray-800' : 'hover:bg-gray-50 dark:hover:bg-gray-800/60'}`}>
                <button onClick={() => setOpened(o => toggled(o, c.key))} aria-label={open ? 'Hide sub-groups' : 'Show sub-groups'} className="grid h-8 w-6 place-items-center text-gray-400 hover:text-gray-700">
                  {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                </button>
                <button onClick={() => onCategory(c.key)} aria-pressed={active} className="flex min-w-0 flex-1 items-center gap-2 py-1.5 pr-2.5 text-sm">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-md text-white" style={{ backgroundColor: color }}>
                    <Icon className="h-3.5 w-3.5" />
                  </span>
                  <span className={`min-w-0 flex-1 text-left ${active ? 'font-medium text-gray-900 dark:text-gray-100' : 'text-gray-600 dark:text-gray-300'}`}>
                    <span className="block truncate">{c.label}</span>
                    <span className="mt-0.5 block h-0.5 rounded-full bg-gray-100 dark:bg-gray-800">
                      <span className="block h-0.5 rounded-full transition-all duration-500" style={{ width: `${(c.count / max) * 100}%`, backgroundColor: color }} />
                    </span>
                  </span>
                  <span className="text-xs tabular-nums text-gray-400">{c.count}</span>
                </button>
              </div>
              {open && (
                <ul className="mb-1 ml-9 mt-0.5 space-y-0.5 border-l border-gray-200 pl-2 dark:border-gray-800">
                  {c.groups.map(g => {
                    const key = `${c.key}/${g.key}`
                    const on = groups.includes(key)
                    return (
                      <li key={key}>
                        <button onClick={() => onGroup(key)} aria-pressed={on} disabled={g.count === 0 && !on}
                          className={`flex w-full items-center gap-2 rounded-md px-2 py-1 text-xs transition-colors disabled:opacity-40 ${on ? 'bg-brand-50 font-medium text-brand-700 dark:bg-brand-900/30 dark:text-brand-200' : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:hover:bg-gray-800 dark:hover:text-gray-100'}`}>
                          <span className={`h-3 w-3 rounded border ${on ? 'border-brand-500 bg-brand-500' : 'border-gray-300 dark:border-gray-600'}`} />
                          <span className="flex-1 truncate text-left">{g.label}</span>
                          <span className="tabular-nums text-gray-400">{g.count}</span>
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

function BundleBlock({ bundle: b, onRequest }: { bundle: Bundle; onRequest: (id: string) => void }) {
  const [collapsed, setCollapsed] = useState(false)
  return (
    <div className="animate-fade-in-up">
      <button onClick={() => setCollapsed(c => !c)} className="sticky top-0 z-10 mb-2 flex w-full items-center gap-2 bg-gray-50/90 py-1.5 text-left backdrop-blur dark:bg-gray-950/90">
        {collapsed ? <ChevronRight className="h-4 w-4 text-gray-400" /> : <ChevronDown className="h-4 w-4 text-gray-400" />}
        {b.color && <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: b.color }} />}
        <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">{b.label}</span>
        <span className="rounded-full bg-gray-200 px-1.5 text-xs tabular-nums text-gray-600 dark:bg-gray-800 dark:text-gray-300">{b.items.length}</span>
        <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
      </button>
      {!collapsed && (b.sub ? (
        <div className="space-y-3 pl-2">
          {b.sub.map(s => (
            <div key={s.key}>
              <p className="mb-1 ml-1 text-xs font-medium uppercase tracking-wide text-gray-400">{s.label} · {s.items.length}</p>
              <Rows items={s.items} onRequest={onRequest} />
            </div>
          ))}
        </div>
      ) : <Rows items={b.items} onRequest={onRequest} />)}
    </div>
  )
}

function Rows({ items, onRequest }: { items: ActivityItem[]; onRequest: (id: string) => void }) {
  return (
    <Card padding="none" className="overflow-hidden">
      <ol className="relative">
        <span aria-hidden className="absolute bottom-3 left-[1.6rem] top-3 w-px bg-gray-200 dark:bg-gray-800" />
        {items.map(i => <Row key={i.id} item={i} onRequest={onRequest} />)}
      </ol>
    </Card>
  )
}

function Row({ item, onRequest }: { item: ActivityItem; onRequest: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const color = categoryColor(item.category)
  const sev = SEVERITY[item.severity]
  const loud = item.severity === 'WARNING' || item.severity === 'CRITICAL'

  return (
    <li className={`relative border-b border-gray-100 last:border-0 dark:border-gray-800/70 ${open ? 'bg-gray-50/70 dark:bg-gray-800/30' : ''}`}>
      <button onClick={() => setOpen(o => !o)} aria-expanded={open} className="flex w-full items-start gap-3 px-4 py-2.5 text-left transition-colors hover:bg-gray-50 dark:hover:bg-gray-800/40">
        <span className={`relative z-10 mt-1.5 h-3 w-3 shrink-0 rounded-full ring-4 ring-white dark:ring-gray-900`} style={{ backgroundColor: color }}>
          {loud && <span className="absolute -inset-1 rounded-full opacity-60 animate-ping" style={{ backgroundColor: sev.color }} />}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
            <span className="text-sm font-medium text-gray-900 dark:text-gray-100">{item.label}</span>
            {item.severity !== 'INFO' && (
              <span className="rounded px-1.5 text-[10px] font-semibold uppercase tracking-wide text-white" style={{ backgroundColor: sev.color }}>{sev.label}</span>
            )}
            {item.target && <span className="max-w-[18rem] truncate rounded bg-gray-100 px-1.5 font-mono text-[11px] text-gray-600 dark:bg-gray-800 dark:text-gray-300" title={item.target}>{item.target}</span>}
          </div>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
            {item.actorName ?? ACTOR_LABEL[item.actorType]} · <span className="font-mono">{item.action}</span>
          </p>
        </div>
        <time dateTime={item.createdAt} title={new Date(item.createdAt).toLocaleString()} className="shrink-0 text-right text-xs tabular-nums text-gray-400">
          <span className="block">{new Date(item.createdAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' })}</span>
          <span className="block text-[10px]">{relativeTime(item.createdAt)}</span>
        </time>
      </button>
      {open && (
        <div className="space-y-3 px-4 pb-4 pl-11 animate-fade-in">
          <JsonView value={item.detail} empty="No extra detail was recorded for this event." />
          <dl className="grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
            <div className="flex gap-2"><dt className="text-gray-400">When</dt><dd className="text-gray-700 dark:text-gray-300">{new Date(item.createdAt).toLocaleString()}</dd></div>
            <div className="flex gap-2"><dt className="text-gray-400">Who</dt><dd className="text-gray-700 dark:text-gray-300">{item.actorName} ({item.actorType.toLowerCase()})</dd></div>
            {item.requestId && (
              <div className="flex items-center gap-2"><dt className="text-gray-400">Request</dt>
                <dd><button onClick={() => onRequest(item.requestId!)} className="inline-flex items-center gap-1 font-mono text-brand-600 hover:underline dark:text-brand-300"><Link2 className="h-3 w-3" />{item.requestId.slice(0, 12)} · everything from it</button></dd>
              </div>
            )}
            {item.action.startsWith('run.') && item.target && /^[0-9a-f-]{36}$/i.test(item.target) && (
              <div className="flex items-center gap-2"><dt className="text-gray-400">Run</dt>
                <dd><Link to={`/runs?run=${item.target}`} className="inline-flex items-center gap-1 text-brand-600 hover:underline dark:text-brand-300"><History className="h-3 w-3" />Open this run step by step</Link></dd>
              </div>
            )}
            <div className="flex items-center gap-2"><dt className="text-gray-400">Chain</dt><dd className="inline-flex items-center gap-1 font-mono text-gray-500"><Fingerprint className="h-3 w-3" />{item.hash.slice(0, 16)}…</dd></div>
          </dl>
        </div>
      )}
    </li>
  )
}

function VerifyChain() {
  const verify = useMutation({ mutationFn: () => activityApi.verify() })
  const result = verify.data
  return (
    <div className="flex items-center gap-2">
      {result && (
        <span className={`text-xs ${result.intact ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
          {result.intact ? `All ${result.entries.toLocaleString()} entries intact` : 'The record was altered'}
        </span>
      )}
      <Button variant="secondary" size="sm" loading={verify.isPending} onClick={() => verify.mutate()}>
        <ShieldCheck className="h-4 w-4" />Verify integrity
      </Button>
    </div>
  )
}
