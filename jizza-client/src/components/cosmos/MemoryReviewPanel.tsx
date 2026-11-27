import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpRight, Check, ChevronDown, ChevronRight, Lightbulb, Pencil, RefreshCw, X } from 'lucide-react'
import { SlideOver, fieldClass } from '@/components/ui/SlideOver'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Switch } from '@/components/ui/Switch'
import { ApiError } from '@/lib/api'
import { memoryReviewApi, type MemoryProposal, type MemoryReviewStatus, type ProposalKind } from '@/api/memoryReview'

const KIND: Record<ProposalKind, { label: string; variant: 'default' | 'success' | 'warning' | 'danger' | 'info' }> = {
  merge: { label: 'Merge', variant: 'info' },
  confirm: { label: 'Confirm', variant: 'success' },
  retire: { label: 'Retire', variant: 'warning' },
  update: { label: 'Change', variant: 'info' },
  lesson: { label: 'Lesson', variant: 'default' },
}
const ANSWERED: Record<string, string> = {
  approved: 'Approved', dismissed: 'Dismissed', expired: 'Expired', stale: 'No longer applied',
}

/**
 * The memory review's suggestions. Once a night Jizza reads your recent chats and what went wrong
 * in your jobs and workflows, and suggests tidying your memory or teaching an agent a lesson.
 * Nothing changes until you approve a suggestion here.
 */
