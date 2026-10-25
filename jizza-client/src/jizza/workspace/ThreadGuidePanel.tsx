import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, Brain, Download, Pencil } from 'lucide-react'
import { conversationsApi } from '@/api/conversations'
import { Button } from '@/components/ui/Button'
import { relativeTime } from '@/lib/timeRange'
import { Markdown } from '../Markdown'
import { useAssistantStore } from '../assistantStore'

/**
 * A thread's own memory: its guide, the learning file its lead and Jizza keep for it (read at the
 * start of every reply in the thread and updated after each one; the user can read it, edit it by
 * hand and save it as a .md file), and the facts learned from what was said in it. Another
 * thread never recalls them.
 */
export function ThreadGuidePanel({ threadId }: { threadId: string }) {
  const version = useAssistantStore(s => s.conversationsVersion)
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')

  const { data: guide, isLoading, refetch } = useQuery({
    queryKey: ['thread-guide', threadId],
    queryFn: () => conversationsApi.guide(threadId),
  })
  const { data: learned, refetch: refetchLearned } = useQuery({
    queryKey: ['thread-learned', threadId],
    queryFn: () => conversationsApi.learned(threadId),
  })
  // The guide is updated a moment after each reply; look again when one finishes.
  useEffect(() => {
    if (editing) return
    void refetch()
    void refetchLearned()
    const t = setTimeout(() => { void refetch(); void refetchLearned() }, 8_000)
    return () => clearTimeout(t)
  }, [version, editing, refetch, refetchLearned])

  const save = useMutation({
    mutationFn: (markdown: string) => conversationsApi.updateGuide(threadId, markdown),
    onSuccess: updated => {
      qc.setQueryData(['thread-guide', threadId], updated)
      setEditing(false)
    },
  })

  const lead = guide?.lead?.name ?? 'Jizza'

  return (
    <div className="flex h-full min-h-0 flex-col bg-gray-50/80 dark:bg-gray-950">
      <div className="flex items-start gap-2 px-4 pb-2 pt-4">
        <BookOpen className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
        <div className="min-w-0 flex-1">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">Thread memory</h3>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
            What {lead} and Jizza keep for this thread. It is read before every reply here, and only here.
          </p>
        </div>
        {!editing && guide && (
          <div className="flex shrink-0 gap-1">
            <IconAction label="Edit the guide" onClick={() => { setDraft(guide.markdown ?? ''); setEditing(true) }}><Pencil className="h-3.5 w-3.5" /></IconAction>
            <IconAction label="Download as .md" onClick={() => void conversationsApi.downloadGuide(threadId, guide.title)}><Download className="h-3.5 w-3.5" /></IconAction>
          </div>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
        {isLoading && <p className="py-2 text-xs text-gray-400">Loading…</p>}
        {editing ? (
          <div className="flex h-full flex-col gap-2">
            <textarea
              value={draft}
              onChange={e => setDraft(e.target.value)}
              aria-label="Thread guide"
              spellCheck
              className="min-h-[16rem] flex-1 resize-none rounded-lg border border-gray-300 bg-white p-3 font-mono text-xs leading-relaxed text-gray-900 focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            />
            {save.isError && <p className="text-xs text-red-600 dark:text-red-400">{(save.error as Error).message}</p>}
            <div className="flex justify-end gap-2">
              <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(false)}>Cancel</Button>
              <Button type="button" size="sm" loading={save.isPending} onClick={() => save.mutate(draft)}>Save guide</Button>
            </div>
          </div>
        ) : guide?.markdown ? (
          <div className="rounded-xl border border-gray-200 bg-white p-3 text-sm leading-relaxed text-gray-800 shadow-sm dark:border-gray-800 dark:bg-gray-900 dark:text-gray-200">
            <Markdown text={guide.markdown} />
          </div>
        ) : !isLoading && (
          <p className="py-2 text-xs text-gray-500 dark:text-gray-400">Nothing kept yet. It fills in as you talk here.</p>
        )}
        {!editing && (
          <section aria-label="Learned in this thread" className="mt-4">
            <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
              <Brain className="h-3.5 w-3.5 text-brand-500" /> Learned in this thread
            </h4>
            {learned && learned.length > 0 ? (
              <ul className="mt-2 flex flex-col gap-1.5">
                {learned.map(f => (
                  <li key={f.id} className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs text-gray-800 shadow-sm dark:border-gray-800 dark:bg-gray-900 dark:text-gray-200">
                    {f.content}
                    <span className="mt-1 flex gap-1.5 text-[11px] text-gray-400">
                      <span>{f.category}</span>·<span>{f.confirmed ? 'confirmed' : 'not confirmed'}</span>·<span>{relativeTime(f.createdAt)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-1.5 text-xs text-gray-500 dark:text-gray-400">No facts yet. What you tell {lead} here is remembered for this thread.</p>
            )}
          </section>
        )}
      </div>

      {!editing && guide?.updatedAt && (
        <p className="border-t border-gray-200 px-4 py-2 text-[11px] text-gray-400 dark:border-gray-800 dark:text-gray-500">
          Updated {relativeTime(guide.updatedAt)}{guide.source === 'user' ? ' · you edited it' : ''}
        </p>
      )}
    </div>
  )
}

function IconAction({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} title={label} aria-label={label}
      className="grid h-7 w-7 place-items-center rounded-md text-gray-500 transition-colors hover:bg-gray-200 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100">
      {children}
    </button>
  )
}
