import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building2, Inbox } from 'lucide-react'
import { meApi } from '@/api/me'
import { CONTEXT_KEY, accountHome, useAuthorization } from '@/auth/useAuthorization'
import { useSwitchAccount } from '@/auth/useSwitchAccount'
import { ROLE_LABELS } from '@/lib/permissions'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/components/ui/EmptyState'
import type { ReceivedInvitation, WorkspaceSummary } from '@/types'

type Outcome =
  | { kind: 'accepted'; workspace: WorkspaceSummary; stayed?: boolean }
  | { kind: 'declined' }

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

/** Membership requests sent to the signed-in person, to accept or decline (flow C). */
export function InvitationsPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const { data: snapshot } = useAuthorization()
  const switchAccount = useSwitchAccount()
  const { data, isLoading, error } = useQuery({ queryKey: ['me', 'invitations'], queryFn: meApi.listInvitations })
  // Answered requests stay on screen with their outcome, so "Switch now" can be offered.
  const [outcomes, setOutcomes] = useState<Record<string, Outcome>>({})

  const next = (location.state as { next?: string } | null)?.next ?? accountHome(snapshot)
  const refreshContext = () => qc.invalidateQueries({ queryKey: CONTEXT_KEY })

  const accept = useMutation({
    mutationFn: (inv: ReceivedInvitation) => meApi.accept(inv.id),
    onSuccess: (workspace, inv) => {
      setOutcomes(o => ({ ...o, [inv.id]: { kind: 'accepted', workspace } }))
      refreshContext()
    },
  })
  const decline = useMutation({
    mutationFn: (inv: ReceivedInvitation) => meApi.decline(inv.id),
    onSuccess: (_, inv) => {
      setOutcomes(o => ({ ...o, [inv.id]: { kind: 'declined' } }))
      refreshContext()
    },
  })

  const invitations = data ?? []
  const open = invitations.filter(i => !outcomes[i.id])

  return (
    <div className="page">
      <PageHeader icon={Inbox} title="Membership requests"
        description="Organizations that asked you to join them. Accept to switch to them from the account menu."
        actions={<Button size="sm" variant="secondary" onClick={() => navigate(next, { replace: true })}>
          {open.length > 0 ? 'Decide later' : 'Continue'}
        </Button>} />

      {isLoading && <div className="flex justify-center py-16"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}

      {data && invitations.length === 0 && (
        <EmptyState icon={Inbox} title="No pending requests"
          description="When an organization adds you as a member, its request shows up here."
          action={{ label: 'Continue', onClick: () => navigate(next, { replace: true }) }} />
      )}

      {invitations.length > 0 && (
        <div className="grid max-w-3xl gap-4">
          {invitations.map(inv => {
            const outcome = outcomes[inv.id]
            return (
              <Card key={inv.id} padding="lg">
                <div className="flex flex-wrap items-start gap-3">
                  <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-100 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
                    <Building2 className="h-5 w-5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2 font-semibold text-gray-900 dark:text-gray-100">
                      {inv.workspaceName} <Badge>{ROLE_LABELS[inv.role]}</Badge>
                    </p>
                    <p className="text-sm text-gray-500 dark:text-gray-400">
                      Invited by {inv.invitedByEmail} · expires {new Date(inv.expiresAt).toLocaleDateString()}
                    </p>
                  </div>
                  {!outcome && (
                    <div className="flex gap-2">
                      <Button size="sm" variant="ghost" loading={decline.isPending && decline.variables?.id === inv.id}
                        disabled={accept.isPending} onClick={() => decline.mutate(inv)}>Decline</Button>
                      <Button size="sm" loading={accept.isPending && accept.variables?.id === inv.id}
                        disabled={decline.isPending} onClick={() => accept.mutate(inv)}>Accept</Button>
                    </div>
                  )}
                </div>

                {outcome?.kind === 'declined' && (
                  <p className="mt-3 text-sm text-gray-500 dark:text-gray-400">Declined.</p>
                )}
                {outcome?.kind === 'accepted' && !outcome.stayed && (
                  <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg bg-green-50 px-3 py-2 dark:bg-green-950/40">
                    <p className="flex-1 text-sm text-green-700 dark:text-green-300">You're now a member of {outcome.workspace.name}.</p>
                    <Button size="sm" variant="ghost"
                      onClick={() => setOutcomes(o => ({ ...o, [inv.id]: { ...outcome, stayed: true } }))}>Stay here</Button>
                    <Button size="sm" loading={switchAccount.isPending && switchAccount.variables === outcome.workspace.id}
                      onClick={() => switchAccount.mutate(outcome.workspace.id)}>
                      Switch to {outcome.workspace.name} now
                    </Button>
                  </div>
                )}
                {outcome?.kind === 'accepted' && outcome.stayed && (
                  <p className="mt-3 text-sm text-gray-500 dark:text-gray-400">
                    Accepted. Switch to {outcome.workspace.name} any time from the account menu.
                  </p>
                )}
              </Card>
            )
          })}
          {accept.error && <p className="text-sm text-red-500">{errorText(accept.error)}</p>}
          {decline.error && <p className="text-sm text-red-500">{errorText(decline.error)}</p>}
          {switchAccount.error && <p className="text-sm text-red-500">{errorText(switchAccount.error)}</p>}
        </div>
      )}
    </div>
  )
}
