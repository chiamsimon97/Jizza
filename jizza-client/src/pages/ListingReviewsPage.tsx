import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ClipboardCheck, PauseCircle, PlayCircle, ShieldCheck, X } from 'lucide-react'
import { listingReviewApi } from '@/api/listings'
import { useAuthorization } from '@/auth/useAuthorization'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { ListingStatusBadge, ProfilePreview, ReviewHistory, errorText } from '@/components/listing/ListingParts'
import type { Listing, ListingStatus } from '@/types'

const TABS: { status: ListingStatus; label: string; empty: string }[] = [
  { status: 'PENDING', label: 'Pending',
    empty: 'No organization is waiting for review. A request appears here when an organization\'s Owner or Admin opens Public listing and clicks Send for review.' },
  { status: 'APPROVED', label: 'Approved',
    empty: 'No organization is public yet. Organizations you approve are listed here and on Discover.' },
  { status: 'REJECTED', label: 'Rejected', empty: 'No requests have been rejected.' },
  { status: 'SUSPENDED', label: 'Suspended', empty: 'No listings are suspended.' },
]

/** The Super Admin's queue of public listing requests. */
export function ListingReviewsPage() {
  const { data: snapshot } = useAuthorization()
  const [tab, setTab] = useState<ListingStatus>('PENDING')
  const [selected, setSelected] = useState<string | null>(null)
  const queue = useQuery({
    queryKey: ['admin', 'listings', tab],
    queryFn: () => listingReviewApi.queue(tab),
    enabled: snapshot?.platformRole === 'SUPER_ADMIN',
  })

  if (snapshot && snapshot.platformRole !== 'SUPER_ADMIN') {
    return <div className="page"><p className="text-sm text-gray-500 dark:text-gray-400">Only a Super Admin can review listings.</p></div>
  }

  return (
    <div className="page">
      <PageHeader icon={ClipboardCheck} title="Listing reviews"
        description="Organizations asking to be listed on Discover. Nothing is listed until you approve it." />

      <div className="mb-5 max-w-4xl rounded-xl border border-brand-200 bg-brand-50 px-4 py-3 text-sm text-gray-700 dark:border-brand-500/30 dark:bg-brand-500/10 dark:text-gray-200">
        <p className="flex items-center gap-1.5 font-medium text-gray-900 dark:text-gray-100">
          <ShieldCheck className="h-4 w-4 text-brand-600 dark:text-brand-400" />You're the Super Admin
        </p>
        <p className="mt-1">
          This page covers every organization on Jizza, whichever account you're in. An organization is private
          until its Owner or Admin sends its profile from <span className="font-medium">Public listing</span>.
          Once you approve it, it becomes a <span className="font-medium">public organization</span>: it shows on
          Discover with a Public badge, and anyone can ask to join.
        </p>
      </div>

      <div role="tablist" aria-label="Listing status" className="mb-4 flex flex-wrap gap-1 border-b border-gray-200 dark:border-gray-800">
        {TABS.map(t => (
          <button key={t.status} role="tab" aria-selected={tab === t.status}
            onClick={() => { setTab(t.status); setSelected(null) }}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${tab === t.status
              ? 'border-brand-500 text-brand-700 dark:text-brand-300'
              : 'border-transparent text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'}`}>
            {t.label}
          </button>
        ))}
      </div>

      <div className="grid max-w-6xl grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,360px)_minmax(0,1fr)]">
        <section aria-label="Requests">
          {queue.isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
          {queue.error && <p className="text-sm text-red-500">{errorText(queue.error)}</p>}
          {queue.data && queue.data.length === 0 && (
            <p className="rounded-xl border border-dashed border-gray-200 p-6 text-center text-sm text-gray-500 dark:border-gray-800 dark:text-gray-400">
              {TABS.find(t => t.status === tab)?.empty}
            </p>
          )}
          {queue.data && queue.data.length > 0 && (
            <ul className="divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200 bg-white dark:divide-gray-800 dark:border-gray-800 dark:bg-gray-950">
              {queue.data.map(l => (
                <li key={l.workspaceId}>
                  <button onClick={() => setSelected(l.workspaceId)}
                    className={`w-full px-4 py-3 text-left hover:bg-gray-50 dark:hover:bg-gray-900 ${selected === l.workspaceId ? 'bg-brand-50 dark:bg-brand-500/10' : ''}`}>
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                      {l.draft?.displayName ?? l.workspaceName}
                      {l.approved && l.status === 'PENDING' && <span className="text-xs font-normal text-gray-500">changes to a listed profile</span>}
                    </p>
                    <p className="text-xs text-gray-500 dark:text-gray-400">
                      {l.workspaceName}{l.submittedAt ? ` · sent ${new Date(l.submittedAt).toLocaleDateString()}` : ''}
                    </p>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {selected
          ? <ReviewPanel key={selected} id={selected} onChanged={() => setSelected(null)} />
          : <p className="text-sm text-gray-500 dark:text-gray-400">Choose a request to review it.</p>}
      </div>
    </div>
  )
}

function ReviewPanel({ id, onChanged }: { id: string; onChanged: () => void }) {
  const qc = useQueryClient()
  const { data: item, isLoading, error } = useQuery({ queryKey: ['admin', 'listing', id], queryFn: () => listingReviewApi.item(id) })
  const [note, setNote] = useState('')

  const act = useMutation({
    mutationFn: (action: 'approve' | 'reject' | 'suspend' | 'reinstate') => {
      switch (action) {
        case 'approve': return listingReviewApi.approve(id, note)
        case 'reject': return listingReviewApi.reject(id, note)
        case 'suspend': return listingReviewApi.suspend(id, note)
        case 'reinstate': return listingReviewApi.reinstate(id, note)
      }
    },
    onSuccess: (next: Listing) => {
      qc.setQueryData(['admin', 'listing', id], next)
      qc.invalidateQueries({ queryKey: ['admin', 'listings'] })
      setNote('')
      onChanged()
    },
  })

  if (isLoading) return <div className="flex justify-center py-10"><Spinner /></div>
  if (error || !item) return <p className="text-sm text-red-500">{errorText(error)}</p>

  const pending = item.status === 'PENDING'
  const listed = item.approved !== null && (item.status === 'APPROVED' || item.status === 'PENDING')
  const needsReason = 'A reason is required, so the organization knows what to fix.'

  return (
    <Card padding="lg">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <h2 className="font-semibold text-gray-900 dark:text-gray-100">{item.workspaceName}</h2>
        <ListingStatusBadge status={item.status} />
      </div>

      {item.draft && (
        <div className="mb-5">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
            {pending ? (item.approved ? 'Proposed changes' : 'Requested profile') : 'Current profile'}
          </p>
          <ProfilePreview profile={item.draft} />
        </div>
      )}
      {pending && item.approved && (
        <div className="mb-5 opacity-70">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">Listed now</p>
          <ProfilePreview profile={item.approved} />
        </div>
      )}

      <Field label="Reason or note" hint="Shown to the organization's Owners and Admins.">
        <textarea className={`${fieldClass} min-h-[72px]`} maxLength={1000} value={note} onChange={e => setNote(e.target.value)} />
      </Field>
      {act.error && <p className="mt-2 text-sm text-red-500">{errorText(act.error)}</p>}
      <div className="mt-3 flex flex-wrap gap-2">
        {pending && (
          <>
            <Button size="sm" loading={act.isPending && act.variables === 'approve'} disabled={act.isPending}
              onClick={() => act.mutate('approve')}><Check className="h-4 w-4" />Approve</Button>
            <Button size="sm" variant="danger" loading={act.isPending && act.variables === 'reject'}
              disabled={act.isPending || !note.trim()} title={note.trim() ? undefined : needsReason}
              onClick={() => act.mutate('reject')}><X className="h-4 w-4" />Reject</Button>
          </>
        )}
        {listed && (
          <Button size="sm" variant="secondary" loading={act.isPending && act.variables === 'suspend'}
            disabled={act.isPending || !note.trim()} title={note.trim() ? undefined : needsReason}
            onClick={() => act.mutate('suspend')}><PauseCircle className="h-4 w-4" />Suspend</Button>
        )}
        {item.status === 'SUSPENDED' && (
          <Button size="sm" loading={act.isPending && act.variables === 'reinstate'} disabled={act.isPending}
            onClick={() => act.mutate('reinstate')}><PlayCircle className="h-4 w-4" />Reinstate</Button>
        )}
      </div>
      {(pending || listed) && !note.trim() && (
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">Rejecting and suspending need a reason, so the organization knows what to fix.</p>
      )}

      <h3 className="mb-2 mt-6 text-sm font-semibold text-gray-900 dark:text-gray-100">History</h3>
      <ReviewHistory history={item.history} />
    </Card>
  )
}
