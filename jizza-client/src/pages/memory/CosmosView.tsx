import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDownUp, Brain, List, Maximize2, Minus, Orbit, Pause, Play, Plus, RotateCcw, X, ZoomIn } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { Button } from '@/components/ui/Button'
import { cosmosApi } from '@/api/cosmos'
import { useWebSocket } from '@/hooks/useWebSocket'
import type { CelestialBody, MemoryNode, MemoryRealtimeEvent, MemorySearchFilters, MemorySearchHit } from '@/types'
import { CosmosCanvas, ITEMS_AT, itemKey, type CosmosCanvasHandle, type CosmosHighlight } from '@/components/cosmos/CosmosCanvas'
import { CosmosSearchBar, useDebounced } from '@/components/cosmos/CosmosSearchBar'
import { CosmosFilters, filterCount, matchesFilters, type CosmosAgentOption } from '@/components/cosmos/CosmosFilters'
import { CosmosListView } from '@/components/cosmos/CosmosListView'
import { MemoryPassportDrawer } from '@/components/cosmos/MemoryPassportDrawer'
import { MemoryTransferPanel } from '@/components/cosmos/MemoryTransferPanel'
import { useCosmosLayout, type CosmosLayout, type PlacedBody } from '@/components/cosmos/useCosmosLayout'

