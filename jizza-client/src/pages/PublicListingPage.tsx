import { type FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Globe, Send, Undo2, EyeOff } from 'lucide-react'
import { categoryLabel, directoryApi, listingApi } from '@/api/listings'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { ListingStatusBadge, ProfilePreview, ReviewHistory, errorText } from '@/components/listing/ListingParts'
import type { Listing, ListingProfile } from '@/types'

export const LISTING_KEY = ['organization', 'listing']

const EMPTY: ListingProfile = { displayName: '', description: '', category: 'other', logoUrl: '', website: '' }

/** What the status means for the owner, in a sentence. */
function explain(l: Listing): string {
  switch (l.status) {
    case 'NONE': return 'Only members can see this organization. Fill in the profile and send it for review to be listed on Discover.'
    case 'PENDING': return l.approved
      ? 'Your changes are waiting for a Super Admin. People still see the approved version meanwhile.'
      : 'Waiting for a Super Admin to review. The organization is not listed until it is approved.'
    case 'APPROVED': return 'Everyone on Jizza can find this organization on Discover and ask to join.'
    case 'REJECTED': return 'The last request was not approved. Fix what the reviewer asked for and send it again.'
    case 'SUSPENDED': return 'A Super Admin suspended this listing. It is hidden from Discover until it is reinstated.'
  }
}

