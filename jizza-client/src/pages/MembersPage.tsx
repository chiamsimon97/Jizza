import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LogOut, Send, UserPlus, Users } from 'lucide-react'
import { workspacesApi } from '@/api/workspaces'
import { useAuthorization } from '@/auth/useAuthorization'
import { usePermission } from '@/auth/usePermission'
import { applySnapshot } from '@/auth/useSwitchAccount'
import { Can } from '@/auth/Can'
import { PERMISSIONS, ROLE_LABELS } from '@/lib/permissions'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { Dialog } from '@/components/ui/Dialog'
import { Field, fieldClass } from '@/components/ui/SlideOver'
import type { Invitation, InvitationStatus, Member, WorkspaceRole } from '@/types'

const MEMBERS_KEY = ['workspace', 'members']
const INVITATIONS_KEY = ['workspace', 'invitations']

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString()
}

const STATUS_BADGE: Record<InvitationStatus, { label: string; variant: 'default' | 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING:  { label: 'Pending',  variant: 'info' },
  ACCEPTED: { label: 'Accepted', variant: 'success' },
  DECLINED: { label: 'Declined', variant: 'danger' },
  REVOKED:  { label: 'Revoked',  variant: 'default' },
  EXPIRED:  { label: 'Expired',  variant: 'warning' },
}

/** The active organization's members and membership requests (flow B). */
export function MembersPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: snapshot } = useAuthorization()
  const [adding, setAdding] = useState(false)
  const [notice, setNotice] = useState('')

  const leave = useMutation({
    mutationFn: () => workspacesApi.leave(),
    onSuccess: personal => {
      applySnapshot(qc, personal)
      navigate('/chat', { replace: true })
    },
  })

  const orgName = snapshot?.activeWorkspace.name ?? 'this organization'
  const isOwner = snapshot?.role === 'OWNER'

  return (
    <div className="page">
      <PageHeader icon={Users} title="Members"
        description={`Who belongs to ${orgName}, their roles, and the requests sent to join.`}
        actions={
          <Can permission={PERMISSIONS.MEMBERS_INVITE}>
            <Button size="sm" onClick={() => { setNotice(''); setAdding(true) }}><UserPlus className="h-4 w-4" />Add member</Button>
          </Can>
        } />

      {notice && (
        <p className="mb-4 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-700 dark:bg-green-950/40 dark:text-green-300">{notice}</p>
      )}

      <div className="grid max-w-5xl gap-6">
        <MembersTable />
        <RequestsSection onSent={setNotice} />

        {snapshot && !isOwner && (
          <Card padding="lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="font-semibold text-gray-900 dark:text-gray-100">Leave organization</h2>
                <p className="text-sm text-gray-500 dark:text-gray-400">You will lose access to {orgName} until someone adds you again.</p>
              </div>
              <Button size="sm" variant="danger" loading={leave.isPending} onClick={() => {
                if (window.confirm(`Leave ${orgName}?`)) leave.mutate()
              }}><LogOut className="h-4 w-4" />Leave organization</Button>
            </div>
            {leave.error && <p className="mt-3 text-sm text-red-500">{errorText(leave.error)}</p>}
          </Card>
        )}
      </div>

      <AddMemberDialog open={adding} onClose={() => setAdding(false)}
        onSent={email => { setAdding(false); setNotice(`Request sent to ${email}.`) }} />
    </div>
  )
}

