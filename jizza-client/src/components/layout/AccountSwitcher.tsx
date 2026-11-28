import { type FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Building2, Check, Globe, House, Inbox, Plus, User, type LucideIcon } from 'lucide-react'
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

/** The icon for an account: a person, a building for an organization, a house for a Family. */
export function accountIcon(w: Pick<WorkspaceSummary, 'type'> | undefined): LucideIcon {
  return w?.type === 'ORGANIZATION' ? Building2 : w?.type === 'FAMILY' ? House : User
}

/** A role's name: in a Family the User role reads as "Member". */
export function roleName(w: Pick<WorkspaceSummary, 'type'>, role: WorkspaceSummary['role']): string {
  return w.type === 'FAMILY' && role === 'USER' ? 'Member' : ROLE_LABELS[role]
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
  if (active.type === 'FAMILY') return `Family${snapshot.role ? ` · ${roleName(active, snapshot.role)}` : ''}`
  if (active.type !== 'ORGANIZATION') return 'Individual account'
  const pub = (snapshot.publicWorkspaceIds ?? []).includes(active.id)
  return `${pub ? 'Public organization' : 'Private organization'}${snapshot.role ? ` · ${ROLE_LABELS[snapshot.role]}` : ''}`
}

/** Which kind of account a create dialog makes. */
export type CreateKind = 'organization' | 'family'

/**
 * The person's accounts with a switch for each, Pending requests, Create organization (flow D) and
 * Create a Family (offered while the person owns no Family).
 * Shown in the account menu at the bottom of the left menu and on Settings › General.
 */
export function AccountChoices({ inMenu = false, onNavigate, onCreate }: {
  /** Inside a popup menu: items take the menuitem role. */
  inMenu?: boolean
  /** Called before leaving the page, such as to close the menu or the phone drawer. */
  onNavigate?: () => void
  onCreate: (kind: CreateKind) => void
}) {
  const { data: snapshot } = useAuthorization()
  const switchAccount = useSwitchAccount()
  if (!snapshot) return null
  const active = snapshot.activeWorkspace
  const pending = snapshot.pendingInvitationCount
  const publicIds = new Set(snapshot.publicWorkspaceIds ?? [])
  const role = inMenu ? 'menuitem' : undefined
  const itemClass = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-100 disabled:opacity-50 dark:text-gray-200 dark:hover:bg-gray-800'
  const ownsFamily = snapshot.workspaces.some(w => w.type === 'FAMILY' && w.role === 'OWNER')

  function choose(w: WorkspaceSummary) {
    onNavigate?.()
    if (w.id === active.id) return
    switchAccount.mutate(w.id)
  }

  return (
    <>
      <p className="px-2.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">Accounts</p>
      {snapshot.workspaces.map(w => {
        const WIcon = accountIcon(w)
        return (
          <button key={w.id} role={role} onClick={() => choose(w)} disabled={switchAccount.isPending}
            aria-current={w.id === active.id ? 'true' : undefined} className={itemClass}>
            <WIcon className="h-4 w-4 shrink-0 text-gray-400" />
            <span className="min-w-0 flex-1 truncate">{accountLabel(w)}</span>
            {publicIds.has(w.id) && <PublicBadge />}
            {w.type !== 'PERSONAL' && <Badge>{w.type === 'FAMILY' ? `Family · ${roleName(w, w.role)}` : roleName(w, w.role)}</Badge>}
            {w.id === active.id && <Check className="h-4 w-4 shrink-0 text-brand-500" />}
          </button>
        )
      })}
      <Link to="/invitations" role={role} onClick={onNavigate} className={itemClass}>
        <Inbox className="h-4 w-4 shrink-0 text-gray-400" />
        Pending requests ({pending})
      </Link>
      <button role={role} onClick={() => onCreate('organization')} className={itemClass}>
        <Plus className="h-4 w-4 shrink-0 text-gray-400" />
        Create organization
      </button>
      {!ownsFamily && (
        <button role={role} onClick={() => onCreate('family')} className={itemClass}>
          <House className="h-4 w-4 shrink-0 text-gray-400" />
          Create a Family
        </button>
      )}
      {switchAccount.error && <p className="px-2.5 py-1.5 text-xs text-red-500">{errorText(switchAccount.error)}</p>}
    </>
  )
}

/** Create organization, then offer to switch to it. */
export function CreateOrganization({ open, onClose, onNavigate }: { open: boolean; onClose: () => void; onNavigate?: () => void }) {
  const switchAccount = useSwitchAccount()
  return (
    <CreateAccountDialog kind="organization" open={open} onClose={onClose}
      onSwitch={w => { onClose(); onNavigate?.(); switchAccount.mutate(w.id) }} />
  )
}

/** Create a Family (one field, its name), then offer to switch to it to add members. */
export function CreateFamily({ open, onClose, onNavigate }: { open: boolean; onClose: () => void; onNavigate?: () => void }) {
  const switchAccount = useSwitchAccount()
  return (
    <CreateAccountDialog kind="family" open={open} onClose={onClose}
      onSwitch={w => { onClose(); onNavigate?.(); switchAccount.mutate(w.id) }} />
  )
}

const CREATE_WORDS: Record<CreateKind, { title: string; done: string; field: string; about: string }> = {
  organization: {
    title: 'Create organization', done: 'Organization created', field: 'Organization name',
    about: 'You will be its Owner and can add members from its Members page.',
  },
  family: {
    title: 'Create a Family', done: 'Family created', field: 'Family name',
    about: 'You will be its Owner and can invite the people in your family from its Members page. Each person keeps their own account; you choose what of a health record each one sees from the Health tab.',
  },
}

function CreateAccountDialog({ kind, open, onClose, onSwitch }: {
  kind: CreateKind; open: boolean; onClose: () => void; onSwitch: (w: WorkspaceSummary) => void
}) {
  const words = CREATE_WORDS[kind]
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [created, setCreated] = useState<WorkspaceSummary | null>(null)
  const create = useMutation({
    mutationFn: (n: string) => kind === 'family' ? workspacesApi.createFamily(n) : workspacesApi.create(n),
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
    <Dialog open={open} onClose={onClose} title={created ? words.done : words.title}
      description={created ? undefined : words.about}>
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
          <Input label={words.field} required autoFocus minLength={2} maxLength={100}
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