const ITEMS_PER_BODY = 200
/** Orbit speeds on offer; at 1× the innermost planet goes round about every 24 seconds. */
const SPEEDS = [0.25, 1, 4, 16]

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
  const [drift, setDrift] = useState(true)
  const [speed, setSpeed] = useState(1)
  const [transferOpen, setTransferOpen] = useState(false)

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

  // Reset: forget one body's part of the sky, or (from the sun) all of it.
  const reset = useMutation({
    mutationFn: (bodyId: string) => cosmosApi.reset(bodyId),
    onSuccess: (_, bodyId) => {
      setSelected(null)
      setRequested([])
      if (layout.byId.get(bodyId)?.body.type === 'SUBJECT') setSelectedBodyId(null)
      qc.invalidateQueries({ queryKey: ['cosmos'] })
      qc.invalidateQueries({ queryKey: ['memory'] })
    },
  })
  const askReset = (b: PlacedBody) => {
    const n = systemCount(layout, b)
    const what = b.body.type === 'SUN'
      ? `Reset ALL of your memory? Every fact, every agent's notes and every person, account and plan (${n} items) will be forgotten.`
      : `Reset ${b.body.label}? ${n} ${n === 1 ? 'memory' : 'memories'} here${hasMoons(layout, b) ? ' and in the moons around it' : ''} will be forgotten${b.body.type === 'SUBJECT' ? ', along with the moon itself' : ''}.`
    if (window.confirm(`${what}\n\nThis can’t be undone.`)) reset.mutate(b.body.bodyId)
  }

  const total = layout.bodies.reduce((sum, b) => sum + b.body.memoryCount, 0)
  const empty = snapshot.data && total === 0
  const selectedBody = selectedBodyId ? layout.byId.get(selectedBodyId) : undefined
  const sun = layout.sunId ? layout.byId.get(layout.sunId) : undefined

  return (
    <div className="page max-w-none">
      <PageHeader icon={Brain} title="Memory"
        description="Everything Jizza remembers about you, which agents can use it, and when they did."
 actions={
          <div className="flex items-center gap-2">
          <Button size="sm" variant="ghost" onClick={() => setTransferOpen(true)}>
            <ArrowDownUp className="h-4 w-4" /> Import / export
          </Button>
          {sun && !empty && (
            <Button size="sm" variant="ghost" onClick={() => askReset(sun)} loading={reset.isPending && reset.variables === sun.body.bodyId}>
              <RotateCcw className="h-4 w-4" /> Reset all
            </Button>
          )}
          <div role="group" aria-label="View" className="inline-flex rounded-lg border border-gray-200 p-0.5 dark:border-gray-800">
            {([['sky', Orbit, 'Sky'], ['list', List, 'List']] as const).map(([v, Icon, text]) => (
              <button key={v} aria-pressed={view === v} onClick={() => setView(v)}
                className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium ${
                  view === v ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/40 dark:text-brand-200' : 'text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-gray-100'}`}>
                <Icon className="h-4 w-4" /> {text}
              </button>
            ))}
          </div>
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
      {reset.isError && <p className="mb-2 text-sm text-red-500">That part of your memory couldn’t be reset. Try again in a moment.</p>}
      {reset.isSuccess && (
        <p className="mb-2 text-sm text-gray-600 dark:text-gray-300">
          Forgotten: {reset.data.facts} {reset.data.facts === 1 ? 'fact' : 'facts'}, {reset.data.notes} {reset.data.notes === 1 ? 'note' : 'notes'}
          {reset.data.subjects > 0 && `, ${reset.data.subjects} ${reset.data.subjects === 1 ? 'moon' : 'moons'}`}.
        </p>
      )}
      {empty && view === 'list' && (
        <EmptyState icon={Brain} title="Nothing remembered yet"
          description="As you chat, Jizza and its agents remember what matters. Tell Jizza something about yourself to begin." />
      )}

      {snapshot.data && layout.bodies.length > 0 && view === 'sky' && (
        <div className="relative h-[calc(100vh-15rem)] min-h-[420px] overflow-hidden rounded-xl border border-gray-200 bg-[#03040a] dark:border-gray-800">
          <CosmosCanvas ref={canvas} layout={layout} links={snapshot.data.links} itemsByBody={itemsByBody}
            selectedBodyId={selectedBodyId} selectedItemKey={selected ? itemKey(selected) : null}
            highlight={highlight} matches={matches} dimmedAgentKeys={dimmedAgentKeys} drift={drift && !reducedMotion}
            paused={selected != null} speed={speed}
            onSelectBody={setSelectedBodyId} onSelectItem={n => { setSelected(n); setSelectedBodyId(n.parentBodyId) }}
            onNeedItems={needItems} />
          <Legend />
          {empty && (
            <div className="pointer-events-none absolute inset-x-0 top-4 mx-auto w-fit max-w-[90%] rounded-lg border border-gray-200 bg-white/90 px-4 py-2 text-center text-sm text-gray-600 shadow-sm backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300">
              Nothing remembered yet. As you chat, Jizza and its agents fill this sky with what matters.
            </div>
          )}
          {selectedBody && (
            <BodyCard body={selectedBody} count={systemCount(layout, selectedBody)}
              resetting={reset.isPending && reset.variables === selectedBody.body.bodyId}
              onZoom={() => flyToBody(selectedBody.body.bodyId)}
              onReset={() => askReset(selectedBody)}
              onClose={() => setSelectedBodyId(null)} />
          )}
          <div className="absolute bottom-3 right-3 flex flex-col gap-1">
            <CanvasButton label="Zoom in" onClick={() => canvas.current?.zoomBy(1.5)}><Plus className="h-4 w-4" /></CanvasButton>
            <CanvasButton label="Zoom out" onClick={() => canvas.current?.zoomBy(1 / 1.5)}><Minus className="h-4 w-4" /></CanvasButton>
            <CanvasButton label="Show everything" onClick={() => canvas.current?.fit()}><Maximize2 className="h-4 w-4" /></CanvasButton>
          </div>
          {!reducedMotion && (
            <div role="group" aria-label="Orbit speed"
              className="absolute right-3 top-3 flex items-center gap-0.5 rounded-lg border border-gray-700 bg-gray-900/85 p-0.5 text-xs text-gray-200 shadow-sm backdrop-blur">
              <button type="button" aria-label={drift ? 'Pause the orbits' : 'Play the orbits'} title={drift ? 'Pause the orbits' : 'Play the orbits'}
                onClick={() => setDrift(d => !d)} className="grid h-7 w-7 place-items-center rounded-md hover:bg-gray-800">
                {drift ? <Pause className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
              </button>
              {SPEEDS.map(s => (
                <button key={s} type="button" aria-pressed={drift && speed === s} title={`Orbit speed ${s}×`}
                  onClick={() => { setSpeed(s); setDrift(true) }}
                  className={`h-7 min-w-[2.25rem] rounded-md px-1.5 font-medium tabular-nums ${
                    drift && speed === s ? 'bg-brand-500/30 text-white' : 'text-gray-300 hover:bg-gray-800'}`}>
                  {s < 1 ? '¼' : s}×
                </button>
              ))}
            </div>
          )}
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
            matches={matches} onOpenItem={setSelected} onReset={askReset} />
        </div>
      )}

      <MemoryPassportDrawer item={selected ? { kind: selected.itemKind, id: selected.id } : null} agents={agents}
        onClose={() => setSelected(null)}
        onLocate={selected ? () => { const n = selected; setSelected(null); locate(n) } : undefined} />
      <MemoryTransferPanel open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  )
}

/** Facts and notes in a body and every moon under it; for the sun, everything. */
function systemCount(layout: CosmosLayout, b: PlacedBody): number {
  if (b.body.type === 'SUN') return layout.bodies.reduce((sum, x) => sum + x.body.memoryCount, 0)
  let n = b.body.memoryCount
  for (const c of layout.children.get(b.body.bodyId) ?? []) n += systemCount(layout, c)
  return n
}

function hasMoons(layout: CosmosLayout, b: PlacedBody) {
  return (layout.children.get(b.body.bodyId) ?? []).length > 0
}

const BODY_KIND: Record<string, string> = {
  SUN: 'The star · everything Jizza remembers', AGENT: 'Planet · an agent', SUBJECT: 'Moon · a person, place or thing',
  NOTES: 'Ice moon · this agent’s own notes', FILE: 'Asteroid · a file', CONNECTOR: 'Space station · a connector',
  WORKFLOW: 'Comet · a workflow', SKILL: 'Pulsar · a skill',
}

/** The selected body: what it is, how much is remembered there, and what you can do with it. */
function BodyCard({ body, count, resetting, onZoom, onReset, onClose }: {
  body: PlacedBody; count: number; resetting: boolean; onZoom: () => void; onReset: () => void; onClose: () => void
}) {
  const b = body.body
  // Procedures and connectors hold no memories of their own; nothing to reset there.
  const resettable = !['WORKFLOW', 'SKILL', 'CONNECTOR'].includes(b.type)
  const relation = typeof b.meta?.relation === 'string' ? b.meta.relation : null
  return (
    <div className="absolute left-3 top-3 w-64 rounded-xl border border-gray-200 bg-white/95 p-3 text-sm shadow-lg backdrop-blur dark:border-gray-700 dark:bg-gray-900/95">
      <div className="flex items-start gap-2">
        <span className="mt-1 h-3 w-3 shrink-0 rounded-full" style={{ background: `rgb(var(--agent-${b.agentKey}, var(--agent-other)))` }} />
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-gray-900 dark:text-gray-100">{b.label}{relation && <span className="font-normal text-gray-500"> · {relation}</span>}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">{BODY_KIND[b.type] ?? b.type}</p>
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="rounded p-0.5 text-gray-400 hover:text-gray-700 dark:hover:text-gray-200">
          <X className="h-4 w-4" />
        </button>
      </div>
      <p className="mt-2 text-gray-600 dark:text-gray-300">
        {count === 0 ? 'Nothing remembered here yet.' : `${count} ${count === 1 ? 'memory' : 'memories'}${b.type === 'AGENT' || b.type === 'SUN' ? ' in this system' : ''}`}
      </p>
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="secondary" onClick={onZoom}><ZoomIn className="h-4 w-4" /> Zoom in</Button>
        {resettable && count > 0 && (
          <Button size="sm" variant="danger" loading={resetting} onClick={onReset}>
            <RotateCcw className="h-4 w-4" /> {b.type === 'SUN' ? 'Reset all' : 'Reset'}
          </Button>
        )}
      </div>
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
    <details className="absolute bottom-3 left-3 max-w-[18rem] rounded-lg border border-gray-200 bg-white/90 px-3 py-2 text-xs text-gray-600 shadow-sm backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300">
      <summary className="cursor-pointer font-medium text-gray-800 dark:text-gray-100">How to read the sky</summary>
      <ul className="mt-2 flex flex-col gap-1">
        <li>Jizza is the star at the centre; each agent is a planet on its own elliptical orbit, moving faster as it swings closer in.</li>
        <li>Round each planet: moons are people, places and things, ice moons its notes, asteroids files, space stations connectors, comets workflows and pulsars skills.</li>
        <li>Specks of stardust are facts, shards are notes. Zoom in to see them.</li>
        <li>Point at anything for details and to hold the sky still; click it to fly there and follow it. Drag to let go.</li>
        <li>Bigger means agents use it more; faint means not used lately.</li>
        <li>A grey speck with a lock is restricted: no agent can use it.</li>
        <li>An amber dashed ring is waiting for you to confirm.</li>
      </ul>
    </details>
  )
}