function MembersTable() {
  const qc = useQueryClient()
  const { data: snapshot } = useAuthorization()
  const { data: members, isLoading, error } = useQuery({ queryKey: MEMBERS_KEY, queryFn: workspacesApi.members })
  const canModify = usePermission(PERMISSIONS.MEMBERS_MODIFY)
  const canRemove = usePermission(PERMISSIONS.MEMBERS_REMOVE)
  const assignable = snapshot?.assignableRoles ?? []

  const changeRole = useMutation({
    mutationFn: ({ id, role }: { id: string; role: WorkspaceRole }) => workspacesApi.changeRole(id, role),
    onSettled: () => qc.invalidateQueries({ queryKey: MEMBERS_KEY }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => workspacesApi.removeMember(id),
    onSettled: () => qc.invalidateQueries({ queryKey: MEMBERS_KEY }),
  })

  /** Whether this role could act on the member at all: never the Owner or yourself. */
  const actionable = (m: Member) => m.role !== 'OWNER' && !m.you
  /** A role that holds the permission still can't act on a member above what it may assign. */
  const outranks = (m: Member) => assignable.includes(m.role)

  return (
    <section>
      <h2 className="mb-3 text-lg font-semibold text-gray-900 dark:text-gray-100">People</h2>
      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}
      {members && (
        <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-800">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-gray-50 text-xs uppercase tracking-wider text-gray-500 dark:bg-gray-900 dark:text-gray-400">
              <tr>
                <th className="px-4 py-2.5 font-medium">Email</th>
                <th className="px-4 py-2.5 font-medium">Role</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
                <th className="px-4 py-2.5 font-medium">Joined</th>
                <th className="px-4 py-2.5 font-medium"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white dark:divide-gray-800 dark:bg-gray-950">
              {members.map(m => {
                const showRoleSelect = actionable(m) && (!canModify || outranks(m))
                const showRemove = actionable(m) && (!canRemove || outranks(m))
                const roles = Array.from(new Set<WorkspaceRole>([m.role, ...assignable]))
                return (
                  <tr key={m.id}>
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-900 dark:text-gray-100">{m.email}</span>
                      {m.you && <Badge variant="info" className="ml-2">You</Badge>}
                    </td>
                    <td className="px-4 py-3">
                      {m.role === 'OWNER' ? (
                        <Badge variant="success">Owner</Badge>
                      ) : showRoleSelect ? (
                        <Can permission={PERMISSIONS.MEMBERS_MODIFY} mode="disable">
                          <select className={`${fieldClass} w-36 py-1.5 disabled:cursor-not-allowed disabled:opacity-60`}
                            aria-label={`Role for ${m.email}`} value={m.role}
                            disabled={changeRole.isPending && changeRole.variables?.id === m.id}
                            onChange={e => changeRole.mutate({ id: m.id, role: e.target.value as WorkspaceRole })}>
                            {roles.map(r => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
                          </select>
                        </Can>
                      ) : (
                        <span className="text-gray-700 dark:text-gray-300">{ROLE_LABELS[m.role]}</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <Badge variant={m.status === 'ACTIVE' ? 'success' : 'warning'}>{m.status === 'ACTIVE' ? 'Active' : 'Suspended'}</Badge>
                    </td>
                    <td className="px-4 py-3 text-gray-500 dark:text-gray-400">{formatDate(m.joinedAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {showRemove && (
                        <Can permission={PERMISSIONS.MEMBERS_REMOVE} mode="disable">
                          <Button size="sm" variant="ghost" loading={remove.isPending && remove.variables === m.id}
                            onClick={() => { if (window.confirm(`Remove ${m.email} from the organization?`)) remove.mutate(m.id) }}>
                            Remove
                          </Button>
                        </Can>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {changeRole.error && <p className="mt-2 text-sm text-red-500">{errorText(changeRole.error)}</p>}
      {remove.error && <p className="mt-2 text-sm text-red-500">{errorText(remove.error)}</p>}
    </section>
  )
}

/** Requests still open, or ones that can be sent again. Accepted ones are members now. */
const SHOWN: InvitationStatus[] = ['PENDING', 'DECLINED', 'EXPIRED']

function RequestsSection({ onSent }: { onSent: (notice: string) => void }) {
  const qc = useQueryClient()
  const { data, isLoading, error } = useQuery({ queryKey: INVITATIONS_KEY, queryFn: workspacesApi.invitations })
  const revoke = useMutation({
    mutationFn: (id: string) => workspacesApi.revokeInvitation(id),
    onSettled: () => qc.invalidateQueries({ queryKey: INVITATIONS_KEY }),
  })
  const resend = useMutation({
    mutationFn: (inv: Invitation) => workspacesApi.invite(inv.email, inv.role),
    onSuccess: (_, inv) => onSent(`Request sent to ${inv.email}.`),
    onSettled: () => qc.invalidateQueries({ queryKey: INVITATIONS_KEY }),
  })

  const requests = (data ?? []).filter(i => SHOWN.includes(i.status))
  const pendingEmails = new Set(requests.filter(i => i.status === 'PENDING').map(i => i.email.toLowerCase()))

  return (
    <section>
      <h2 className="mb-3 text-lg font-semibold text-gray-900 dark:text-gray-100">Requests</h2>
      {isLoading && <div className="flex justify-center py-10"><Spinner /></div>}
      {error && <p className="text-sm text-red-500">{errorText(error)}</p>}
      {data && requests.length === 0 && (
        <p className="rounded-xl border border-dashed border-gray-200 p-6 text-center text-sm text-gray-500 dark:border-gray-800 dark:text-gray-400">
          No open requests.
        </p>
      )}
      {requests.length > 0 && (
        <ul className="divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200 bg-white dark:divide-gray-800 dark:border-gray-800 dark:bg-gray-950">
          {requests.map(inv => (
            <li key={inv.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-gray-900 dark:text-gray-100">
                  <span className="truncate">{inv.email}</span>
                  <Badge>{ROLE_LABELS[inv.role]}</Badge>
                  <Badge variant={STATUS_BADGE[inv.status].variant}>{STATUS_BADGE[inv.status].label}</Badge>
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  Invited by {inv.invitedByEmail} · sent {formatDate(inv.createdAt)} · {inv.status === 'EXPIRED' ? 'expired' : 'expires'} {formatDate(inv.expiresAt)}
                </p>
              </div>
              {inv.status === 'PENDING' && (
                <Can permission={PERMISSIONS.MEMBERS_INVITE} mode="disable">
                  <Button size="sm" variant="ghost" loading={revoke.isPending && revoke.variables === inv.id}
                    onClick={() => { if (window.confirm(`Revoke the request to ${inv.email}?`)) revoke.mutate(inv.id) }}>
                    Revoke
                  </Button>
                </Can>
              )}
              {inv.status !== 'PENDING' && !pendingEmails.has(inv.email.toLowerCase()) && (
                <Can permission={PERMISSIONS.MEMBERS_INVITE} mode="disable">
                  <Button size="sm" variant="secondary" loading={resend.isPending && resend.variables?.id === inv.id}
                    onClick={() => resend.mutate(inv)}>
                    <Send className="h-3.5 w-3.5" />Send again
                  </Button>
                </Can>
              )}
            </li>
          ))}
        </ul>
      )}
      {revoke.error && <p className="mt-2 text-sm text-red-500">{errorText(revoke.error)}</p>}
      {resend.error && <p className="mt-2 text-sm text-red-500">{errorText(resend.error)}</p>}
    </section>
  )
}

function AddMemberDialog({ open, onClose, onSent }: { open: boolean; onClose: () => void; onSent: (email: string) => void }) {
  const qc = useQueryClient()
  const { data: snapshot } = useAuthorization()
  const roles = snapshot?.assignableRoles ?? []
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<WorkspaceRole | ''>('')
  const chosenRole: WorkspaceRole | undefined = role || (roles.includes('USER') ? 'USER' : roles[0])

  const invite = useMutation({
    mutationFn: () => workspacesApi.invite(email.trim(), chosenRole!),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: INVITATIONS_KEY })
      const sentTo = email.trim()
      setEmail('')
      setRole('')
      onSent(sentTo)
    },
  })

  function close() {
    invite.reset()
    onClose()
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!chosenRole) return
    invite.mutate()
  }

  return (
    <Dialog open={open} onClose={close} title="Add member"
      description="They'll see the request the next time they sign in, and can accept or decline it.">
      <form onSubmit={submit} className="flex flex-col gap-3">
        <Input label="Email" type="email" required autoFocus autoComplete="off"
          value={email} onChange={e => setEmail(e.target.value)} />
        <Field label="Role">
          <select className={fieldClass} value={chosenRole ?? ''} onChange={e => setRole(e.target.value as WorkspaceRole)}>
            {roles.map(r => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
        </Field>
        {roles.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">Your role can't assign any roles.</p>}
        {invite.error && <p className="text-sm text-red-500">{errorText(invite.error)}</p>}
        <div className="mt-1 flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>Cancel</Button>
          <Button type="submit" loading={invite.isPending} disabled={!chosenRole}>Send request</Button>
        </div>
      </form>
    </Dialog>
  )
}
