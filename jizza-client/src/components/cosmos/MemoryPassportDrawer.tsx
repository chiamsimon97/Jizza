import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Archive, ArrowUpRight, Check, Crosshair, Eye, Lock, MoveRight, Pencil, RotateCcw, Sparkles, Trash2, Unlock,
} from 'lucide-react'
import { SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { cosmosApi } from '@/api/cosmos'
import { ApiError } from '@/lib/api'
import type { ItemKind, MemoryCategory, Passport, PassportStamp } from '@/types'
import { CATEGORIES, label, type CosmosAgentOption } from './CosmosFilters'

interface Props {
  item: { kind: ItemKind; id: string } | null
  agents: CosmosAgentOption[]
  onClose: () => void
  /** Shows the item in the sky, when the sky is on screen. */
  onLocate?: () => void
}

const CONTEXT: Record<string, string> = {
  chat_turn: 'in a chat', task: 'for a task', workflow_run: 'in a workflow', agent_run: 'on its own',
  memory_review: 'from a memory review', job: 'while working on a request',
}
const ACTION: Record<string, string> = {
  created: 'Created', updated: 'Edited', confirmed: 'Confirmed', retired: 'Retired', restored: 'Restored',
  superseded: 'Replaced by a newer memory', shared: 'Shared', unshared: 'Unshared', moved: 'Moved',
  restricted: 'Restricted', unrestricted: 'Unrestricted', read: 'Read',
}

/**
 * A memory's passport: what it says, which agents can use it, where it came from, and every time it
 * was used or changed. Every change Jizza makes to who can see something happens from here.
 */
export function MemoryPassportDrawer({ item, agents, onClose, onLocate }: Props) {
  const qc = useQueryClient()
  const [revealed, setRevealed] = useState<Passport | null>(null)
  const [extraStamps, setExtraStamps] = useState<PassportStamp[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [mode, setMode] = useState<'view' | 'edit' | 'move' | 'promote'>('view')
  const [error, setError] = useState('')

  const queryKey = ['cosmos', 'passport', item?.kind, item?.id]
  const { data, isLoading, error: loadError } = useQuery({
    queryKey,
    queryFn: () => cosmosApi.passport(item!.kind, item!.id),
    enabled: !!item,
  })

  // Revealed content lives only in this drawer, never in the query cache, and goes when it closes.
  useEffect(() => {
    setRevealed(null); setExtraStamps([]); setNextCursor(null); setMode('view'); setError('')
  }, [item?.kind, item?.id])
  useEffect(() => { setNextCursor(data?.nextCursor ?? null); setExtraStamps([]) }, [data])

  const refresh = () => qc.invalidateQueries({ queryKey: ['cosmos'] })
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onMutate: () => setError(''),
    onSuccess: () => { setMode('view'); refresh() },
    onError: (e: unknown) => setError(e instanceof ApiError || e instanceof Error ? e.message : 'That didn’t work'),
  })
  const reveal = useMutation({
    mutationFn: () => cosmosApi.reveal(item!.kind, item!.id),
    onMutate: () => setError(''),
    onSuccess: p => setRevealed(p),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Could not reveal it'),
  })
  const more = useMutation({
    mutationFn: () => cosmosApi.passport(item!.kind, item!.id, nextCursor),
    onSuccess: p => { setExtraStamps(s => [...s, ...p.stamps]); setNextCursor(p.nextCursor) },
  })

  if (!item) return null
  const p = data
  const node = p?.item
  const isFact = item.kind === 'fact'
  const title = revealed?.title ?? p?.title
  const content = revealed?.content ?? p?.content
  const hidden = !!node?.redacted && !revealed
  const ownerId = p?.origin.agentId ?? undefined
  const busy = act.isPending

  const run = (fn: () => Promise<unknown>) => act.mutate(fn)
  const forget = () => {
    if (!window.confirm('Forget this for good? No agent will be able to use it again, and it can’t be undone.')) return
    act.mutate(async () => {
      if (isFact) await cosmosApi.forgetFact(item.id)
      else await cosmosApi.forgetNote(ownerId!, item.id)
      onClose()
    })
  }

  return (
    <SlideOver open title={isFact ? 'Memory' : 'Note'} onClose={onClose}
      description={node ? `${isFact ? label(node.category ?? 'general') : 'Saved by an agent'} · added ${new Date(node.createdAt).toLocaleDateString()}` : undefined}>
      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {loadError && <p className="text-sm text-red-500">This memory couldn’t be loaded. It may have been forgotten.</p>}
      {p && node && (
        <div className="flex flex-col gap-6">
          {/* What it says */}
          <section className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-1.5">
              {node.restricted && <Badge variant="danger"><Lock className="mr-1 h-3 w-3" />Restricted</Badge>}
              {node.status === 'pending' && <Badge variant="warning">Waiting for you to confirm</Badge>}
              {(node.status === 'retired' || node.status === 'superseded') && <Badge>{label(node.status)}</Badge>}
              {isFact && node.confirmed && node.status === 'active' && <Badge variant="success">Confirmed</Badge>}
              {node.classification && node.classification !== 'none' && (
                <Badge variant="warning">{node.classification === 'pii' ? 'Personal' : label(node.classification)}</Badge>
              )}
              <Badge variant="info">Used {node.accessCount} {node.accessCount === 1 ? 'time' : 'times'}</Badge>
            </div>
            {hidden ? (
              <div className="flex items-center gap-3 rounded-lg border border-dashed border-gray-300 p-4 dark:border-gray-700">
                <Lock className="h-5 w-5 text-gray-400" />
                <p className="flex-1 text-sm text-gray-500 dark:text-gray-400">
                  Restricted. No agent can use this, and its content stays hidden until you confirm it’s you.
                </p>
                <Button size="sm" variant="secondary" loading={reveal.isPending} onClick={() => reveal.mutate()}>
                  <Eye className="h-3.5 w-3.5" /> Reveal
                </Button>
              </div>
            ) : mode === 'edit' ? (
              <EditForm isFact={isFact} title={title ?? ''} content={content ?? ''} busy={busy}
                onCancel={() => setMode('view')}
                onSave={(t, c) => run(() => isFact ? cosmosApi.correctFact(item.id, c) : cosmosApi.editNote(ownerId!, item.id, t, c))} />
            ) : (
              <div>
                {title && <p className="font-medium text-gray-900 dark:text-gray-100">{title}</p>}
                {content && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-700 dark:text-gray-300">{content}</p>}
              </div>
            )}
          </section>

          {/* Who can use it */}
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">Who can use it</h3>
            {isFact
              ? <FactVisibility passport={p} agents={agents} busy={busy} moving={mode === 'move'}
                  onShare={(a, on) => run(() => on ? cosmosApi.shareFact(item.id, a) : cosmosApi.unshareFact(item.id, a))}
                  onMove={domains => run(() => cosmosApi.moveFact(item.id, domains))}
                  onStartMove={() => setMode('move')} onCancelMove={() => setMode('view')} />
              : <NoteVisibility passport={p} agents={agents} busy={busy} moving={mode === 'move'}
                  onMove={to => run(() => cosmosApi.moveNote(ownerId!, item.id, to))}
                  onStartMove={() => setMode('move')} onCancelMove={() => setMode('view')} />}
          </section>

          {/* Where it came from */}
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">Where it came from</h3>
            <p className="text-sm text-gray-700 dark:text-gray-300">
              {p.origin.label ?? label(p.origin.kind)}
              {p.origin.conversationId && (
                <> · <Link className="text-brand-600 hover:underline dark:text-brand-300" to={`/chat?conversationId=${p.origin.conversationId}`}>
                  Open the conversation <ArrowUpRight className="inline h-3 w-3" /></Link></>
              )}
            </p>
          </section>

          {mode === 'promote' && !isFact && (
            <PromoteForm busy={busy} onCancel={() => setMode('view')}
              onPromote={(category, removeNote) => run(async () => {
                await cosmosApi.promoteNote(ownerId!, item.id, category, removeNote)
                if (removeNote) onClose()
              })} />
          )}

          {error && <p role="alert" className="text-sm text-red-500">{error}</p>}

          {/* What you can do */}
          {mode === 'view' && (
            <section className="flex flex-wrap gap-2">
              {onLocate && <Button size="sm" variant="ghost" onClick={onLocate}><Crosshair className="h-3.5 w-3.5" /> Show in sky</Button>}
              {!hidden && <Button size="sm" variant="secondary" onClick={() => setMode('edit')}><Pencil className="h-3.5 w-3.5" /> Edit</Button>}
              {isFact && (node.status === 'pending' || (!node.confirmed && node.status === 'active')) && (
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => run(() => cosmosApi.confirmFact(item.id))}>
                  <Check className="h-3.5 w-3.5" /> Confirm
                </Button>
              )}
              {isFact && (node.status === 'active' || node.status === 'pending') && (
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => run(() => cosmosApi.retireFact(item.id))}>
                  <Archive className="h-3.5 w-3.5" /> Retire
                </Button>
              )}
              {isFact && (node.status === 'retired' || node.status === 'superseded') && (
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => run(() => cosmosApi.restoreFact(item.id))}>
                  <RotateCcw className="h-3.5 w-3.5" /> Restore
                </Button>
              )}
              {!isFact && !hidden && (
                <Button size="sm" variant="secondary" onClick={() => setMode('promote')}>
                  <Sparkles className="h-3.5 w-3.5" /> Make it a shared memory
                </Button>
              )}
              {node.restricted ? (
                <Button size="sm" variant="secondary" disabled={busy}
                  onClick={() => run(() => isFact ? cosmosApi.restrictFact(item.id, false) : cosmosApi.restrictNote(ownerId!, item.id, false))}>
                  <Unlock className="h-3.5 w-3.5" /> Let agents use it again
                </Button>
              ) : (
                <Button size="sm" variant="secondary" disabled={busy}
                  onClick={() => run(async () => {
                    setRevealed(null)
                    await (isFact ? cosmosApi.restrictFact(item.id, true) : cosmosApi.restrictNote(ownerId!, item.id, true))
                  })}>
                  <Lock className="h-3.5 w-3.5" /> Restrict
                </Button>
              )}
              <Button size="sm" variant="danger" disabled={busy} onClick={forget}><Trash2 className="h-3.5 w-3.5" /> Forget</Button>
            </section>
          )}

          {/* Stamps */}
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">History</h3>
            <ol className="flex flex-col gap-2 border-l border-gray-200 pl-4 dark:border-gray-800">
              {[...p.stamps, ...extraStamps].map((s, i) => (
                <li key={`${s.at}-${i}`} className="relative text-sm">
                  <span className={`absolute -left-[21px] top-1.5 h-2 w-2 rounded-full ${s.action === 'read' ? 'bg-accent-500' : 'bg-brand-500'}`} />
                  <span className="text-gray-900 dark:text-gray-100">{ACTION[s.action] ?? label(s.action)}</span>
                  <span className="text-gray-500 dark:text-gray-400">
                    {s.actorLabel ? ` by ${s.actorLabel}` : ''}{s.contextKind ? ` ${CONTEXT[s.contextKind] ?? ''}` : ''}{s.note ? ` (${s.note})` : ''}
                  </span>
                  <span className="block text-xs text-gray-400">{new Date(s.at).toLocaleString()}</span>
                </li>
              ))}
            </ol>
            {nextCursor && (
              <Button size="sm" variant="ghost" className="mt-2" loading={more.isPending} onClick={() => more.mutate()}>Show older</Button>
            )}
          </section>
        </div>
      )}
    </SlideOver>
  )
}

