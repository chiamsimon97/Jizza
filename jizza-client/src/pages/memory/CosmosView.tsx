import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Brain, List, Maximize2, Minus, Orbit, Plus, Wind } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { cosmosApi } from '@/api/cosmos'
import { useWebSocket } from '@/hooks/useWebSocket'
import type { CelestialBody, MemoryNode, MemoryRealtimeEvent, MemorySearchFilters, MemorySearchHit } from '@/types'
import { CosmosCanvas, ITEMS_AT, itemKey, type CosmosCanvasHandle, type CosmosHighlight } from '@/components/cosmos/CosmosCanvas'
import { CosmosSearchBar, useDebounced } from '@/components/cosmos/CosmosSearchBar'
import { CosmosFilters, filterCount, matchesFilters, type CosmosAgentOption } from '@/components/cosmos/CosmosFilters'
import { CosmosListView } from '@/components/cosmos/CosmosListView'
import { MemoryPassportDrawer } from '@/components/cosmos/MemoryPassportDrawer'
import { useCosmosLayout } from '@/components/cosmos/useCosmosLayout'

const ITEMS_PER_BODY = 200

/**
 * The Memory Cosmos: everything Jizza remembers about the user, drawn as a solar system. Jizza is the
 * sun, each specialist a planet; subjects, notes, files and tools are moons, and every fact or note
 * is a satellite. The list is the same sky as a tree. `/memory/:agentId` opens the list on one agent.
 */