export function MemoryReviewPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const [error, setError] = useState('')
  const [note, setNote] = useState('')
  const [showAnswered, setShowAnswered] = useState(false)

  const status = useQuery({ queryKey: ['memory', 'review', 'status'], queryFn: memoryReviewApi.status, enabled: open })
  const proposals = useQuery({ queryKey: ['memory', 'review', 'open'], queryFn: () => memoryReviewApi.proposals(['open']), enabled: open })
  const answered = useQuery({
    queryKey: ['memory', 'review', 'answered'],
    queryFn: () => memoryReviewApi.proposals(['approved', 'dismissed', 'stale', 'expired']),
    enabled: open && showAnswered,
  })
  const settings = useQuery({ queryKey: ['memory', 'settings'], queryFn: memoryReviewApi.settings, enabled: open })

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['memory', 'review'] })
    qc.invalidateQueries({ queryKey: ['cosmos'] })
  }
  const fail = (e: unknown) => setError(e instanceof ApiError || e instanceof Error ? e.message : 'That didn’t work')

  const run = useMutation({
    mutationFn: memoryReviewApi.run,
    onMutate: () => { setError(''); setNote('') },
    onSuccess: r => {
      setNote(r.status === 'done'
        ? (r.proposals === 0 ? 'Jizza looked and has nothing to suggest right now.' : `Jizza has ${r.proposals} new ${r.proposals === 1 ? 'suggestion' : 'suggestions'}.`)
        : r.detail ?? 'Nothing to review right now.')
      refresh()
    },
    onError: fail,
  })
  const toggle = useMutation({
    mutationFn: (on: boolean) => memoryReviewApi.setReview(
      { autoLearn: settings.data?.autoLearn ?? true, retentionDays: settings.data?.retentionDays ?? null }, on),
    onMutate: () => setError(''),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['memory', 'settings'] })
      qc.invalidateQueries({ queryKey: ['memory', 'review', 'status'] })
    },
    onError: fail,
  })
  const answer = useMutation({
    mutationFn: ({ id, approve, content }: { id: string; approve: boolean; content?: string }) =>
      approve ? memoryReviewApi.approve(id, content) : memoryReviewApi.dismiss(id),
    onMutate: () => { setError(''); setNote('') },
    onSuccess: p => {
      if (p.status === 'stale') setNote('That memory changed since the suggestion was made, so it was left as it is.')
      refresh()
    },
    onError: fail,
  })

  const list = proposals.data ?? []
  return (
    <SlideOver open={open} onClose={onClose} title="Suggestions"
      description="After reading your recent chats and what went wrong, Jizza suggests these changes. Nothing changes until you approve one.">
      <div className="flex flex-col gap-5">
        <ReviewHeader status={status.data} enabled={status.data?.enabled ?? true} busy={toggle.isPending}
          running={run.isPending} onToggle={on => toggle.mutate(on)} onRun={() => run.mutate()} />

        {note && <p className="text-sm text-gray-600 dark:text-gray-300">{note}</p>}
        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}

        {proposals.isLoading && <div className="flex justify-center py-8"><Spinner /></div>}
        {proposals.isError && <p className="text-sm text-red-500">The suggestions couldn’t be loaded. Try again in a moment.</p>}
        {proposals.data && list.length === 0 && (
          <div className="rounded-lg border border-dashed border-gray-300 p-6 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
            <Lightbulb className="mx-auto mb-2 h-5 w-5" />
            No suggestions waiting. Jizza looks again after tonight’s review.
          </div>
        )}
        <ul className="flex flex-col gap-3">
          {list.map(p => (
            <li key={p.id}>
              <ProposalCard proposal={p} busy={answer.isPending && answer.variables?.id === p.id}
                onApprove={content => answer.mutate({ id: p.id, approve: true, content })}
                onDismiss={() => answer.mutate({ id: p.id, approve: false })} />
            </li>
          ))}
        </ul>

        <section>
          <button type="button" onClick={() => setShowAnswered(s => !s)}
            className="inline-flex items-center gap-1 text-sm font-medium text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-gray-100">
            {showAnswered ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />} Recently answered
          </button>
          {showAnswered && (
            <ul className="mt-2 flex flex-col gap-1.5">
              {answered.isLoading && <li><Spinner /></li>}
              {answered.data?.length === 0 && <li className="text-sm text-gray-500">Nothing answered yet.</li>}
              {answered.data?.slice(0, 20).map(p => (
                <li key={p.id} className="flex items-start gap-2 text-sm">
                  <Badge variant={p.status === 'approved' ? 'success' : 'default'}>{ANSWERED[p.status] ?? p.status}</Badge>
                  <span className="min-w-0 flex-1 text-gray-700 dark:text-gray-300">{p.summary}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </SlideOver>
  )
}

function ReviewHeader({ status, enabled, busy, running, onToggle, onRun }: {
  status?: MemoryReviewStatus; enabled: boolean; busy: boolean; running: boolean
  onToggle: (on: boolean) => void; onRun: () => void
}) {
  const last = status?.last
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-gray-200 p-3 dark:border-gray-800">
      <div className="flex items-center gap-3">
        <Switch checked={enabled} onChange={onToggle} disabled={busy} label="Review my memory each night" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-gray-900 dark:text-gray-100">Review my memory each night</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {enabled && status?.nextRunAt ? `Next review ${new Date(status.nextRunAt).toLocaleString()}.` : 'Off. You can still review now.'}
          </p>
        </div>
        <Button size="sm" variant="secondary" loading={running} onClick={onRun}>
          <RefreshCw className="h-3.5 w-3.5" /> Review now
        </Button>
      </div>
      {last && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          Last review {new Date(last.startedAt).toLocaleString()}
          {last.status === 'done' && `: ${last.proposals} ${last.proposals === 1 ? 'suggestion' : 'suggestions'}.`}
          {last.status !== 'done' && last.detail && `: ${last.detail}`}
        </p>
      )}
    </section>
  )
}

function ProposalCard({ proposal: p, busy, onApprove, onDismiss }: {
  proposal: MemoryProposal; busy: boolean; onApprove: (content?: string) => void; onDismiss: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(p.content ?? '')
  const [showEvidence, setShowEvidence] = useState(false)
  const editable = p.content != null && (p.kind === 'merge' || p.kind === 'update' || p.kind === 'lesson')
  const kind = KIND[p.kind]
  return (
    <article className="flex flex-col gap-2 rounded-xl border border-gray-200 p-3 dark:border-gray-800">
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant={kind.variant}>{kind.label}</Badge>
        {p.agent && <span className="text-xs text-gray-500 dark:text-gray-400">for {p.agent.name}{p.about ? ` · about ${p.about}` : ''}</span>}
        {p.occurrences > 1 && <Badge>{p.kind === 'lesson' ? `went wrong ${p.occurrences} times` : `${p.occurrences} times`}</Badge>}
      </div>
      {editing ? (
        <form className="flex flex-col gap-2" onSubmit={e => { e.preventDefault(); onApprove(text.trim()) }}>
          <textarea className={fieldClass} rows={3} value={text} maxLength={p.kind === 'lesson' ? 600 : 2000}
            onChange={e => setText(e.target.value)} aria-label="Wording to save" />
          <div className="flex gap-2">
            <Button size="sm" type="submit" loading={busy} disabled={!text.trim()}><Check className="h-3.5 w-3.5" /> Save and approve</Button>
            <Button size="sm" variant="ghost" type="button" onClick={() => { setEditing(false); setText(p.content ?? '') }}>Cancel</Button>
          </div>
        </form>
      ) : (
        <p className="text-sm text-gray-900 dark:text-gray-100">{p.summary}</p>
      )}
      {p.reason && <p className="text-xs text-gray-500 dark:text-gray-400">{p.reason}</p>}
      {p.kind === 'merge' && p.facts.length > 0 && (
        <ul className="ml-4 list-disc text-xs text-gray-600 dark:text-gray-300">
          {p.facts.map(f => <li key={f.id}>{f.content ?? '(hidden)'}</li>)}
        </ul>
      )}
      {p.evidence.length > 0 && (
        <div>
          <button type="button" onClick={() => setShowEvidence(s => !s)}
            className="inline-flex items-center gap-1 text-xs font-medium text-brand-600 hover:underline dark:text-brand-300">
            {showEvidence ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            Based on {p.evidence.length} {p.evidence.length === 1 ? 'thing' : 'things'}
          </button>
          {showEvidence && (
            <ul className="mt-1.5 flex flex-col gap-1.5 border-l border-gray-200 pl-3 dark:border-gray-800">
              {p.evidence.map((e, i) => (
                <li key={i} className="text-xs text-gray-600 dark:text-gray-300">
                  <span>{e.label}</span>
                  {e.at && <span className="text-gray-400"> · {new Date(e.at).toLocaleString()}</span>}
                  {e.link && (
                    <> · <Link to={e.link} className="text-brand-600 hover:underline dark:text-brand-300">Open <ArrowUpRight className="inline h-3 w-3" /></Link></>
                  )}
                  {e.detail && <span className="mt-0.5 block font-mono text-[11px] text-gray-500 dark:text-gray-400">{e.detail}</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {!editing && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" loading={busy} onClick={() => onApprove()}><Check className="h-3.5 w-3.5" /> Approve</Button>
          {editable && <Button size="sm" variant="secondary" disabled={busy} onClick={() => setEditing(true)}><Pencil className="h-3.5 w-3.5" /> Edit</Button>}
          <Button size="sm" variant="ghost" disabled={busy} onClick={onDismiss}><X className="h-3.5 w-3.5" /> Dismiss</Button>
        </div>
      )}
    </article>
  )
}
