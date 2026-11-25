import { type FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Building2, Check, Globe, Inbox, Plus, User } from 'lucide-react'
import { workspacesApi } from '@/api/workspaces'
import { CONTEXT_KEY, useAuthorization } from '@/auth/useAuthorization'
import { useSwitchAccount } from '@/auth/useSwitchAccount'
import { ROLE_LABELS } from '@/lib/permissions'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Input } from '@/components/ui/Input'
import type { AuthorizationSnapshot, WorkspaceSummary } from '@/types'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

export function accountLabel(w: WorkspaceSummary) {
  return w.type === 'PERSONAL' ? 'Personal' : w.name
}

/** Marks an organization listed on Discover. Private accounts carry no badge. */
export function PublicBadge() {
  return (
    <span title="Listed publicly on Discover"
      className="inline-flex shrink-0 items-center gap-1 rounded-full bg-green-100 px-1.5 py-0.5 text-[10px] font-semibold text-green-700 ring-1 ring-inset ring-green-500/20 dark:bg-green-900/30 dark:text-green-400">
      <Globe className="h-3 w-3" />Public
    </span>
  )
}

/** How the active account is described under the person's name: "Individual account", "Private organization · Admin". */
export function accountDescription(snapshot: AuthorizationSnapshot): string {
  const active = snapshot.activeWorkspace
  if (active.type !== 'ORGANIZATION') return 'Individual account'
  const pub = (snapshot.publicWorkspaceIds ?? []).includes(active.id)
  return `${pub ? 'Public organization' : 'Private organization'}${snapshot.role ? ` · ${ROLE_LABELS[snapshot.role]}` : ''}`
}

/**
 * The person's accounts with a switch for each, Pending requests and Create organization (flow D).
 * Shown in the account menu at the bottom of the left menu and on Settings › General.
 */
export function AccountChoices({ inMenu = false, onNavigate, onCreate }: {
  /** Inside a popup menu: items take the menuitem role. */
  inMenu?: boolean
  /** Called before leaving the page, such as to close the menu or the phone drawer. */
  onNavigate?: () => void
  onCreate: () => void
}) {
  const { data: snapshot } = useAuthorization()
  const switchAccount = useSwitchAccount()
  if (!snapshot) return null
  const active = snapshot.activeWorkspace
  const pending = snapshot.pendingInvitationCount
  const publicIds = new Set(snapshot.publicWorkspaceIds ?? [])
  const role = inMenu ? 'menuitem' : undefined
  const itemClass = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-100 disabled:opacity-50 dark:text-gray-200 dark:hover:bg-gray-800'

  function choose(w: WorkspaceSummary) {
    onNavigate?.()
    if (w.id === active.id) return
    switchAccount.mutate(w.id)
  }

  return (
    <>
      <p className="px-2.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">Accounts</p>
      {snapshot.workspaces.map(w => {
        const WIcon = w.type === 'ORGANIZATION' ? Building2 : User
        return (
          <button key={w.id} role={role} onClick={() => choose(w)} disabled={switchAccount.isPending}
            aria-current={w.id === active.id ? 'true' : undefined} className={itemClass}>
            <WIcon className="h-4 w-4 shrink-0 text-gray-400" />
            <span className="min-w-0 flex-1 truncate">{accountLabel(w)}</span>
            {publicIds.has(w.id) && <PublicBadge />}
            {w.type === 'ORGANIZATION' && <Badge>{ROLE_LABELS[w.role]}</Badge>}
            {w.id === active.id && <Check className="h-4 w-4 shrink-0 text-brand-500" />}
          </button>
        )
      })}
      <Link to="/invitations" role={role} onClick={onNavigate} className={itemClass}>
        <Inbox className="h-4 w-4 shrink-0 text-gray-400" />
        Pending requests ({pending})
      </Link>
      <button role={role} onClick={onCreate} className={itemClass}>
        <Plus className="h-4 w-4 shrink-0 text-gray-400" />
        Create organization
      </button>
      {switchAccount.error && <p className="px-2.5 py-1.5 text-xs text-red-500">{errorText(switchAccount.error)}</p>}
    </>
  )
}

/** Create organization, then offer to switch to it. */
export function CreateOrganization({ open, onClose, onNavigate }: { open: boolean; onClose: () => void; onNavigate?: () => void }) {
  const switchAccount = useSwitchAccount()
  return (
    <CreateOrganizationDialog open={open} onClose={onClose}
      onSwitch={w => { onClose(); onNavigate?.(); switchAccount.mutate(w.id) }} />
  )
}

function CreateOrganizationDialog({ open, onClose, onSwitch }: {
  open: boolean; onClose: () => void; onSwitch: (w: WorkspaceSummary) => void
}) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [created, setCreated] = useState<WorkspaceSummary | null>(null)
  const create = useMutation({
    mutationFn: (n: string) => workspacesApi.create(n),
    onSuccess: w => {
      setCreated(w)
      qc.invalidateQueries({ queryKey: CONTEXT_KEY })
    },
  })

  useEffect(() => {
    if (open) { setName(''); setCreated(null); create.reset() }
  }, [open])

  function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length < 2 || trimmed.length > 100) return
    create.mutate(trimmed)
  }

  const trimmedLength = name.trim().length
  const invalid = name.length > 0 && (trimmedLength < 2 || trimmedLength > 100)

  return (
    <Dialog open={open} onClose={onClose} title={created ? 'Organization created' : 'Create organization'}
      description={created ? undefined : 'You will be its Owner and can add members from its Members page.'}>
      {created ? (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-gray-600 dark:text-gray-300">{created.name} is ready. Switch to it now?</p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>Not now</Button>
            <Button type="button" onClick={() => onSwitch(created)}>Switch to {created.name}</Button>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-3">
          <Input label="Organization name" required autoFocus minLength={2} maxLength={100}
            value={name} onChange={e => setName(e.target.value)}
            error={invalid ? 'Use 2 to 100 characters' : undefined} />
          {create.error && <p className="text-sm text-red-500">{errorText(create.error)}</p>}
          <div className="mt-1 flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
            <Button type="submit" loading={create.isPending} disabled={trimmedLength < 2 || trimmedLength > 100}>Create</Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