export function CosmosView() {
  const { agentId } = useParams()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const view: 'sky' | 'list' = agentId || params.get('view') === 'list' ? 'list' : 'sky'
  const setView = (v: 'sky' | 'list') => {
    if (v === 'sky') navigate('/memory')
    else setParams({ view: 'list' })
  }

  const canvas = useRef<CosmosCanvasHandle>(null)
  const [q, setQ] = useState('')
  const dq = useDebounced(q.trim(), 250)
  const [filters, setFilters] = useState<MemorySearchFilters>({})
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [selectedBodyId, setSelectedBodyId] = useState<string | null>(null)
  const [selected, setSelected] = useState<MemoryNode | null>(null)
  const [requested, setRequested] = useState<string[]>([])
  const [includeInactive, setIncludeInactive] = useState(false)
  const reducedMotion = useMemo(() => !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches, [])
  const [drift, setDrift] = useState(false)

  const snapshot = useQuery({ queryKey: ['cosmos', 'snapshot'], queryFn: cosmosApi.snapshot })
  const layout = useCosmosLayout(snapshot.data)
  const bodies = useMemo(() => new Map<string, CelestialBody>((snapshot.data?.bodies ?? []).map(b => [b.bodyId, b])), [snapshot.data])

  const agents: CosmosAgentOption[] = useMemo(() => (snapshot.data?.bodies ?? [])
    .filter(b => (b.type === 'SUN' || b.type === 'AGENT') && typeof b.meta?.agentId === 'string')
    .map(b => ({
      id: b.meta!.agentId as string, key: b.agentKey, name: b.label, bodyId: b.bodyId,
      domain: typeof b.meta?.domain === 'string' ? b.meta.domain : undefined,
    })), [snapshot.data])

  // Each body's facts and notes, loaded the first time it is zoomed into.
  const itemQueries = useQueries({
    queries: requested.map(bodyId => ({
      queryKey: ['cosmos', 'items', bodyId],
      queryFn: () => cosmosApi.items(bodyId, { limit: ITEMS_PER_BODY }),
      staleTime: 30_000,
    })),
  })
  const itemsStamp = itemQueries.map(r => r.dataUpdatedAt).join(',')
  const itemsByBody = useMemo(() => {
    const m = new Map<string, MemoryNode[]>()
    itemQueries.forEach((r, i) => { if (r.data) m.set(requested[i], r.data.items) })
    return m
    // dataUpdatedAt changes whenever any page does; the arrays themselves are stable between.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemsStamp, requested])
  const needItems = useCallback((ids: string[]) => {
    setRequested(prev => {
      const missing = ids.filter(id => !prev.includes(id))
      return missing.length ? [...prev, ...missing] : prev
    })
  }, [])
  // Bodies that disappeared (moved or forgotten) stop being fetched.
  useEffect(() => {
    if (!snapshot.data) return
    setRequested(prev => {
      const kept = prev.filter(id => bodies.has(id))
      return kept.length === prev.length ? prev : kept
    })
  }, [snapshot.data, bodies])

  // Search lights up matches and fades everything else.
  const search = useQuery({
    queryKey: ['cosmos', 'search', dq, filters],
    queryFn: () => cosmosApi.search(dq, filters),
    enabled: dq.length > 0,
  })
  const highlight: CosmosHighlight | null = useMemo(() => {
    if (!dq || !search.data) return null
    const h: CosmosHighlight = { bodies: new Set(), items: new Set() }
    for (const hit of search.data) {
      hit.bodyPath.forEach(id => h.bodies.add(id))
      h.items.add(itemKey(hit.item))
    }
    return h
  }, [dq, search.data])
  useEffect(() => {
    if (search.data?.length) needItems([...new Set(search.data.map(h => h.item.parentBodyId))])
  }, [search.data, needItems])

  const matches = useMemo(() => matchesFilters(filters), [filters])
  const dimmedAgentKeys = useMemo(() => {
    if (!filters.agentIds?.length) return new Set<string>()
    return new Set(agents.filter(a => !filters.agentIds!.includes(a.id)).map(a => a.key))
  }, [filters.agentIds, agents])

  // Live: anything that changes the user's memory refreshes the sky, the lists and the passport.
  const pending = useRef<number | null>(null)
  const onMemoryEvent = useCallback((e: MemoryRealtimeEvent) => {
    if (e.kind === 'MEMORY_FORGOTTEN' && selected && e.itemId === selected.id) setSelected(null)
    if (pending.current != null) return
    pending.current = window.setTimeout(() => {
      pending.current = null
      qc.invalidateQueries({ queryKey: ['cosmos'] })
      qc.invalidateQueries({ queryKey: ['memory'] })
    }, 300)
  }, [qc, selected])
  useWebSocket({ onMemoryEvent })
  useEffect(() => () => { if (pending.current != null) window.clearTimeout(pending.current) }, [])

  const flyToBody = (bodyId: string, k?: number) => {
    const b = layout.byId.get(bodyId)
    if (b) canvas.current?.flyTo(b.x, b.y, k ?? Math.max(ITEMS_AT * 1.8, 120 / (b.satExtent + 20)), bodyId)
  }
  const pick = (hit: MemorySearchHit) => {
    needItems([hit.item.parentBodyId])
    setSelectedBodyId(hit.item.parentBodyId)
    setSelected(hit.item)
    if (view === 'sky') flyToBody(hit.item.parentBodyId)
  }
  const locate = (n: MemoryNode) => {
    needItems([n.parentBodyId])
    setSelectedBodyId(n.parentBodyId)
    if (view !== 'sky') navigate('/memory')
    // The sky may be mounting; give it a frame to size itself first.
    requestAnimationFrame(() => requestAnimationFrame(() => flyToBody(n.parentBodyId)))
  }

  const total = layout.bodies.reduce((sum, b) => sum + b.body.memoryCount, 0)
  const empty = snapshot.data && total === 0 && layout.bodies.length <= 1

  return (
    <div className="page max-w-none">
      <PageHeader icon={Brain} title="Memory"
        description="Everything Jizza remembers about you, which agents can use it, and when they did."
        actions={
          <div role="group" aria-label="View" className="inline-flex rounded-lg border border-gray-200 p-0.5 dark:border-gray-800">
            {([['sky', Orbit, 'Sky'], ['list', List, 'List']] as const).map(([v, Icon, text]) => (
              <button key={v} aria-pressed={view === v} onClick={() => setView(v)}
                className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium ${
                  view === v ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/40 dark:text-brand-200' : 'text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-gray-100'}`}>
                <Icon className="h-4 w-4" /> {text}
              </button>
            ))}
          </div>
        } />

      <div className="mb-3 flex flex-col gap-3">
        <CosmosSearchBar value={q} onChange={setQ} hits={dq ? search.data : undefined} loading={search.isFetching}
          bodies={bodies} onPick={pick} filtersOpen={filtersOpen} filterCount={filterCount(filters)}
          onToggleFilters={() => setFiltersOpen(o => !o)} />
        {filtersOpen && <CosmosFilters value={filters} onChange={setFilters} agents={agents} />}
      </div>

      {snapshot.isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {snapshot.isError && <p className="py-10 text-center text-sm text-red-500">Your memory couldn’t be loaded. Try again in a moment.</p>}
      {empty && (
        <EmptyState icon={Brain} title="Nothing remembered yet"
          description="As you chat, Jizza and its agents remember what matters. Tell Jizza something about yourself to begin." />
      )}

      {snapshot.data && !empty && view === 'sky' && (
        <div className="relative h-[calc(100vh-15rem)] min-h-[420px] overflow-hidden rounded-xl border border-gray-200 dark:border-gray-800">
          <CosmosCanvas ref={canvas} layout={layout} links={snapshot.data.links} itemsByBody={itemsByBody}
            selectedBodyId={selectedBodyId} selectedItemKey={selected ? itemKey(selected) : null}
            highlight={highlight} matches={matches} dimmedAgentKeys={dimmedAgentKeys} drift={drift && !reducedMotion}
            onSelectBody={setSelectedBodyId} onSelectItem={n => { setSelected(n); setSelectedBodyId(n.parentBodyId) }}
            onNeedItems={needItems} />
          <Legend />
          <div className="absolute bottom-3 right-3 flex flex-col gap-1">
            <CanvasButton label="Zoom in" onClick={() => canvas.current?.zoomBy(1.5)}><Plus className="h-4 w-4" /></CanvasButton>
            <CanvasButton label="Zoom out" onClick={() => canvas.current?.zoomBy(1 / 1.5)}><Minus className="h-4 w-4" /></CanvasButton>
            <CanvasButton label="Show everything" onClick={() => canvas.current?.fit()}><Maximize2 className="h-4 w-4" /></CanvasButton>
            {!reducedMotion && (
              <CanvasButton label={drift ? 'Stop the orbits' : 'Let the orbits drift'} pressed={drift} onClick={() => setDrift(d => !d)}>
                <Wind className="h-4 w-4" />
              </CanvasButton>
            )}
          </div>
        </div>
      )}

      {snapshot.data && !empty && view === 'list' && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-3 text-sm">
            {agentId && (
              <button onClick={() => setView('list')} className="font-medium text-brand-600 hover:underline dark:text-brand-300">All agents</button>
            )}
            <label className="ml-auto flex items-center gap-2 text-gray-600 dark:text-gray-300">
              <input type="checkbox" checked={includeInactive} onChange={e => setIncludeInactive(e.target.checked)} />
              Show retired and replaced
            </label>
          </div>
          <CosmosListView key={agentId ?? 'all'} layout={layout} agentId={agentId} includeInactive={includeInactive}
            matches={matches} onOpenItem={setSelected} />
        </div>
      )}

      <MemoryPassportDrawer item={selected ? { kind: selected.itemKind, id: selected.id } : null} agents={agents}
        onClose={() => setSelected(null)}
        onLocate={selected ? () => { const n = selected; setSelected(null); locate(n) } : undefined} />
    </div>
  )
}

function CanvasButton({ label, pressed, onClick, children }: { label: string; pressed?: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} aria-pressed={pressed} onClick={onClick}
      className={`grid h-9 w-9 place-items-center rounded-lg border shadow-sm transition-colors ${
        pressed ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/60 dark:text-brand-200'
          : 'border-gray-200 bg-white/90 text-gray-700 hover:bg-white dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-200'}`}>
      {children}
    </button>
  )
}

function Legend() {
  return (
    <details className="absolute bottom-3 left-3 max-w-[16rem] rounded-lg border border-gray-200 bg-white/90 px-3 py-2 text-xs text-gray-600 shadow-sm backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300">
      <summary className="cursor-pointer font-medium text-gray-800 dark:text-gray-100">How to read the sky</summary>
      <ul className="mt-2 flex flex-col gap-1">
        <li>Jizza is the sun; each agent is a planet in its own colour.</li>
        <li>Moons are people, places and things, notes, files, connectors and workflows.</li>
        <li>Dots are facts, squares are an agent’s notes. Zoom in to see them.</li>
        <li>Bigger means agents use it more; faint means not used lately.</li>
        <li>A grey dot with a lock is restricted: no agent can use it.</li>
        <li>An amber dashed ring is waiting for you to confirm.</li>
      </ul>
    </details>
  )
}
