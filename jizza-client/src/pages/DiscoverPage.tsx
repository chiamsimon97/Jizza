import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Compass, Search, UserPlus } from 'lucide-react'
import { categoryLabel, directoryApi } from '@/api/listings'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import { OrgLogo, ProfilePreview, errorText } from '@/components/listing/ListingParts'

/** Organizations a Super Admin approved for listing, for everyone signed in. */
export function DiscoverPage() {
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [category, setCategory] = useState('')
  const [page, setPage] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => { setDebounced(query); setPage(0) }, 250)
    return () => clearTimeout(t)
  }, [query])

  const { data: categories } = useQuery({ queryKey: ['directory', 'categories'], queryFn: directoryApi.categories, staleTime: Infinity })
  const { data, isLoading, error } = useQuery({
    queryKey: ['directory', debounced, category, page],
    queryFn: () => directoryApi.search(debounced, category, page),
    placeholderData: keepPreviousData,
  })
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1

  return (
    <div className="page">
      <PageHeader icon={Compass} title="Discover"
        description="Public organizations on Jizza. Each one was approved before it appears here, and you can ask to join." />

      <div className="mb-5 flex max-w-5xl flex-wrap gap-3">
        <label className="relative min-w-[220px] flex-1">
          <span className="sr-only">Search organizations</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search organizations"
            className={`${fieldClass} w-full pl-9`} />
        </label>
        <select aria-label="Category" className={`${fieldClass} w-48`} value={category}
          onChange={e => { setCategory(e.target.value); setPage(0) }}>
          <option value="">All categories</option>
          {(categories?.categories ?? []).map(c => <option key={c} value={c}>{categoryLabel(c)}</option>)}
        </select>
      </div>

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}
      {data && data.items.length === 0 && (
        <EmptyState icon={Compass} title={debounced || category ? 'No organizations match' : 'No public organizations yet'}
          description={debounced || category ? 'Try other words or another category.' : 'Organizations appear here once a Super Admin approves their listing.'} />
      )}
      {data && data.items.length > 0 && (
        <>
          <ul className="grid max-w-5xl grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.items.map(o => (
              <li key={o.id}>
                <Link to={`/discover/${o.id}`} className="block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500">
                  <Card padding="lg" interactive className="h-full">
                    <div className="flex items-start gap-3">
                      <OrgLogo profile={o.profile} size={40} />
                      <div className="min-w-0">
                        <p className="truncate font-semibold text-gray-900 dark:text-gray-100">{o.profile.displayName}</p>
                        <Badge className="mt-1">{categoryLabel(o.profile.category)}</Badge>
                      </div>
                    </div>
                    {o.profile.description && (
                      <p className="mt-3 line-clamp-3 text-sm text-gray-600 dark:text-gray-300">{o.profile.description}</p>
                    )}
                  </Card>
                </Link>
              </li>
            ))}
          </ul>
          {pages > 1 && (
            <div className="mt-5 flex max-w-5xl items-center justify-center gap-3 text-sm text-gray-600 dark:text-gray-300">
              <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <span>Page {page + 1} of {pages}</span>
              <Button size="sm" variant="ghost" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

/** One public organization's profile, and asking to join it. */
export function DiscoverProfilePage() {
  const { orgId = '' } = useParams()
  const qc = useQueryClient()
  const key = ['directory', 'profile', orgId]
  const { data, isLoading, error } = useQuery({ queryKey: key, queryFn: () => directoryApi.profile(orgId) })
  const [message, setMessage] = useState('')
  const [asking, setAsking] = useState(false)

  const ask = useMutation({
    mutationFn: () => directoryApi.askToJoin(orgId, message),
    onSuccess: () => { setAsking(false); setMessage(''); qc.invalidateQueries({ queryKey: key }) },
  })
  const cancel = useMutation({
    mutationFn: () => directoryApi.cancel(orgId),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })

  return (
    <div className="page">
      <Link to="/discover" className="mb-4 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100">
        <ArrowLeft className="h-4 w-4" />Discover
      </Link>
      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && (
        <EmptyState icon={Compass} title="Organization not found" description="It may have been unlisted. Go back to Discover to find others." />
      )}
      {data && (
        <Card padding="lg" className="max-w-3xl">
          <ProfilePreview profile={data.profile} />
          {data.listedAt && (
            <p className="mt-4 text-xs text-gray-500 dark:text-gray-400">Listed since {new Date(data.listedAt).toLocaleDateString()}</p>
          )}
          <div className="mt-5 border-t border-gray-100 pt-5 dark:border-gray-800">
            {data.member ? (
              <p className="text-sm text-gray-600 dark:text-gray-300">You're a member. Switch to it from the account menu.</p>
            ) : data.pendingRequest ? (
              <div className="flex flex-wrap items-center gap-3">
                <p className="text-sm text-gray-600 dark:text-gray-300">
                  You asked to join on {new Date(data.pendingRequest.createdAt).toLocaleDateString()}. An Owner or Admin will accept or decline.
                </p>
                <Button size="sm" variant="ghost" loading={cancel.isPending} onClick={() => cancel.mutate()}>Withdraw request</Button>
              </div>
            ) : asking ? (
              <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); ask.mutate() }}>
                <Field label="Message (optional)" hint="Tell them who you are and why you'd like to join.">
                  <textarea className={`${fieldClass} min-h-[80px]`} maxLength={500} autoFocus
                    value={message} onChange={e => setMessage(e.target.value)} />
                </Field>
                <div className="flex justify-end gap-2">
                  <Button type="button" variant="ghost" onClick={() => setAsking(false)}>Cancel</Button>
                  <Button type="submit" loading={ask.isPending}>Send request</Button>
                </div>
              </form>
            ) : (
              <Button onClick={() => setAsking(true)}><UserPlus className="h-4 w-4" />Ask to join</Button>
            )}
            {(ask.error || cancel.error) && <p className="mt-2 text-sm text-red-500">{errorText(ask.error ?? cancel.error)}</p>}
          </div>
        </Card>
      )}
    </div>
  )
}
