import { useRef, useState, type KeyboardEvent } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronRight, Eye, Lock, RotateCcw, Trash2, Upload } from 'lucide-react'
import { cosmosApi } from '@/api/cosmos'
import { memoryApi } from '@/api/memory'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import type { MemoryFile, MemoryNode } from '@/types'
import type { CosmosLayout, PlacedBody } from './useCosmosLayout'
import { FilePreview } from './FilePreview'

interface Props {
  layout: CosmosLayout
  /** Show only this agent's part of the sky, opened. */
  agentId?: string
  includeInactive: boolean
  matches: (n: MemoryNode) => boolean
  onOpenItem: (n: MemoryNode) => void
  /** Offers to forget a body's part of the sky. */
  onReset?: (b: PlacedBody) => void
}

const TYPE_LABEL: Record<string, string> = {
  SUN: 'Jizza', AGENT: 'Agent', SUBJECT: 'Subject', NOTES: 'Notes', FILE: 'File', CONNECTOR: 'Connector',
  WORKFLOW: 'Workflow', SKILL: 'Skill',
}

/**
 * The Cosmos as a tree, for keyboards and screen readers and for anyone who'd rather read than fly:
 * the same bodies and items, the same passport. Files are added and removed here too.
 */
export function CosmosListView({ layout, agentId, includeInactive, matches, onOpenItem, onReset }: Props) {
  const sun = layout.sunId ? layout.byId.get(layout.sunId) : undefined
  const startOpen = () => {
    const open = new Set<string>()
    if (sun) open.add(sun.body.bodyId)
    const focus = agentId ? layout.bodies.find(b => b.body.meta?.agentId === agentId) : undefined
    if (focus) open.add(focus.body.bodyId)
    return open
  }
  const [expanded, setExpanded] = useState<Set<string>>(startOpen)
  const [preview, setPreview] = useState<{ agentId: string; file: MemoryFile } | null>(null)
  const treeRef = useRef<HTMLUListElement>(null)

  const toggle = (id: string, open?: boolean) => setExpanded(prev => {
    const next = new Set(prev)
    const want = open ?? !next.has(id)
    if (want) next.add(id); else next.delete(id)
    return next
  })

  const roots: PlacedBody[] = (() => {
    if (!sun) return []
    if (!agentId) return [sun]
    const focus = layout.bodies.find(b => b.body.meta?.agentId === agentId)
    return focus ? [focus] : [sun]
  })()

  // Arrow keys move through the visible rows, as the ARIA tree pattern describes.
  function onKeyDown(e: KeyboardEvent) {
    const rows = Array.from(treeRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]') ?? [])
    const current = document.activeElement as HTMLElement | null
    const i = current ? rows.indexOf(current) : -1
    if (i < 0) return
    const row = rows[i]
    const id = row.dataset.bodyId
    const expandable = row.getAttribute('aria-expanded') != null
    const isOpen = row.getAttribute('aria-expanded') === 'true'
    const focus = (el?: HTMLElement) => { if (el) { e.preventDefault(); el.focus() } }
    switch (e.key) {
      case 'ArrowDown': focus(rows[i + 1]); break
      case 'ArrowUp': focus(rows[i - 1]); break
      case 'Home': focus(rows[0]); break
      case 'End': focus(rows[rows.length - 1]); break
      case 'ArrowRight':
        if (expandable && !isOpen && id) { e.preventDefault(); toggle(id, true) } else if (isOpen) focus(rows[i + 1])
        break
      case 'ArrowLeft':
        if (expandable && isOpen && id) { e.preventDefault(); toggle(id, false) }
        else focus(row.parentElement?.closest('li')?.querySelector<HTMLElement>(':scope > [role="treeitem"]') ?? undefined)
        break
      case 'Enter': case ' ':
        e.preventDefault(); row.click(); break
    }
  }

  return (
    <>
      <ul ref={treeRef} role="tree" aria-label="Everything Jizza remembers" onKeyDown={onKeyDown}
        className="rounded-xl border border-gray-200 bg-white p-2 dark:border-gray-800 dark:bg-gray-900">
        {roots.map((b, i) => (
          <BodyRow key={b.body.bodyId} placed={b} layout={layout} depth={0} first={i === 0}
            expanded={expanded} toggle={toggle} includeInactive={includeInactive} matches={matches}
            onOpenItem={onOpenItem} onPreview={setPreview} onReset={onReset} />
        ))}
      </ul>
      <FilePreview agentId={preview?.agentId ?? ''} file={preview?.file ?? null} onClose={() => setPreview(null)} />
    </>
  )
}

