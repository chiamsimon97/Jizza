import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Download, MoreHorizontal, Pencil, Pin, PinOff, Plus, Search, Trash2 } from 'lucide-react'
import { conversationsApi } from '@/api/conversations'
import { AgentLogo } from '@/components/brand/AgentLogo'
import type { ConversationSummary } from '@/types'
import { resumeConversation, useAssistantStore } from '../assistantStore'

/** When a conversation last moved, in the coarse buckets a list of chats is usually grouped by. */
function bucket(iso?: string): string {
  if (!iso) return 'Older'
  const then = new Date(iso)
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  if (then.getTime() >= startOfToday) return 'Today'
  if (then.getTime() >= startOfToday - day) return 'Yesterday'
  if (then.getTime() >= startOfToday - 7 * day) return 'Previous 7 days'
  return 'Older'
}

/**
 * The left column of the chat workspace: every conversation, searchable, with pin, rename,
 * archive and delete. Pinned conversations stay on top; the rest are grouped by when they last moved.
 */
export function ConversationRail({ onPicked }: { onPicked?: () => void }) {
  const { conversationId, conversationsVersion, newConversation, incognito } = useAssistantStore()
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [showArchived, setShowArchived] = useState(false)
  const qc = useQueryClient()

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), 250)
    return () => clearTimeout(t)
  }, [query])

  const { data, isLoading } = useQuery({
    queryKey: ['conversations', debounced, showArchived],
    queryFn: () => conversationsApi.list({ q: debounced || undefined, archived: showArchived }),
  })

  // A sent message, a finished job or a new title moves the list.
  useEffect(() => { void qc.invalidateQueries({ queryKey: ['conversations'] }) }, [conversationsVersion, qc])

  const patch = useMutation({
    mutationFn: ({ id, ...body }: { id: string; title?: string; pinned?: boolean; archived?: boolean }) =>
      conversationsApi.update(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['conversations'] }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => conversationsApi.remove(id),
    onSuccess: (_, id) => {
      if (id === useAssistantStore.getState().conversationId) newConversation()
      void qc.invalidateQueries({ queryKey: ['conversations'] })
    },
  })

  // Threads sit under the main chat they came from when it is listed too; otherwise on their own.
  const all = data ?? []
  const listed = new Set(all.map(c => c.id))
  const threadsOf = new Map<string, ConversationSummary[]>()
  for (const c of all) {
    if (c.kind === 'thread' && c.parentId && listed.has(c.parentId)) {
      threadsOf.set(c.parentId, [...(threadsOf.get(c.parentId) ?? []), c])
    }
  }
  const nested = new Set([...threadsOf.values()].flat().map(c => c.id))
  const top = all.filter(c => !nested.has(c.id))
  const pinned = top.filter(c => c.pinned)
  const groups = new Map<string, ConversationSummary[]>()
  for (const c of top.filter(c => !c.pinned)) {
    const key = bucket(c.lastMessageAt ?? c.createdAt)
    groups.set(key, [...(groups.get(key) ?? []), c])
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-col gap-2 p-3">
        <button
          type="button"
          onClick={() => { newConversation(); onPicked?.() }}
          className="flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm font-medium text-gray-800 shadow-sm transition-colors hover:border-brand-300 hover:text-brand-700 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-200 dark:hover:border-brand-700 dark:hover:text-brand-300"
        >
          <Plus className="h-4 w-4" />
          New conversation
        </button>
        <label className="flex items-center gap-2 rounded-lg bg-gray-100 px-2.5 py-1.5 text-sm dark:bg-gray-900">
          <Search className="h-3.5 w-3.5 shrink-0 text-gray-400" />
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search conversations"
            className="min-w-0 flex-1 bg-transparent text-gray-900 placeholder-gray-400 focus:outline-none dark:text-gray-100"
          />
        </label>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {isLoading && <p className="px-2 py-3 text-xs text-gray-400">Loading…</p>}
        {!isLoading && (data ?? []).length === 0 && (
          <p className="px-2 py-3 text-xs text-gray-500 dark:text-gray-400">
            {debounced ? 'Nothing matches that.' : showArchived ? 'No archived conversations.' : 'Your conversations will appear here.'}
          </p>
        )}
        {pinned.length > 0 && (
          <Group label="Pinned">
            {pinned.map(c => (
              <RowWithThreads key={c.id} c={c} threads={threadsOf.get(c.id)} current={incognito ? undefined : conversationId}
                onPicked={onPicked} patch={patch.mutate} remove={remove.mutate} />
            ))}
          </Group>
        )}
        {[...groups.entries()].map(([label, rows]) => (
          <Group key={label} label={label}>
            {rows.map(c => (
              <RowWithThreads key={c.id} c={c} threads={threadsOf.get(c.id)} current={incognito ? undefined : conversationId}
                onPicked={onPicked} patch={patch.mutate} remove={remove.mutate} />
            ))}
          </Group>
        ))}
      </div>

      <button
        type="button"
        onClick={() => setShowArchived(!showArchived)}
        className="flex items-center gap-2 border-t border-gray-200 px-4 py-2.5 text-xs font-medium text-gray-500 hover:text-gray-900 dark:border-gray-800 dark:text-gray-400 dark:hover:text-gray-100"
      >
        {showArchived ? <ArchiveRestore className="h-3.5 w-3.5" /> : <Archive className="h-3.5 w-3.5" />}
        {showArchived ? 'Back to conversations' : 'Archived'}
      </button>
    </div>
  )
}

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-2">
      <p className="px-2 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">{label}</p>
      <ul className="flex flex-col gap-0.5">{children}</ul>
    </div>
  )
}

