import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronsUpDown, LifeBuoy, LogOut, Settings, ShieldCheck } from 'lucide-react'
import { useAuthorization } from '@/auth/useAuthorization'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'
import { useSignOut } from '@/hooks/useSignOut'
import { AccountChoices, CreateFamily, CreateOrganization, PublicBadge, accountDescription, accountIcon, accountLabel, type CreateKind } from './AccountSwitcher'

/**
 * The active account at the bottom of the left menu, like Claude's. It opens one menu with the
 * accounts to switch between, Settings, Help, the theme and Sign out, so none of these take room in
 * the left menu itself.
 */
export function AccountMenu({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  const { data: snapshot } = useAuthorization()
  const signOut = useSignOut()
  const [open, setOpen] = useState(false)
  const [creating, setCreating] = useState<CreateKind | null>(null)
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

  const email = snapshot?.email ?? ''
  const active = snapshot?.activeWorkspace
  const Icon = accountIcon(active)
  const label = active ? accountLabel(active) : 'Account'
  const activePublic = !!active && (snapshot?.publicWorkspaceIds ?? []).includes(active.id)
  const superAdmin = snapshot?.platformRole === 'SUPER_ADMIN'
  const pending = snapshot?.pendingInvitationCount ?? 0
  const go = () => { setOpen(false); onNavigate?.() }
  const itemClass = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'

  return (
    <div ref={ref} className="relative">
      {open && (
        <div role="menu" aria-label="Account menu"
          className="absolute bottom-full left-0 z-30 mb-2 max-h-[80vh] w-64 overflow-y-auto rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
          <div className="px-2.5 pb-1 pt-1">
            <p className="truncate text-xs text-gray-500 dark:text-gray-400" title={email}>{email}</p>
            {superAdmin && (
              <p className="mt-0.5 flex items-center gap-1 text-[11px] font-medium text-brand-600 dark:text-brand-400">
                <ShieldCheck className="h-3 w-3" />Super Admin
              </p>
            )}
          </div>
          <AccountChoices inMenu onNavigate={go} onCreate={kind => { setOpen(false); setCreating(kind) }} />
          <div className="my-1 h-px bg-gray-100 dark:bg-gray-800" />
          <Link role="menuitem" to="/settings" onClick={go} className={itemClass}>
            <Settings className="h-4 w-4 text-gray-400" />Settings
          </Link>
          <Link role="menuitem" to="/help" onClick={go} className={itemClass}>
            <LifeBuoy className="h-4 w-4 text-gray-400" />Get help
          </Link>
          <div className="px-1 py-1"><ThemeSwitcher /></div>
          <div className="my-1 h-px bg-gray-100 dark:bg-gray-800" />
          <button role="menuitem" onClick={() => { setOpen(false); void signOut() }}
            className={`${itemClass} hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10 dark:hover:text-red-400`}>
            <LogOut className="h-4 w-4 text-gray-400" />Sign out
          </button>
        </div>
      )}
      <button
        onClick={() => setOpen(o => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Open account menu"
        title={collapsed ? `${label} · ${email}` : undefined}
        className={`relative flex w-full items-center gap-2.5 rounded-lg py-1.5 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 ${
          collapsed ? 'justify-center px-1' : 'px-2'
        } ${open ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-100 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
          <Icon className="h-4 w-4" />
        </span>
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-1.5">
                <span className="truncate text-sm font-semibold text-gray-900 dark:text-gray-100">{label}</span>
                {activePublic && <PublicBadge />}
              </span>
              <span className="block truncate text-xs text-gray-500 dark:text-gray-400">
                {snapshot ? accountDescription(snapshot) : ''}
              </span>
            </span>
            <ChevronsUpDown className="h-4 w-4 shrink-0 text-gray-400" />
          </>
        )}
        {pending > 0 && (
          <span aria-label={`${pending} pending requests`}
            className="absolute left-7 top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-brand-500 px-1 text-[10px] font-semibold text-white">
            {pending}
          </span>
        )}
      </button>
      <CreateOrganization open={creating === 'organization'} onClose={() => setCreating(null)} onNavigate={onNavigate} />
      <CreateFamily open={creating === 'family'} onClose={() => setCreating(null)} onNavigate={onNavigate} />
    </div>
  )
}