interface RowProps {
  placed: PlacedBody
  layout: CosmosLayout
  depth: number
  first?: boolean
  expanded: Set<string>
  toggle: (id: string, open?: boolean) => void
  includeInactive: boolean
  matches: (n: MemoryNode) => boolean
  onOpenItem: (n: MemoryNode) => void
  onPreview: (p: { agentId: string; file: MemoryFile }) => void
  onReset?: (b: PlacedBody) => void
}

function BodyRow({ placed, layout, depth, first, expanded, toggle, includeInactive, matches, onOpenItem, onPreview, onReset }: RowProps) {
  const b = placed.body
  const id = b.bodyId
  const open = expanded.has(id)
  const kids = layout.children.get(id) ?? []
  const hasContent = kids.length > 0 || b.memoryCount > 0 || b.type === 'AGENT' || b.type === 'SUN'
  const agentId = typeof b.meta?.agentId === 'string' ? b.meta.agentId : undefined
  const isAgent = b.type === 'AGENT' || b.type === 'SUN'

  return (
    <li role="none">
      <div role="treeitem" tabIndex={first ? 0 : -1} data-body-id={id} aria-level={depth + 1}
        aria-expanded={hasContent ? open : undefined} aria-selected={false}
        onClick={() => hasContent && toggle(id)}
        style={{ paddingLeft: depth * 16 + 4 }}
        className="flex cursor-pointer items-center gap-2 rounded-lg py-1.5 pr-2 text-sm outline-none hover:bg-gray-50 focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:bg-gray-800/60">
        <ChevronRight className={`h-3.5 w-3.5 shrink-0 text-gray-400 transition-transform ${open ? 'rotate-90' : ''} ${hasContent ? '' : 'invisible'}`} />
        <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: `rgb(var(--agent-${b.agentKey}, var(--agent-other)))` }} />
        <span className={`truncate ${isAgent ? 'font-semibold text-gray-900 dark:text-gray-100' : 'text-gray-800 dark:text-gray-200'}`}>{b.label}</span>
        <span className="shrink-0 text-xs text-gray-400">{TYPE_LABEL[b.type]}</span>
        {placed.total > 0 && <span className="ml-auto shrink-0 rounded-full bg-gray-100 px-2 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">{placed.total}</span>}
        {onReset && (placed.total > 0 || b.memoryCount > 0) && !['WORKFLOW', 'SKILL', 'CONNECTOR'].includes(b.type) && (
          <button type="button" aria-label={`Reset ${b.label}`} title={b.type === 'SUN' ? 'Reset all memory' : `Reset ${b.label}`}
            onClick={e => { e.stopPropagation(); onReset(placed) }}
            className={`${placed.total > 0 ? '' : 'ml-auto '}shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-950/40`}>
            <RotateCcw className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      {open && (
        <ul role="group">
          {isAgent && agentId && <AgentFiles agentId={agentId} agentName={b.label} depth={depth + 1} />}
          {b.type === 'FILE' && agentId && typeof b.meta?.fileId === 'string' && (
            <FileActions agentId={agentId} fileId={b.meta.fileId} depth={depth + 1} onPreview={onPreview} />
          )}
          {kids.map(k => (
            <BodyRow key={k.body.bodyId} placed={k} layout={layout} depth={depth + 1} expanded={expanded} toggle={toggle}
              includeInactive={includeInactive} matches={matches} onOpenItem={onOpenItem} onPreview={onPreview} onReset={onReset} />
          ))}
          {(b.memoryCount > 0 || includeInactive) && (
            <BodyItems bodyId={id} depth={depth + 1} includeInactive={includeInactive} matches={matches} onOpenItem={onOpenItem} />
          )}
        </ul>
      )}
    </li>
  )
}

