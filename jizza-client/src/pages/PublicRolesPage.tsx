import { useEffect, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, UserCog } from 'lucide-react'
import { publicRolesApi } from '@/api/listings'
import { useAuthorization } from '@/auth/useAuthorization'
import { PageHeader } from '@/components/ui/PageHeader'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { fieldClass } from '@/components/ui/SlideOver'
import { errorText } from '@/components/listing/ListingParts'
import { ROLE_LABELS } from '@/lib/permissions'
import type { WorkspaceRole } from '@/types'

const ASSIGNABLE: WorkspaceRole[] = ['USER', 'DEVELOPER', 'ADMIN']

/** The Super Admin's Public roles: who is an Admin, Developer or User in the Public organization. */
export function PublicRolesPage() {
  const { data: snapshot } = useAuthorization()
  const qc = useQueryClient()
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), 250)
    return () => clearTimeout(t)
  }, [query])

  const superAdmin = snapshot?.platformRole === 'SUPER_ADMIN'
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'public-roles', debounced],
    queryFn: () => publicRolesApi.list(debounced),
    enabled: superAdmin,
    placeholderData: keepPreviousData,
  })
  const setRole = useMutation({
    mutationFn: ({ id, role }: { id: string; role: WorkspaceRole }) => publicRolesApi.setRole(id, role),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'public-roles'] }),
  })

  if (snapshot && !superAdmin) {
    return <div className="page"><p className="text-sm text-gray-500 dark:text-gray-400">Only a Super Admin can change Public roles.</p></div>
  }

  return (
    <div className="page">
      <PageHeader icon={UserCog} title="Public roles"
        description={`Everyone on Jizza belongs to ${data?.workspaceName || 'the Public organization'} as a User. Make someone a Developer or Admin to let them publish there.`} />

      <ul className="mb-5 max-w-4xl list-disc space-y-1 pl-5 text-sm text-gray-600 dark:text-gray-300">
        <li><span className="font-medium">User</span>: uses what's published in the Public organization.</li>
        <li><span className="font-medium">Developer</span>: also publishes connectors there.</li>
        <li><span className="font-medium">Admin</span>: also publishes agent templates and manages its listing.</li>
      </ul>

      <label className="relative mb-4 block max-w-md">
        <span className="sr-only">Search by email</span>
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
        <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search by email"
          className={`${fieldClass} w-full pl-9`} />
      </label>

      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}
      {setRole.error && <p className="mb-3 text-sm text-red-500">{errorText(setRole.error)}</p>}
      {data && data.members.length === 0 && (
        <p className="text-sm text-gray-500 dark:text-gray-400">{debounced ? 'Nobody matches that email.' : 'Nobody has joined yet.'}</p>
      )}
      {data && data.members.length > 0 && (
        <ul className="max-w-4xl divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200 bg-white dark:divide-gray-800 dark:border-gray-800 dark:bg-gray-950">
          {data.members.map(m => (
            <li key={m.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <span className="min-w-0 truncate text-sm text-gray-900 dark:text-gray-100">
                {m.email}{m.you && <span className="ml-1 text-gray-500">(you)</span>}
              </span>
              {m.role === 'OWNER'
                ? <Badge>{ROLE_LABELS.OWNER}</Badge>
                : (
                  <select className={`${fieldClass} w-36 py-1.5`} aria-label={`Public role for ${m.email}`} value={m.role}
                    disabled={setRole.isPending && setRole.variables?.id === m.id}
                    onChange={e => setRole.mutate({ id: m.id, role: e.target.value as WorkspaceRole })}>
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