type Patch = (v: { id: string; title?: string; pinned?: boolean; archived?: boolean }) => void

/** A main chat, then the threads Jizza opened from it, indented under a guide line. */
function RowWithThreads({ c, threads, current, onPicked, patch, remove }: {
  c: ConversationSummary; threads?: ConversationSummary[]; current?: string; onPicked?: () => void; patch: Patch; remove: (id: string) => void
}) {
  return (
    <>
      <Row c={c} active={c.id === current} onPicked={onPicked} patch={patch} remove={remove} />
      {threads && threads.length > 0 && (
        <li>
          <ul aria-label={`Threads from ${c.title || 'this conversation'}`} className="ml-3 flex flex-col gap-0.5 border-l border-gray-200 pl-1.5 dark:border-gray-800">
            {threads.map(t => <Row key={t.id} c={t} active={t.id === current} onPicked={onPicked} patch={patch} remove={remove} />)}
          </ul>
        </li>
      )}
    </>
  )
}

function Row({ c, active, onPicked, patch, remove }: {
  c: ConversationSummary; active: boolean; onPicked?: () => void; patch: Patch; remove: (id: string) => void
}) {
  const [menu, setMenu] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [title, setTitle] = useState(c.title ?? '')
  const ref = useRef<HTMLLIElement>(null)

  useEffect(() => {
    if (!menu) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) { setMenu(false); setConfirming(false) } }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [menu])

  const label = c.title || c.preview || 'Untitled conversation'

  if (renaming) {
    return (
      <li className="px-1">
        <input
          autoFocus
          value={title}
          onChange={e => setTitle(e.target.value)}
          onBlur={() => setRenaming(false)}
          onKeyDown={e => {
            if (e.key === 'Enter' && title.trim()) { patch({ id: c.id, title: title.trim() }); setRenaming(false) }
            if (e.key === 'Escape') setRenaming(false)
          }}
          maxLength={120}
          className="w-full rounded-md border border-brand-400 bg-white px-2 py-1.5 text-sm text-gray-900 focus:outline-none dark:bg-gray-900 dark:text-gray-100"
        />
      </li>
    )
  }

  return (
    <li ref={ref} className="group relative">
      <button
        type="button"
        onClick={() => { if (!active) resumeConversation(c.id); onPicked?.() }}
        title={label}
        className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 pr-8 text-left text-sm transition-colors ${
          active
            ? 'bg-brand-50 font-medium text-brand-800 dark:bg-brand-500/15 dark:text-brand-200'
            : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800/70'
        }`}
      >
        {c.pinned && <Pin className="h-3 w-3 shrink-0 text-gray-400" />}
        {c.kind === 'thread' && (
          <span className="shrink-0" title={c.lead ? `Led by ${c.lead.name}` : 'Thread'}>
            <AgentLogo agentKey={c.lead?.key ?? 'jizza'} size={16} />
          </span>
        )}
        <span className="truncate">{label}</span>
      </button>
      <button
        type="button"
        onClick={() => setMenu(!menu)}
        aria-label={`Options for ${label}`}
        className={`absolute right-1 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded text-gray-500 hover:bg-gray-200 dark:hover:bg-gray-700 ${menu ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 focus:opacity-100'}`}
      >
        <MoreHorizontal className="h-4 w-4" />
      </button>
      {menu && (
        <div className="absolute right-1 top-full z-20 mt-1 w-44 overflow-hidden rounded-lg border border-gray-200 bg-white py-1 text-sm shadow-lg dark:border-gray-700 dark:bg-gray-900">
          <MenuItem icon={c.pinned ? PinOff : Pin} label={c.pinned ? 'Unpin' : 'Pin'} onClick={() => { patch({ id: c.id, pinned: !c.pinned }); setMenu(false) }} />
          <MenuItem icon={Pencil} label="Rename" onClick={() => { setTitle(c.title ?? ''); setRenaming(true); setMenu(false) }} />
          <MenuItem icon={Download} label="Download" onClick={() => { void conversationsApi.download(c.id, c.title, 'md'); setMenu(false) }} />
          <MenuItem icon={c.archived ? ArchiveRestore : Archive} label={c.archived ? 'Unarchive' : 'Archive'} onClick={() => { patch({ id: c.id, archived: !c.archived }); setMenu(false) }} />
          {confirming
            ? <MenuItem icon={Trash2} label="Delete for good" danger onClick={() => { remove(c.id); setMenu(false) }} />
            : <MenuItem icon={Trash2} label="Delete" danger onClick={() => setConfirming(true)} />}
        </div>
      )}
    </li>
  )
}

function MenuItem({ icon: Icon, label, onClick, danger }: { icon: typeof Pin; label: string; onClick: () => void; danger?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-gray-100 dark:hover:bg-gray-800 ${danger ? 'text-red-600 dark:text-red-400' : 'text-gray-700 dark:text-gray-200'}`}
    >
      <Icon className="h-3.5 w-3.5" />
      {label}
    </button>
  )
}
