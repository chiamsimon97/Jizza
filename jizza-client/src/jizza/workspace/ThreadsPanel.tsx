import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Check, ChevronDown, ChevronRight, Loader2, MessageCircleQuestion, Search, X } from 'lucide-react'
import { conversationsApi } from '@/api/conversations'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { relativeTime } from '@/lib/timeRange'
import type { ConversationStage, ConversationSummary } from '@/types'
import { resumeConversation, useAssistantStore } from '../assistantStore'

const GROUPS: { stage: ConversationStage; label: string }[] = [
  { stage: 'waiting', label: 'Waiting on you' },
  { stage: 'working', label: 'Working' },
  { stage: 'idle', label: 'Idle' },
  { stage: 'resolved', label: 'Resolved' },
]

/**
 * Every thread at a glance, grouped by where it stands: waiting on the user (a lead asked
 * something or needs an approval), working (a reply is being worked on), idle, and resolved
 * (archived). Shows the open chat's threads, or every chat's.
 */
export function ThreadsPanel({ mainId, currentId }: { mainId?: string; currentId?: string }) {
  const version = useAssistantStore(s => s.conversationsVersion)
  const qc = useQueryClient()
  const [scope, setScope] = useState<'chat' | 'all'>(mainId ? 'chat' : 'all')
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [folded, setFolded] = useState<Record<string, boolean>>({ resolved: true })
  const inChat = scope === 'chat' && !!mainId

  const { data, isLoading } = useQuery({
    queryKey: ['threads-panel', inChat ? mainId : 'all', version],
    queryFn: () => (inChat ? conversationsApi.threads(mainId!) : conversationsApi.allThreads()),
    retry: false,
    // A thread that is working changes stage on its own; look again until it settles.
    refetchInterval: q => ((q.state.data as ConversationSummary[] | undefined)?.some(t => t.stage === 'working') ? 3_000 : 15_000),
  })

  const resolve = useMutation({
    mutationFn: ({ id, resolved }: { id: string; resolved: boolean }) => conversationsApi.update(id, { archived: resolved }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['threads-panel'] })
      useAssistantStore.setState(s => ({ conversationsVersion: s.conversationsVersion + 1 }))
    },
  })

  const q = query.trim().toLowerCase()
  const threads = (data ?? []).filter(t => !q || `${t.title ?? ''} ${t.topic ?? ''} ${t.lead?.name ?? ''}`.toLowerCase().includes(q))
  const byStage = (stage: ConversationStage) => threads.filter(t => (t.stage ?? (t.archived ? 'resolved' : 'idle')) === stage)

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 items-center gap-2 px-3 pb-1 pt-3">
        {mainId ? (
          <div role="radiogroup" aria-label="Which threads" className="flex rounded-lg bg-gray-100 p-0.5 text-xs dark:bg-gray-800">
            {([['chat', 'This chat'], ['all', 'All chats']] as const).map(([id, label]) => (
              <button key={id} type="button" role="radio" aria-checked={scope === id} onClick={() => setScope(id)}
                className={`rounded-md px-2 py-0.5 font-medium transition-colors ${scope === id
                  ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-900 dark:text-gray-100'
                  : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
                {label}
              </button>
            ))}
          </div>
        ) : <span className="text-xs font-medium text-gray-500 dark:text-gray-400">All chats</span>}
        <span className="flex-1" />
        <button type="button" aria-label={searching ? 'Close search' : 'Search threads'} onClick={() => { setSearching(!searching); setQuery('') }}
          className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200">
          {searching ? <X className="h-3.5 w-3.5" /> : <Search className="h-3.5 w-3.5" />}
        </button>
      </div>
      {searching && (
        <div className="px-3 pb-1">
          <input autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder="Search threads"
            className="w-full rounded-lg bg-gray-100 px-2.5 py-1.5 text-sm text-gray-900 placeholder-gray-400 focus:outline-none dark:bg-gray-900 dark:text-gray-100" />
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {isLoading && <p className="px-2 py-3 text-xs text-gray-400">Loading…</p>}
        {GROUPS.map(({ stage, label }) => {
          const rows = byStage(stage)
          const open = !folded[stage]
          if (rows.length === 0 && stage !== 'waiting') return null
          return (
            <section key={stage} aria-label={label} className="mt-2">
              <button type="button" onClick={() => setFolded(f => ({ ...f, [stage]: open }))} aria-expanded={open}
                className="flex items-center gap-1 px-2 py-1 text-xs font-medium text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200">
                {label} · {rows.length}
                {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              </button>
              {open && rows.length === 0 && (
                <p className="mx-1 mt-1 rounded-lg border border-dashed border-gray-300 px-3 py-3 text-center text-xs text-gray-400 dark:border-gray-700">
                  Questions and approvals from your threads.
                </p>
              )}
              {open && rows.map(t => (
                <ThreadRow key={t.id} thread={t} current={t.id === currentId}
                  onResolve={resolved => resolve.mutate({ id: t.id, resolved })} />
              ))}
            </section>
          )
        })}
        {!isLoading && (data ?? []).length === 0 && (
          <p className="px-3 py-6 text-center text-xs text-gray-400">
            No threads yet. Turn threads on for a chat and Jizza moves each topic into a thread led by its specialist.
          </p>
        )}
      </div>
    </div>
  )
}

function StageIcon({ stage }: { stage: ConversationStage }) {
  switch (stage) {
    case 'working': return <Loader2 aria-label="Working" className="h-4 w-4 animate-spin text-brand-500" />
    case 'waiting': return <MessageCircleQuestion aria-label="Waiting on you" className="h-4 w-4 text-amber-500" />
    case 'resolved': return <Check aria-label="Resolved" className="h-4 w-4 text-emerald-500" />
    default: return <span aria-label="Idle" className="block h-3.5 w-3.5 rounded-full border-2 border-dashed border-gray-400" />
  }
}

function ThreadRow({ thread, current, onResolve }: { thread: ConversationSummary; current: boolean; onResolve: (resolved: boolean) => void }) {
  const stage = thread.stage ?? (thread.archived ? 'resolved' : 'idle')
  const at = thread.lastMessageAt ?? thread.createdAt
  return (
    <div className={`group relative flex gap-2.5 rounded-lg px-2 py-2 transition-colors ${current ? 'bg-brand-50 dark:bg-brand-500/10' : 'hover:bg-gray-100 dark:hover:bg-gray-900'}`}>
      <span className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center"><StageIcon stage={stage} /></span>
      <button type="button" onClick={() => resumeConversation(thread.id)} className="min-w-0 flex-1 text-left" title={thread.topic ?? thread.title}>
        <span className="flex items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate text-sm font-medium text-gray-900 dark:text-gray-100">{thread.title ?? 'Thread'}</span>
          <span className="shrink-0 text-[11px] text-gray-400">{relativeTime(at)}</span>
        </span>
        {thread.preview && <span className="mt-0.5 block truncate text-xs text-gray-500 dark:text-gray-400">{thread.preview}</span>}
        <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
          <span className="flex items-center gap-1 rounded-full bg-brand-50 py-0.5 pl-0.5 pr-2 text-[11px] font-medium text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
            <AgentLogo agentKey={thread.lead?.key ?? 'jizza'} size={14} />
            {thread.lead?.name ?? 'Jizza'}
          </span>
          <span className="rounded-full border border-gray-200 px-2 py-0.5 text-[11px] text-gray-500 dark:border-gray-700 dark:text-gray-400">
            {Math.ceil(thread.messageCount / 2)} {Math.ceil(thread.messageCount / 2) === 1 ? 'exchange' : 'exchanges'}
          </span>
        </span>
      </button>
      {stage !== 'working' && (
        <button type="button" onClick={() => onResolve(stage !== 'resolved')}
          aria-label={stage === 'resolved' ? `Reopen ${thread.title ?? 'thread'}` : `Resolve ${thread.title ?? 'thread'}`}
          title={stage === 'resolved' ? 'Reopen' : 'Mark resolved'}
          className="absolute right-1.5 top-7 rounded-md p-1 text-gray-400 opacity-0 transition-opacity hover:bg-gray-200 hover:text-gray-700 focus:opacity-100 group-hover:opacity-100 dark:hover:bg-gray-800 dark:hover:text-gray-200">
          {stage === 'resolved' ? <ArchiveRestore className="h-3.5 w-3.5" /> : <Archive className="h-3.5 w-3.5" />}
        </button>
      )}
    </div>
  )
}
