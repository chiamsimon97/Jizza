import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Briefcase, Check, Download, HeartPulse, KeyRound, LayoutGrid, Search, TrendingUp, User } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { mcpApi } from '@/api/mcp'
import type { McpCatalogCategory, McpCatalogEntry, McpServer } from '@/types'
import { Empty, SectionHeader } from './shared'

export const CATEGORY_LABELS: Record<McpCatalogCategory, string> = {
  personal: 'Personal',
  professional: 'Professional',
  finance: 'Finance and investing',
  health: 'Health and medical',
}

const CATEGORY_ICONS: Record<McpCatalogCategory, LucideIcon> = {
  personal: User,
  professional: Briefcase,
  finance: TrendingUp,
  health: HeartPulse,
}

const CATEGORY_TINT: Record<McpCatalogCategory, string> = {
  personal: 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-200',
  professional: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-200',
  finance: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200',
  health: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200',
}

/** How a catalog server signs in, in a few words for its card. */
export function signInLabel(entry: McpCatalogEntry): string {
  if (entry.ownClient) return 'Your own OAuth client'
  switch (entry.authType) {
    case 'NONE': return 'No sign-in'
    case 'OAUTH': return `Sign in with ${entry.publisher}`
    default: return entry.secretLabel ?? 'Needs a key'
  }
}

/** Trailing slashes and case aside, the same address. */
export function sameUrl(a: string, b: string): boolean {
  const norm = (u: string) => u.trim().toLowerCase().replace(/\/+$/, '')
  return norm(a) === norm(b)
}

/** The catalog's servers, filtered by category and a search. Pure, for the tests. */
export function filterCatalog(entries: McpCatalogEntry[], category: McpCatalogCategory | 'all', query: string): McpCatalogEntry[] {
  const q = query.trim().toLowerCase()
  return entries.filter(e => (category === 'all' || e.category === category)
    && (!q || [e.name, e.publisher, e.description].some(s => s.toLowerCase().includes(q))))
}

/** Ready-made MCP servers from popular services, each installed into the current account in one click. */
export function McpCatalogSection({ installed, canInstall, onInstall }: {
  installed: McpServer[]
  canInstall: boolean
  onInstall: (entry: McpCatalogEntry, redirectUri: string | null) => void
}) {
  const { data, isLoading } = useQuery({ queryKey: ['mcp-catalog'], queryFn: () => mcpApi.catalog(), staleTime: Infinity })
  const [category, setCategory] = useState<McpCatalogCategory | 'all'>('all')
  const [query, setQuery] = useState('')
  const entries = data?.entries ?? []
  const shown = useMemo(() => filterCatalog(entries, category, query), [entries, category, query])
  const counts = useMemo(() => {
    const c: Partial<Record<McpCatalogCategory, number>> = {}
    for (const e of entries) c[e.category] = (c[e.category] ?? 0) + 1
    return c
  }, [entries])
  const tabs: Array<{ key: McpCatalogCategory | 'all'; label: string; icon: LucideIcon; count: number }> = [
    { key: 'all', label: 'All', icon: LayoutGrid, count: entries.length },
    ...(Object.keys(CATEGORY_LABELS) as McpCatalogCategory[]).map(k => ({ key: k, label: CATEGORY_LABELS[k], icon: CATEGORY_ICONS[k], count: counts[k] ?? 0 })),
  ]

  return (
    <section className="mt-10">
      <SectionHeader title="Ready-made MCP servers"
        description="Popular public servers from the services themselves. Install one into the account you're in, sign in if it asks, and choose which agents may use it." />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        {tabs.map(t => {
          const Icon = t.icon
          const on = category === t.key
          return (
            <button key={t.key} type="button" onClick={() => setCategory(t.key)} aria-pressed={on}
              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm ${on ? 'border-brand-400 bg-brand-50 text-brand-800 dark:bg-brand-500/10 dark:text-brand-200' : 'border-gray-200 text-gray-600 hover:border-brand-300 dark:border-gray-700 dark:text-gray-300'}`}>
              <Icon className="h-3.5 w-3.5" />{t.label}<span className="text-xs text-gray-400">{t.count}</span>
            </button>
          )
        })}
        <label className="relative ml-auto w-full sm:w-56">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search servers" aria-label="Search ready-made servers"
            className="w-full rounded-lg border border-gray-200 bg-white py-1.5 pl-8 pr-3 text-sm text-gray-900 placeholder:text-gray-400 focus:border-brand-400 focus:outline-none dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100" />
        </label>
      </div>

      {isLoading && <div className="flex justify-center py-8"><Spinner /></div>}
      {!isLoading && shown.length === 0 && <Empty>No ready-made server matches that.</Empty>}
      {shown.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {shown.map(entry => {
            const Icon = CATEGORY_ICONS[entry.category]
            const isInstalled = installed.some(s => sameUrl(s.url, entry.url))
            return (
              <Card key={entry.id} padding="md" className="flex flex-col">
                <div className="flex items-start gap-3">
                  <div className={`grid h-9 w-9 shrink-0 place-items-center rounded-lg ${CATEGORY_TINT[entry.category]}`}>
                    <Icon className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-semibold text-gray-900 dark:text-gray-100">{entry.name}</p>
                    <p className="truncate text-xs text-gray-500 dark:text-gray-400">{entry.publisher}</p>
                  </div>
                  {isInstalled && <Badge variant="success"><Check className="mr-1 h-3 w-3" />Installed</Badge>}
                </div>
                <p className="mt-2 flex-1 text-sm text-gray-600 dark:text-gray-300">{entry.description}</p>
                <div className="mt-3 flex items-center gap-2">
                  <span className="inline-flex min-w-0 flex-1 items-center gap-1 truncate text-xs text-gray-500 dark:text-gray-400">
                    <KeyRound className="h-3.5 w-3.5 shrink-0" />{signInLabel(entry)}
                  </span>
                  {canInstall && (
                    <Button size="sm" variant={isInstalled ? 'ghost' : 'secondary'} onClick={() => onInstall(entry, data?.redirectUri ?? null)}>
                      <Download className="h-4 w-4" />{isInstalled ? 'Install again' : 'Install'}
                    </Button>
                  )}
                </div>
              </Card>
            )
          })}
        </div>
      )}
    </section>
  )
}
