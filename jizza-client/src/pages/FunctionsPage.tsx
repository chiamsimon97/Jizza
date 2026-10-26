import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Braces, Compass, Copy, Globe, Plus, Search, Trash2 } from 'lucide-react'
import { functionsApi, type ScriptFunction, type ScriptFunctionDraft } from '@/api/functions'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { fieldClass } from '@/components/ui/SlideOver'
import { ExplorePublicFunctions } from '@/components/functions/ExplorePublicFunctions'
import { FunctionEditor, TestPanel } from '@/components/functions/FunctionEditor'
import { cleanDraft, copyOf, draftOf, emptyDraft } from '@/components/functions/functionModel'

/**
 * Functions: a few lines of Jizza script kept on their own, with typed inputs and a return type.
 * Workflows run one from a Script step and map its inputs; functions call each other as fn.<name>(…).
 * /functions/new makes one; /functions/<id> edits it. /functions/explore lists Jizza's public
 * functions to install; installed ones are listed after the person's own, to read, test, call, copy
 * or uninstall.
 */
export function FunctionsPage() {
  const { functionId } = useParams()
  const navigate = useNavigate()
  const copied = (useLocation().state as { draft?: ScriptFunctionDraft } | null)?.draft
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({ queryKey: ['functions'], queryFn: () => functionsApi.list() })
  const [query, setQuery] = useState('')
  const creating = functionId === 'new'
  const exploring = functionId === 'explore'
  const current = data?.find(f => f.id === functionId)

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data ?? []).filter(f => !q || f.name.toLowerCase().includes(q) || (f.description ?? '').toLowerCase().includes(q))
  }, [data, query])
  const groups = [
    { label: 'Your functions', items: shown.filter(f => f.scope !== 'public') },
    { label: 'Installed', items: shown.filter(f => f.scope === 'public') },
  ]

  return (
    <div className="page">
      <PageHeader icon={Braces} title="Functions"
        description="Reusable bits of logic with inputs and a return value. Run them from a workflow's Script step, or call one from another."
        actions={<div className="flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" onClick={() => navigate('/functions/explore')}><Compass className="h-4 w-4" />Explore public functions</Button>
          <Button size="sm" onClick={() => navigate('/functions/new')}><Plus className="h-4 w-4" />New function</Button>
        </div>} />

      {exploring && <ExplorePublicFunctions />}

      {!exploring && isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-600">Could not load functions: {(error as Error).message}</p>}

      {!exploring && data && data.length === 0 && !creating && (
        <EmptyState icon={Braces} title="No functions yet"
          description="Write a function once, such as an order total with tax, or install a ready-made one from Explore public functions, and use it in any workflow or from other functions."
          action={{ label: 'Explore public functions', onClick: () => navigate('/functions/explore') }} />
      )}

      {!exploring && data && (data.length > 0 || creating) && (
        <div className="grid min-w-0 gap-4 lg:grid-cols-[16rem_minmax(0,1fr)]">
          <Card padding="sm" className="flex h-fit min-w-0 flex-col gap-2">
            <label className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
              <input aria-label="Search functions" placeholder="Search" className={`${fieldClass} !pl-8 text-sm`}
                value={query} onChange={e => setQuery(e.target.value)} />
            </label>
            {groups.map(g => g.items.length > 0 && (
            <div key={g.label} className="flex flex-col gap-0.5">
            <h3 className="px-2 pt-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{g.label}</h3>
            <ul className="flex flex-col gap-0.5" aria-label={g.label}>
              {g.items.map(f => (
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
            </ul>
            </div>
            ))}
            {shown.length === 0 && <p className="px-2 py-1.5 text-xs text-gray-500">No function matches.</p>}
          </Card>

          <div className="min-w-0">
            {creating && <Editing key={copied ? `copy-${copied.name}` : 'new'} all={data} initial={copied} onSaved={f => { qc.invalidateQueries({ queryKey: ['functions'] }); navigate(`/functions/${f.id}`, { replace: true }) }} />}
            {current && current.scope === 'public' && <PublicFunction key={current.id} fn={current}
              onCopy={() => navigate('/functions/new', { state: { draft: copyOf(current, data.map(f => f.name)) } })}
              onUninstalled={() => { qc.invalidateQueries({ queryKey: ['functions'] }); navigate('/functions', { replace: true }) }} />}
            {current && current.scope !== 'public' && <Editing key={current.id} all={data} saved={current}
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

/** An installed public function: what it takes, its script and what it returns, to read, test, copy or uninstall. */
function PublicFunction({ fn, onCopy, onUninstalled }: { fn: ScriptFunction; onCopy: () => void; onUninstalled: () => void }) {
  const [confirming, setConfirming] = useState(false)
  const uninstall = useMutation({ mutationFn: () => functionsApi.uninstall(fn.id), onSuccess: onUninstalled })
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <Card className="flex flex-col gap-2">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h2 className="font-mono text-sm font-semibold text-gray-900 dark:text-gray-100">
              fn.{fn.name}({fn.inputs.map(i => i.name).join(', ')}) → {fn.returnType}
            </h2>
            {fn.description && <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">{fn.description}</p>}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" onClick={onCopy}><Copy className="h-3.5 w-3.5" />Make a copy</Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirming(true)}><Trash2 className="h-3.5 w-3.5" />Uninstall</Button>
          </div>
        </div>
        <p className="inline-flex items-center gap-1 text-[11px] text-gray-500">
          <Globe className="h-3 w-3" />A public function from Jizza, installed for you. Call it from any function or Script step; copy it to change it.
        </p>
        {uninstall.error && <p role="alert" className="text-xs text-red-600 dark:text-red-400">{(uninstall.error as Error).message}</p>}
        {fn.usedBy.length > 0 && (
          <p className="text-[11px] text-gray-500">Used by {fn.usedBy.length === 1 ? 'the workflow' : 'the workflows'} {fn.usedBy.map(n => `"${n}"`).join(', ')}.</p>
        )}
      </Card>
      {fn.inputs.length > 0 && (
        <Card className="flex flex-col gap-1.5">
          <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Inputs</h3>
          {fn.inputs.map(i => (
            <p key={i.name} className="text-xs text-gray-700 dark:text-gray-300">
              <span className="font-mono">{i.name}</span>
              <span className="text-gray-500"> · {i.type}{i.required ? ' · required' : ''}{i.default !== undefined ? ` · default ${JSON.stringify(i.default)}` : ''}</span>
              {i.description && <span className="text-gray-500"> · {i.description}</span>}
            </p>
          ))}
        </Card>
      )}
      <Card className="flex flex-col gap-2">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">Script</h3>
        <pre className="max-h-96 overflow-auto rounded-lg bg-gray-50 p-3 font-mono text-xs text-gray-800 dark:bg-gray-800/50 dark:text-gray-200">{fn.script}</pre>
        {fn.returnType === 'map' && fn.returnFields.length > 0 && (
          <p className="text-[11px] text-gray-500">Returns the keys {fn.returnFields.map(f => f.path).join(', ')}.</p>
        )}
      </Card>
      <TestPanel draft={draftOf(fn)} />
      <Dialog open={confirming} title={`Uninstall fn.${fn.name}?`} onClose={() => setConfirming(false)}
        description={fn.usedBy.length > 0
          ? `The workflows ${fn.usedBy.map(n => `"${n}"`).join(', ')} run it, and their Script steps will fail until you install it again.`
          : 'Functions that call it will fail until you install it again from Explore public functions.'}>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirming(false)}>Cancel</Button>
          <Button variant="danger" loading={uninstall.isPending} onClick={() => { setConfirming(false); uninstall.mutate() }}>Uninstall</Button>
        </div>
      </Dialog>
    </div>
  )
}

function Editing({ all, saved, initial, onSaved, onDeleted }: {
  all: ScriptFunction[]; saved?: ScriptFunction; initial?: ScriptFunctionDraft; onSaved: (f: ScriptFunction) => void; onDeleted?: () => void
}) {
  const [draft, setDraft] = useState<ScriptFunctionDraft>(() => (saved ? draftOf(saved) : initial ?? emptyDraft()))
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
