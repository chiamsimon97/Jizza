import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Building2, Check, ChevronsUpDown, Globe, Inbox, Plus, ShieldCheck, User } from 'lucide-react'
import { workspacesApi } from '@/api/workspaces'
import { CONTEXT_KEY, useAuthorization } from '@/auth/useAuthorization'
import { useSwitchAccount } from '@/auth/useSwitchAccount'
import { ROLE_LABELS } from '@/lib/permissions'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Input } from '@/components/ui/Input'
import type { WorkspaceSummary } from '@/types'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

function accountLabel(w: WorkspaceSummary) {
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

/** The current account, and a menu to switch to another one or create an organization (flow D). */
export function AccountSwitcher({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  const { data: snapshot } = useAuthorization()
  const switchAccount = useSwitchAccount()
  const [open, setOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!snapshot) return null
  const active = snapshot.activeWorkspace
  const isOrg = active.type === 'ORGANIZATION'
  const Icon = isOrg ? Building2 : User
  const pending = snapshot.pendingInvitationCount
  const publicIds = new Set(snapshot.publicWorkspaceIds ?? [])
  const activePublic = publicIds.has(active.id)
  const superAdmin = snapshot.platformRole === 'SUPER_ADMIN'

  function choose(w: WorkspaceSummary) {
    setOpen(false)
    if (w.id === active.id) return
    onNavigate?.()
    switchAccount.mutate(w.id)
  }

  return (
    <div ref={ref} className={`relative border-b border-gray-200 dark:border-gray-800 ${collapsed ? 'px-2 py-2' : 'px-3 py-2'}`}>
      <button
        onClick={() => setOpen(o => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account: ${accountLabel(active)}`}
        title={collapsed ? `${accountLabel(active)} (${isOrg ? 'Organization' : 'Personal'})` : undefined}
        className={`relative flex w-full items-center gap-2.5 rounded-lg py-2 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 ${
          collapsed ? 'justify-center px-2' : 'px-2'
        }`}
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-100 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
          <Icon className="h-4 w-4" />
        </span>
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-1.5">
                <span className="truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{accountLabel(active)}</span>
                {activePublic && <PublicBadge />}
              </span>
              <span className="block truncate text-xs text-gray-500 dark:text-gray-400">
                {isOrg
                  ? `${activePublic ? 'Public organization' : 'Private organization'}${snapshot.role ? ` · ${ROLE_LABELS[snapshot.role]}` : ''}`
                  : 'Individual account'}
              </span>
              {superAdmin && (
                <span className="mt-0.5 flex items-center gap-1 text-[11px] font-medium text-brand-600 dark:text-brand-400">
                  <ShieldCheck className="h-3 w-3" />Super Admin
                </span>
              )}
            </span>
            <ChevronsUpDown className="h-4 w-4 shrink-0 text-gray-400" />
          </>
        )}
        {pending > 0 && (
          <span className={`grid h-4 min-w-4 place-items-center rounded-full bg-brand-500 px-1 text-[10px] font-semibold text-white ${collapsed ? 'absolute right-0.5 top-0.5' : ''}`}>
            {pending}
          </span>
        )}
      </button>

      {open && (
        <div role="menu"
          className={`absolute z-30 w-64 rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl animate-fade-in dark:border-gray-800 dark:bg-gray-900 ${
            collapsed ? 'left-full top-2 ml-2' : 'left-3 right-3 top-full mt-1 w-auto'
          }`}>
          <p className="px-2.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">Accounts</p>
          {snapshot.workspaces.map(w => {
            const WIcon = w.type === 'ORGANIZATION' ? Building2 : User
            return (
              <button key={w.id} role="menuitem" onClick={() => choose(w)} disabled={switchAccount.isPending}
                className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-100 disabled:opacity-50 dark:text-gray-200 dark:hover:bg-gray-800">
                <WIcon className="h-4 w-4 shrink-0 text-gray-400" />
                <span className="min-w-0 flex-1 truncate">{accountLabel(w)}</span>
                {publicIds.has(w.id) && <PublicBadge />}
                {w.type === 'ORGANIZATION' && <Badge>{ROLE_LABELS[w.role]}</Badge>}
                {w.id === active.id && <Check className="h-4 w-4 shrink-0 text-brand-500" />}
              </button>
            )
          })}
          <div className="my-1 h-px bg-gray-100 dark:bg-gray-800" />
          <Link to="/invitations" role="menuitem" onClick={() => { setOpen(false); onNavigate?.() }}
            className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800">
            <Inbox className="h-4 w-4 shrink-0 text-gray-400" />
            Pending requests ({pending})
          </Link>
          <button role="menuitem" onClick={() => { setOpen(false); setCreating(true) }}
            className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800">
            <Plus className="h-4 w-4 shrink-0 text-gray-400" />
            Create organization
          </button>
          {switchAccount.error && <p className="px-2.5 py-1.5 text-xs text-red-500">{errorText(switchAccount.error)}</p>}
        </div>
      )}

      <CreateOrganizationDialog open={creating} onClose={() => setCreating(false)}
        onSwitch={w => { setCreating(false); onNavigate?.(); switchAccount.mutate(w.id) }} />
    </div>
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
