import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Braces, Plus, Search } from 'lucide-react'
import { functionsApi, type ScriptFunction, type ScriptFunctionDraft } from '@/api/functions'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { fieldClass } from '@/components/ui/SlideOver'
import { FunctionEditor } from '@/components/functions/FunctionEditor'
import { cleanDraft, draftOf, emptyDraft } from '@/components/functions/functionModel'

/**
 * Functions: a few lines of Jizza script kept on their own, with typed inputs and a return type.
 * Workflows run one from a Script step and map its inputs; functions call each other as fn.<name>(…).
 * /functions/new makes one; /functions/<id> edits it.
 */
export function FunctionsPage() {
  const { functionId } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({ queryKey: ['functions'], queryFn: () => functionsApi.list() })
  const [query, setQuery] = useState('')
  const creating = functionId === 'new'
  const current = data?.find(f => f.id === functionId)

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data ?? []).filter(f => !q || f.name.toLowerCase().includes(q) || (f.description ?? '').toLowerCase().includes(q))
  }, [data, query])

  return (
    <div className="page">
      <PageHeader icon={Braces} title="Functions"
        description="Reusable bits of logic with inputs and a return value. Run them from a workflow's Script step, or call one from another."
        actions={<Button size="sm" onClick={() => navigate('/functions/new')}><Plus className="h-4 w-4" />New function</Button>} />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-600">Could not load functions: {(error as Error).message}</p>}

      {data && data.length === 0 && !creating && (
        <EmptyState icon={Braces} title="No functions yet"
          description="Write a function once, such as an order total with tax, and use it in any workflow or from other functions."
          action={{ label: 'New function', onClick: () => navigate('/functions/new') }} />
      )}

      {data && (data.length > 0 || creating) && (
        <div className="grid min-w-0 gap-4 lg:grid-cols-[16rem_minmax(0,1fr)]">
          <Card padding="sm" className="flex h-fit min-w-0 flex-col gap-2">
            <label className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
              <input aria-label="Search functions" placeholder="Search" className={`${fieldClass} !pl-8 text-sm`}
                value={query} onChange={e => setQuery(e.target.value)} />
            </label>
            <ul className="flex flex-col gap-0.5" aria-label="Your functions">
              {shown.map(f => (
                <li key={f.id}>
                  <Link to={`/functions/${f.id}`} aria-current={f.id === functionId ? 'page' : undefined}
                    className={`block rounded-lg px-2 py-1.5 text-sm ${f.id === functionId ? 'bg-brand-50 text-brand-700 dark:bg-brand-500/10 dark:text-brand-200' : 'text-gray-700 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
                    <span className="block truncate font-mono text-xs">fn.{f.name}</span>
                    <span className="block truncate text-[11px] text-gray-500">
                      {f.inputs.length} input{f.inputs.length === 1 ? '' : 's'} → {f.returnType}
                      {f.usedBy.length > 0 && ` · in ${f.usedBy.length} workflow${f.usedBy.length === 1 ? '' : 's'}`}
                    </span>
                  </Link>
                </li>
              ))}
              {shown.length === 0 && <li className="px-2 py-1.5 text-xs text-gray-500">No function matches.</li>}
            </ul>
          </Card>

          <div className="min-w-0">
            {creating && <Editing key="new" all={data} onSaved={f => { qc.invalidateQueries({ queryKey: ['functions'] }); navigate(`/functions/${f.id}`, { replace: true }) }} />}
            {current && <Editing key={current.id} all={data} saved={current}
              onSaved={() => qc.invalidateQueries({ queryKey: ['functions'] })}
              onDeleted={() => { qc.invalidateQueries({ queryKey: ['functions'] }); navigate('/functions', { replace: true }) }} />}
            {!creating && !current && functionId && <p className="text-sm text-gray-500">That function no longer exists.</p>}
            {!creating && !functionId && data.length > 0 && (
              <p className="rounded-xl border border-dashed border-gray-300 p-8 text-center text-sm text-gray-500 dark:border-gray-700">
                Pick a function to change or test it, or make a new one.
              </p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function Editing({ all, saved, onSaved, onDeleted }: {
  all: ScriptFunction[]; saved?: ScriptFunction; onSaved: (f: ScriptFunction) => void; onDeleted?: () => void
}) {
  const [draft, setDraft] = useState<ScriptFunctionDraft>(() => (saved ? draftOf(saved) : emptyDraft()))
  const [confirming, setConfirming] = useState(false)
  // A save (or a rename elsewhere) brings the stored version in; a refetch of the same version keeps unsaved edits.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { if (saved) setDraft(draftOf(saved)) }, [saved?.updatedAt])
  const others = all.filter(f => f.id !== saved?.id)
  const save = useMutation({
    mutationFn: () => (saved ? functionsApi.update(saved.id, cleanDraft(draft)) : functionsApi.create(cleanDraft(draft))),
    onSuccess: onSaved,
  })
  const remove = useMutation({ mutationFn: () => functionsApi.remove(saved!.id), onSuccess: () => onDeleted?.() })

  return <>
    <FunctionEditor draft={draft} onChange={setDraft} others={others} saved={saved}
      saving={save.isPending} error={(save.error as Error | null)?.message ?? (remove.error as Error | null)?.message}
      onSave={() => save.mutate()} onDelete={saved ? () => setConfirming(true) : undefined} />
    <Dialog open={confirming} title={`Delete fn.${saved?.name ?? ''}?`} onClose={() => setConfirming(false)}
      description={saved && saved.usedBy.length > 0
        ? `The workflows ${saved.usedBy.map(n => `"${n}"`).join(', ')} run it, and their Script steps will fail until you pick another function.`
        : 'Functions that call it will fail until you change them.'}>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={() => setConfirming(false)}>Cancel</Button>
        <Button variant="danger" loading={remove.isPending} onClick={() => { setConfirming(false); remove.mutate() }}>Delete</Button>
      </div>
    </Dialog>
  </>
}