function EditForm({ isFact, title, content, busy, onSave, onCancel }: {
  isFact: boolean; title: string; content: string; busy: boolean
  onSave: (title: string, content: string) => void; onCancel: () => void
}) {
  const [t, setT] = useState(title)
  const [c, setC] = useState(content)
  return (
    <form className="flex flex-col gap-2" onSubmit={e => { e.preventDefault(); onSave(t, c) }}>
      {!isFact && <input className={fieldClass} value={t} maxLength={200} onChange={e => setT(e.target.value)} aria-label="Title" />}
      <textarea className={fieldClass} rows={5} value={c} maxLength={isFact ? 2000 : 20000} onChange={e => setC(e.target.value)} aria-label="What it says" />
      {isFact && <p className="text-xs text-gray-400">Your correction is kept in its history and counts as confirmed.</p>}
      <div className="flex gap-2">
        <Button size="sm" type="submit" loading={busy} disabled={isFact ? !c.trim() : !t.trim()}>Save</Button>
        <Button size="sm" variant="ghost" type="button" onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  )
}

function FactVisibility({ passport, agents, busy, moving, onShare, onMove, onStartMove, onCancelMove }: {
  passport: Passport; agents: CosmosAgentOption[]; busy: boolean; moving: boolean
  onShare: (agentId: string, on: boolean) => void; onMove: (domains: string[]) => void
  onStartMove: () => void; onCancelMove: () => void
}) {
  const v = passport.visibility
  const specialists = agents.filter(a => a.domain)
  const [picked, setPicked] = useState<string[]>(v.domains)
  useEffect(() => { setPicked(v.domains) }, [v.domains, moving])

  if (passport.item.restricted) {
    return <p className="text-sm text-gray-600 dark:text-gray-300">Nobody. You restricted it, so no agent can use it until you let them.</p>
  }
  if (moving) {
    return (
      <div className="flex flex-col gap-2">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={picked.length === 0} onChange={() => setPicked(picked.length ? [] : specialists.slice(0, 1).map(a => a.domain!))} />
          Every agent
        </label>
        {specialists.map(a => (
          <label key={a.id} className="flex items-center gap-2 pl-5 text-sm">
            <input type="checkbox" checked={picked.includes(a.domain!)}
              onChange={() => setPicked(p => p.includes(a.domain!) ? p.filter(d => d !== a.domain) : [...p, a.domain!])} />
            {a.name} only
          </label>
        ))}
        <p className="text-xs text-gray-400">Jizza always sees what its specialists see. You’ll be asked to confirm it’s you.</p>
        <div className="flex gap-2">
          <Button size="sm" loading={busy} onClick={() => onMove(picked)}>Save</Button>
          <Button size="sm" variant="ghost" onClick={onCancelMove}>Cancel</Button>
        </div>
      </div>
    )
  }
  const sees = new Set(v.agents.map(a => a.id))
  return (
    <div className="flex flex-col gap-2">
      {v.everyAgent
        ? <p className="text-sm text-gray-700 dark:text-gray-300">Every agent.</p>
        : (
          <div className="flex flex-wrap gap-2">
            {specialists.map(a => {
              const on = sees.has(a.id)
              return (
                <button key={a.id} type="button" disabled={busy} aria-pressed={on}
                  onClick={() => onShare(a.id, !on)}
                  title={on ? `Stop ${a.name} using it` : `Let ${a.name} use it`}
                  className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors disabled:opacity-60 ${
                    on ? 'border-brand-400 bg-brand-50 text-brand-700 dark:border-brand-600 dark:bg-brand-950/40 dark:text-brand-200'
                      : 'border-dashed border-gray-300 text-gray-500 hover:border-gray-400 dark:border-gray-700'}`}>
                  <span className="h-2 w-2 rounded-full" style={{ background: `rgb(var(--agent-${a.key}, var(--agent-other)))` }} />
                  {a.name}{on ? '' : ' +'}
                </button>
              )
            })}
          </div>
        )}
      <button type="button" onClick={onStartMove} className="inline-flex items-center gap-1 self-start text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
        <MoveRight className="h-3 w-3" /> Change who can use it
      </button>
    </div>
  )
}

function NoteVisibility({ passport, agents, busy, moving, onMove, onStartMove, onCancelMove }: {
  passport: Passport; agents: CosmosAgentOption[]; busy: boolean; moving: boolean
  onMove: (toAgentId: string) => void; onStartMove: () => void; onCancelMove: () => void
}) {
  const owner = agents.find(a => a.id === passport.origin.agentId)
  const [to, setTo] = useState('')
  if (moving) {
    return (
      <div className="flex flex-col gap-2">
        <select className={fieldClass} value={to} onChange={e => setTo(e.target.value)} aria-label="Move to">
          <option value="">Choose an agent…</option>
          {agents.filter(a => a.id !== owner?.id).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <p className="text-xs text-gray-400">The note leaves {owner?.name ?? 'its agent'}’s memory. You’ll be asked to confirm it’s you.</p>
        <div className="flex gap-2">
          <Button size="sm" loading={busy} disabled={!to} onClick={() => onMove(to)}>Move</Button>
          <Button size="sm" variant="ghost" onClick={onCancelMove}>Cancel</Button>
        </div>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm text-gray-700 dark:text-gray-300">
        {passport.item.restricted ? 'Nobody, while it’s restricted.' : `Only ${owner?.name ?? 'the agent that saved it'}. An agent’s notes are its own.`}
      </p>
      <button type="button" onClick={onStartMove} className="inline-flex items-center gap-1 self-start text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
        <MoveRight className="h-3 w-3" /> Move to another agent
      </button>
    </div>
  )
}

function PromoteForm({ busy, onPromote, onCancel }: {
  busy: boolean; onPromote: (category: MemoryCategory, removeNote: boolean) => void; onCancel: () => void
}) {
  const [category, setCategory] = useState<MemoryCategory>('GENERAL')
  const [remove, setRemove] = useState(false)
  return (
    <section className="flex flex-col gap-2 rounded-lg border border-gray-200 p-3 dark:border-gray-800">
      <p className="text-sm text-gray-700 dark:text-gray-300">
        Copies this note into your memory as something you stand behind. Agents then see it by the usual rules for its kind.
      </p>
      <select className={fieldClass} value={category} onChange={e => setCategory(e.target.value as MemoryCategory)} aria-label="Kind of memory">
        {CATEGORIES.map(c => <option key={c} value={c}>{label(c)}</option>)}
      </select>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={remove} onChange={e => setRemove(e.target.checked)} /> Remove the note afterwards
      </label>
      <div className="flex gap-2">
        <Button size="sm" loading={busy} onClick={() => onPromote(category, remove)}>Make it a memory</Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
    </section>
  )
}
