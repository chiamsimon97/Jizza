import { useEffect, useId, useRef, useState } from 'react'
import { Lock, Search, SlidersHorizontal, X } from 'lucide-react'
import type { CelestialBody, MemorySearchHit } from '@/types'
import { Spinner } from '@/components/ui/Spinner'

/** Waits until the user stops typing before the value changes. */
export function useDebounced<T>(value: T, ms = 250) {
  const [v, setV] = useState(value)
  useEffect(() => {
    const id = window.setTimeout(() => setV(value), ms)
    return () => window.clearTimeout(id)
  }, [value, ms])
  return v
}

interface Props {
  value: string
  onChange: (q: string) => void
  hits: MemorySearchHit[] | undefined
  loading: boolean
  bodies: Map<string, CelestialBody>
  onPick: (hit: MemorySearchHit) => void
  filtersOpen: boolean
  filterCount: number
  onToggleFilters: () => void
}

/**
 * Search across every fact and note, by meaning and by words. Results are listed here and lit up in
 * the sky (everything else fades back); picking one flies to it and opens its passport.
 */
export function CosmosSearchBar({ value, onChange, hits, loading, bodies, onPick, filtersOpen, filterCount, onToggleFilters }: Props) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const listId = useId()
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => { setActive(0) }, [hits])
  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (!wrapRef.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const showList = open && value.trim().length > 0
  const list = hits ?? []

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive(a => Math.min(a + 1, list.length - 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && list[active]) { e.preventDefault(); onPick(list[active]); setOpen(false) }
    else if (e.key === 'Escape') { if (showList) setOpen(false); else onChange('') }
  }

  return (
    <div ref={wrapRef} className="relative flex items-center gap-2">
      <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
        <input type="text" value={value} placeholder="Search everything Jizza remembers"
          role="combobox" aria-expanded={showList} aria-controls={listId} aria-autocomplete="list"
          aria-activedescendant={showList && list[active] ? `${listId}-${active}` : undefined}
          onChange={e => { onChange(e.target.value); setOpen(true) }}
          onFocus={() => setOpen(true)} onKeyDown={onKeyDown}
          className="w-full rounded-lg border border-gray-300 bg-white py-2 pl-9 pr-9 text-sm text-gray-900 shadow-sm placeholder-gray-400 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100" />
        {loading && <span className="absolute right-3 top-1/2 -translate-y-1/2"><Spinner size="sm" /></span>}
        {!loading && value && (
          <button onClick={() => onChange('')} aria-label="Clear search"
            className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-gray-400 hover:text-gray-700 dark:hover:text-gray-200">
            <X className="h-3.5 w-3.5" />
          </button>
        )}
        {showList && (
          <ul id={listId} role="listbox" aria-label="Search results"
            className="absolute left-0 right-0 top-full z-20 mt-1 max-h-80 overflow-y-auto rounded-lg border border-gray-200 bg-white py-1 shadow-xl dark:border-gray-800 dark:bg-gray-900">
            {!loading && list.length === 0 && <li className="px-3 py-2 text-sm text-gray-500">Nothing matches.</li>}
            {list.map((hit, i) => (
              <li key={`${hit.item.itemKind}:${hit.item.id}`} id={`${listId}-${i}`} role="option" aria-selected={i === active}
                onMouseEnter={() => setActive(i)}
                onMouseDown={e => { e.preventDefault(); onPick(hit); setOpen(false) }}
                className={`cursor-pointer px-3 py-2 ${i === active ? 'bg-brand-50 dark:bg-brand-950/40' : ''}`}>
                <div className="flex items-center gap-2 text-sm text-gray-900 dark:text-gray-100">
                  <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: `rgb(var(--agent-${hit.item.agentKey}, var(--agent-other)))` }} />
                  {hit.item.redacted
                    ? <span className="flex items-center gap-1 text-gray-500"><Lock className="h-3 w-3" /> Restricted memory</span>
                    : <span className="truncate">{hit.snippet ?? hit.item.label}</span>}
                  <span className="ml-auto shrink-0 text-xs text-gray-400">{hit.item.itemKind}</span>
                </div>
                <p className="mt-0.5 truncate pl-4 text-xs text-gray-500 dark:text-gray-400">
                  {hit.bodyPath.map(id => bodies.get(id)?.label ?? '…').join(' › ')}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
      <button onClick={onToggleFilters} aria-pressed={filtersOpen}
        className={`relative inline-flex h-9 items-center gap-1.5 rounded-lg border px-3 text-sm font-medium transition-colors ${
          filtersOpen || filterCount
            ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/40 dark:text-brand-200'
            : 'border-gray-300 bg-white text-gray-700 hover:border-gray-400 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200'}`}>
        <SlidersHorizontal className="h-4 w-4" /> Filters{filterCount ? ` · ${filterCount}` : ''}
      </button>
    </div>
  )
}