/** The active organization's public profile and listing (Owner and Admin). */
export function PublicListingPage() {
  const qc = useQueryClient()
  const { data: listing, isLoading, error } = useQuery({ queryKey: LISTING_KEY, queryFn: listingApi.get })
  const { data: categories } = useQuery({ queryKey: ['directory', 'categories'], queryFn: directoryApi.categories, staleTime: Infinity })
  const [form, setForm] = useState<ListingProfile>(EMPTY)
  const [dirty, setDirty] = useState(false)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    if (!listing || dirty) return
    const base = listing.draft ?? { ...EMPTY, displayName: listing.workspaceName }
    setForm({ ...base, logoUrl: base.logoUrl ?? '', website: base.website ?? '' })
  }, [listing, dirty])

  const onDone = (message: string) => (next: Listing) => {
    qc.setQueryData(LISTING_KEY, next)
    setDirty(false)
    setNotice(message)
  }
  const save = useMutation({ mutationFn: () => listingApi.save(form), onSuccess: onDone('Profile saved.') })
  const submit = useMutation({
    mutationFn: async () => {
      if (dirty) await listingApi.save(form)
      return listingApi.submit()
    },
    onSuccess: onDone('Sent for review. A Super Admin will approve or reject it.'),
  })
  const withdraw = useMutation({ mutationFn: listingApi.withdraw, onSuccess: onDone('Request withdrawn.') })
  const unlist = useMutation({ mutationFn: listingApi.unlist, onSuccess: onDone('Removed from Discover.') })
  const busy = save.isPending || submit.isPending || withdraw.isPending || unlist.isPending
  const failure = save.error ?? submit.error ?? withdraw.error ?? unlist.error

  function set<K extends keyof ListingProfile>(key: K, value: ListingProfile[K]) {
    setForm(f => ({ ...f, [key]: value }))
    setDirty(true)
    setNotice('')
  }

  function onSave(e: FormEvent) {
    e.preventDefault()
    save.mutate()
  }

  if (isLoading) return <div className="flex justify-center py-16"><Spinner /></div>
  if (error || !listing) return <div className="page"><p className="text-sm text-red-500">{errorText(error)}</p></div>

  const locked = listing.status === 'PENDING' || listing.status === 'SUSPENDED'
  const canSubmit = !locked && (listing.status !== 'APPROVED' || listing.changesSinceApproval || dirty)
  const listed = listing.approved !== null && (listing.status === 'APPROVED' || listing.status === 'PENDING')
  const lastRejection = listing.history.find(r => r.action === 'REJECTED' || r.action === 'SUSPENDED')
  const showReason = lastRejection && (listing.status === 'REJECTED' || listing.status === 'SUSPENDED'
    || (listing.status === 'APPROVED' && listing.changesSinceApproval && listing.history[0]?.action === 'REJECTED'))

  return (
    <div className="page">
      <PageHeader icon={Globe} title="Public listing"
        description="Choose whether people outside the organization can find it on Discover. A Super Admin approves every listing first." />

      <div className="grid max-w-5xl grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-6">
          <Card padding="lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <h2 className="font-semibold text-gray-900 dark:text-gray-100">Status</h2>
                <ListingStatusBadge status={listing.status} />
              </div>
              <div className="flex flex-wrap gap-2">
                {listing.status === 'PENDING' && (
                  <Button size="sm" variant="secondary" loading={withdraw.isPending} disabled={busy}
                    onClick={() => withdraw.mutate()}><Undo2 className="h-4 w-4" />Withdraw request</Button>
                )}
                {listed && (
                  <Button size="sm" variant="danger" loading={unlist.isPending} disabled={busy}
                    onClick={() => { if (window.confirm('Remove the organization from Discover? Listing it again needs a new review.')) unlist.mutate() }}>
                    <EyeOff className="h-4 w-4" />Unlist
                  </Button>
                )}
              </div>
            </div>
            <p className="mt-2 text-sm text-gray-600 dark:text-gray-300">{explain(listing)}</p>
            {showReason && lastRejection?.note && (
              <p className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
                <span className="font-medium">Reviewer's reason:</span> {lastRejection.note}
              </p>
            )}
            {listing.status === 'APPROVED' && listing.changesSinceApproval && !dirty && (
              <p className="mt-3 text-sm text-amber-700 dark:text-amber-300">You have changes that people can't see yet. Send them for review to publish them.</p>
            )}
          </Card>

          <Card padding="lg">
            <form onSubmit={onSave} className="flex flex-col gap-4">
              <h2 className="font-semibold text-gray-900 dark:text-gray-100">Public profile</h2>
              {locked && (
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  {listing.status === 'PENDING' ? 'Withdraw the request to change the profile.' : 'The profile can\'t be changed while the listing is suspended.'}
                </p>
              )}
              <fieldset disabled={locked || busy} className="flex flex-col gap-4">
                <Input label="Public name" required maxLength={100} value={form.displayName}
                  onChange={e => set('displayName', e.target.value)} />
                <Field label="Description" hint={`${form.description.length}/500. What the organization does and who should join.`}>
                  <textarea className={`${fieldClass} min-h-[96px]`} maxLength={500} value={form.description}
                    onChange={e => set('description', e.target.value)} />
                </Field>
                <Field label="Category">
                  <select className={fieldClass} value={form.category} onChange={e => set('category', e.target.value)}>
                    {(categories?.categories ?? ['other']).map(c => <option key={c} value={c}>{categoryLabel(c)}</option>)}
                  </select>
                </Field>
                <Input label="Logo link (optional)" type="url" placeholder="https://" maxLength={500}
                  value={form.logoUrl ?? ''} onChange={e => set('logoUrl', e.target.value)} />
                <Input label="Website (optional)" type="url" placeholder="https://" maxLength={500}
                  value={form.website ?? ''} onChange={e => set('website', e.target.value)} />
              </fieldset>
              {failure && <p className="text-sm text-red-500">{errorText(failure)}</p>}
              {notice && !failure && <p className="text-sm text-green-700 dark:text-green-300">{notice}</p>}
              {!locked && (
                <div className="flex flex-wrap items-center justify-end gap-2">
                  {listing.submissionsLeftToday !== null && listing.submissionsLeftToday < 3 && (
                    <span className="mr-auto text-xs text-gray-500 dark:text-gray-400">
                      {listing.submissionsLeftToday} review request{listing.submissionsLeftToday === 1 ? '' : 's'} left today
                    </span>
                  )}
                  <Button type="submit" variant="secondary" loading={save.isPending} disabled={busy || !dirty}>Save draft</Button>
                  <Button type="button" loading={submit.isPending} disabled={busy || !canSubmit}
                    onClick={() => submit.mutate()}>
                    <Send className="h-4 w-4" />{listing.approved ? 'Send changes for review' : 'Send for review'}
                  </Button>
                </div>
              )}
            </form>
          </Card>
        </div>

        <div className="flex flex-col gap-6">
          <Card padding="lg">
            <h2 className="mb-3 font-semibold text-gray-900 dark:text-gray-100">What people see</h2>
            {listing.approved && listed
              ? <ProfilePreview profile={listing.approved} />
              : <p className="text-sm text-gray-500 dark:text-gray-400">Nothing. The organization is not listed.</p>}
          </Card>
          <Card padding="lg">
            <h2 className="mb-3 font-semibold text-gray-900 dark:text-gray-100">Review history</h2>
            <ReviewHistory history={listing.history} />
          </Card>
        </div>
      </div>
    </div>
  )
}