function BodyItems({ bodyId, depth, includeInactive, matches, onOpenItem }: {
  bodyId: string; depth: number; includeInactive: boolean; matches: (n: MemoryNode) => boolean; onOpenItem: (n: MemoryNode) => void
}) {
  const q = useInfiniteQuery({
    queryKey: ['cosmos', 'list-items', bodyId, includeInactive],
    queryFn: ({ pageParam }) => cosmosApi.items(bodyId, { cursor: pageParam, includeInactive, limit: 100 }),
    initialPageParam: null as string | null,
    getNextPageParam: last => last.nextCursor,
  })
  const items = (q.data?.pages.flatMap(p => p.items) ?? []).filter(matches)
  const pad = { paddingLeft: depth * 16 + 26 }
  if (q.isLoading) return <li role="none" style={pad} className="py-1.5"><Spinner size="sm" /></li>
  return (
    <>
      {items.map(n => (
        <li role="none" key={`${n.itemKind}:${n.id}`}>
          <div role="treeitem" tabIndex={-1} aria-level={depth + 1} aria-selected={false} onClick={() => onOpenItem(n)}
            style={pad}
            className="flex cursor-pointer items-center gap-2 rounded-lg py-1.5 pr-2 text-sm outline-none hover:bg-gray-50 focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:bg-gray-800/60">
            <span className={`h-1.5 w-1.5 shrink-0 ${n.itemKind === 'note' ? '' : 'rounded-full'}`} style={{ background: `rgb(var(--agent-${n.agentKey}, var(--agent-other)))` }} />
            {n.redacted
              ? <span className="flex items-center gap-1 text-gray-500"><Lock className="h-3 w-3" /> Restricted memory</span>
              : <span className={`truncate ${n.status === 'retired' || n.status === 'superseded' ? 'text-gray-400 line-through' : 'text-gray-700 dark:text-gray-300'}`}>{n.label}</span>}
            {n.status === 'pending' && <span className="shrink-0 rounded-full bg-amber-100 px-1.5 text-[10px] font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">confirm?</span>}
            {n.restricted && !n.redacted && <Lock className="h-3 w-3 shrink-0 text-gray-400" aria-label="Restricted" />}
            <span className="ml-auto shrink-0 text-xs text-gray-400">{n.accessCount > 0 ? `used ${n.accessCount}×` : n.itemKind}</span>
          </div>
        </li>
      ))}
      {q.hasNextPage && (
        <li role="none" style={pad}>
          <Button size="sm" variant="ghost" loading={q.isFetchingNextPage} onClick={() => q.fetchNextPage()}>Show more</Button>
        </li>
      )}
    </>
  )
}

/** Teaching an agent with files: drop or pick documents, PDFs or images. */
function AgentFiles({ agentId, agentName, depth }: { agentId: string; agentName: string; depth: number }) {
  const qc = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [error, setError] = useState('')
  const upload = useMutation({
    mutationFn: async (files: File[]) => { for (const f of files) await memoryApi.upload(agentId, f) },
    onMutate: () => setError(''),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : 'Upload failed'),
    onSettled: () => { qc.invalidateQueries({ queryKey: ['cosmos'] }); qc.invalidateQueries({ queryKey: ['memory', agentId] }) },
  })
  return (
    <li role="none" style={{ paddingLeft: depth * 16 + 26 }} className="py-1"
      onDragOver={e => e.preventDefault()}
      onDrop={e => { e.preventDefault(); if (e.dataTransfer.files.length) upload.mutate(Array.from(e.dataTransfer.files)) }}>
      <input ref={input} type="file" multiple hidden
        accept="image/png,image/jpeg,image/gif,image/webp,.heic,application/pdf,.txt,.md,.csv,.tsv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
        onChange={e => { if (e.target.files?.length) upload.mutate(Array.from(e.target.files)); e.target.value = '' }} />
      <Button size="sm" variant="ghost" loading={upload.isPending} onClick={() => input.current?.click()}>
        <Upload className="h-3.5 w-3.5" /> Teach {agentName} with files
      </Button>
      {error && <span className="ml-2 text-xs text-red-500">{error}</span>}
    </li>
  )
}

function FileActions({ agentId, fileId, depth, onPreview }: {
  agentId: string; fileId: string; depth: number; onPreview: (p: { agentId: string; file: MemoryFile }) => void
}) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['memory', agentId], queryFn: () => memoryApi.get(agentId) })
  const file = data?.files.find(f => f.id === fileId)
  const remove = useMutation({
    mutationFn: () => memoryApi.deleteFile(agentId, fileId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['cosmos'] }); qc.invalidateQueries({ queryKey: ['memory', agentId] }) },
  })
  if (!file) return null
  return (
    <li role="none" style={{ paddingLeft: depth * 16 + 26 }} className="flex flex-wrap items-center gap-2 py-1 text-xs text-gray-500">
      <span>{file.readableChars > 0 ? 'Readable' : 'Stored, not readable yet'}</span>
      <Button size="sm" variant="ghost" onClick={() => onPreview({ agentId, file })}><Eye className="h-3.5 w-3.5" /> View</Button>
      <Button size="sm" variant="ghost" loading={remove.isPending}
        onClick={() => { if (window.confirm(`Remove "${file.filename}" from this agent’s memory?`)) remove.mutate() }}>
        <Trash2 className="h-3.5 w-3.5" /> Delete
      </Button>
    </li>
  )
}
