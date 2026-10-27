import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Check, Download, Search } from 'lucide-react'
import { functionsApi } from '@/api/functions'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Spinner } from '@/components/ui/Spinner'
import { fieldClass } from '@/components/ui/SlideOver'

/**
 * Explore public functions: every function Jizza ships, with what it does. Hovering one offers
 * Install, which adds it to the person's Functions, where workflows and other functions can use it.
 */
export function ExplorePublicFunctions() {
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({ queryKey: ['functions', 'public'], queryFn: () => functionsApi.explore() })
  const [query, setQuery] = useState('')
  const install = useMutation({
    mutationFn: (id: string) => functionsApi.install(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['functions'] }),
  })

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data ?? []).filter(({ function: f }) => !q || f.name.toLowerCase().includes(q) || (f.description ?? '').toLowerCase().includes(q))
  }, [data, query])

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Link to="/functions" className="inline-flex items-center gap-1 text-sm text-brand-600 hover:underline dark:text-brand-300">
          <ArrowLeft className="h-4 w-4" />Back to your functions
        </Link>
        <label className="relative w-full sm:w-64">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
          <input aria-label="Search public functions" placeholder="Search" className={`${fieldClass} !pl-8 text-sm`}
            value={query} onChange={e => setQuery(e.target.value)} />
        </label>
      </div>
      <div>
        <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Explore public functions</h2>
        <p className="text-sm text-gray-500">Ready-made functions from Jizza. Install one to use it in your workflows and functions.</p>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-600">Could not load public functions: {(error as Error).message}</p>}
      {install.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{(install.error as Error).message}</p>}

      <ul aria-label="Public functions" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {shown.map(({ function: f, installed }) => (
          <li key={f.id}>
            <Card className="group relative flex h-full flex-col gap-1.5">
              <span className="truncate font-mono text-sm font-semibold text-gray-900 dark:text-gray-100">fn.{f.name}</span>
              <span className="truncate font-mono text-[11px] text-gray-500">({f.inputs.map(i => i.name).join(', ')}) → {f.returnType}</span>
              {f.description && <p className="text-sm text-gray-600 dark:text-gray-300">{f.description}</p>}
              <div className="mt-auto flex items-center gap-2 pt-2">
                {installed ? (
                  <>
                    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300">
                      <Check className="h-3 w-3" />Installed
                    </span>
                    <Link to={`/functions/${f.id}`} className="text-xs text-brand-600 hover:underline dark:text-brand-300">Open</Link>
                  </>
                ) : (
                  <Button size="sm" aria-label={`Install fn.${f.name}`} loading={install.isPending && install.variables === f.id}
                    onClick={() => install.mutate(f.id)}
                    className="opacity-100 transition-opacity focus-visible:opacity-100 sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100">
                    <Download className="h-3.5 w-3.5" />Install
                  </Button>
                )}
              </div>
            </Card>
          </li>
        ))}
      </ul>
      {data && shown.length === 0 && <p className="text-sm text-gray-500">No public function matches.</p>}
    </div>
  )
}
