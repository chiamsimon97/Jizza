import { useEffect, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, UserCog } from 'lucide-react'
import { accountRegistryApi } from '@/api/listings'
import { useAuthorization } from '@/auth/useAuthorization'
import { PageHeader } from '@/components/ui/PageHeader'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { fieldClass } from '@/components/ui/SlideOver'
import { errorText } from '@/components/listing/ListingParts'
import { ROLE_LABELS } from '@/lib/permissions'
import type { AccountSource, WorkspaceRole, WorkspaceType } from '@/types'

const ASSIGNABLE: WorkspaceRole[] = ['USER', 'DEVELOPER', 'ADMIN']

const SOURCE_LABELS: Record<AccountSource, string> = {
  INDIVIDUAL_SIGNUP: 'Individual sign-up',
  ORGANIZATION_SIGNUP: 'Team sign-up',
  ORGANIZATION_CREATED: 'Team created later',
  FAMILY_CREATED: 'Family created later',
  EXISTING: 'Before the registry',
}

const TYPE_FILTERS: { value: '' | WorkspaceType; label: string }[] = [
  { value: '', label: 'All accounts' },
  { value: 'PERSONAL', label: 'Individual' },
  { value: 'ORGANIZATION', label: 'Team' },
  { value: 'FAMILY', label: 'Family' },
]

const TYPE_LABELS: Record<WorkspaceType, string> = { PERSONAL: 'Individual', ORGANIZATION: 'Team', FAMILY: 'Family' }

/**
 * The Super Admin's view of Jizza Public: every Individual and Team account registered there, and
 * each person's role. Nobody is a member just for having an account; making someone a Developer or
 * Admin adds them.
 */
export function RegisteredAccountsPage() {
  const { data: snapshot } = useAuthorization()
  const qc = useQueryClient()
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [type, setType] = useState<'' | WorkspaceType>('')

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), 250)
    return () => clearTimeout(t)
  }, [query])

  const superAdmin = snapshot?.platformRole === 'SUPER_ADMIN'
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'accounts', debounced],
    queryFn: () => accountRegistryApi.list(debounced),
    enabled: superAdmin,
    placeholderData: keepPreviousData,
  })
  const setRole = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: WorkspaceRole }) => accountRegistryApi.setRole(userId, role),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'accounts'] }),
  })

  if (snapshot && !superAdmin) {
    return <div className="page"><p className="text-sm text-gray-500 dark:text-gray-400">Only a Super Admin can see registered accounts.</p></div>
  }

  const accounts = (data?.accounts ?? []).filter(a => !type || a.type === type)
  const individuals = data?.accounts.filter(a => a.type === 'PERSONAL').length ?? 0
  const families = data?.accounts.filter(a => a.type === 'FAMILY').length ?? 0
  const teams = (data?.accounts.length ?? 0) - individuals - families
  const publicName = data?.workspaceName || 'Jizza Public'

  return (
    <div className="page">
      <PageHeader icon={UserCog} title="Registered accounts"
        description={`Every Individual, Family and Team account is registered in ${publicName} when it's created. People don't join ${publicName} or see it, unless you make them a Developer or Admin.`} />

      {data && (
        <p className="mb-4 text-sm text-gray-600 dark:text-gray-300">
          {families
            ? `${individuals} Individual, ${teams} Team and ${families} Family account${families === 1 ? '' : 's'}`
            : `${individuals} Individual and ${teams} Team account${teams === 1 ? '' : 's'}`}{debounced ? ' match' : ''}.
        </p>
      )}

      <div className="mb-4 flex max-w-4xl flex-wrap gap-3">
        <label className="relative min-w-[220px] flex-1">
          <span className="sr-only">Search accounts</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search by account name or email"
            className={`${fieldClass} w-full pl-9`} />
        </label>
        <select aria-label="Account type" className={`${fieldClass} w-44`} value={type}
          onChange={e => setType(e.target.value as '' | WorkspaceType)}>
          {TYPE_FILTERS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
        </select>
      </div>

      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}
      {setRole.error && <p className="mb-3 text-sm text-red-500">{errorText(setRole.error)}</p>}
      {data && accounts.length === 0 && (
        <p className="text-sm text-gray-500 dark:text-gray-400">{debounced || type ? 'No accounts match.' : 'No accounts are registered yet.'}</p>
      )}
      {accounts.length > 0 && (
        <ul className="max-w-4xl divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200 bg-white dark:divide-gray-800 dark:border-gray-800 dark:bg-gray-950">
          {accounts.map(a => (
            <li key={a.workspaceId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                  {a.type === 'PERSONAL' ? a.ownerEmail : a.name}
                  <Badge>{TYPE_LABELS[a.type] ?? 'Team'}</Badge>
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {a.type !== 'PERSONAL' && `Owner ${a.ownerEmail} · `}
                  {SOURCE_LABELS[a.source]} · {new Date(a.registeredAt).toLocaleDateString()}
                </p>
              </div>
              {a.type !== 'PERSONAL'
                ? null
                : a.publicRole === 'OWNER'
                  ? <Badge>{ROLE_LABELS.OWNER}</Badge>
                  : (
                    <select className={`${fieldClass} w-36 py-1.5`} aria-label={`Role in ${publicName} for ${a.ownerEmail}`} value={a.publicRole}
                      disabled={setRole.isPending && setRole.variables?.userId === a.ownerUserId}
                      onChange={e => setRole.mutate({ userId: a.ownerUserId, role: e.target.value as WorkspaceRole })}>
                      {ASSIGNABLE.map(r => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
                    </select>
                  )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
