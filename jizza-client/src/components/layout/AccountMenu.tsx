import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronsUpDown, LifeBuoy, LogOut, Settings } from 'lucide-react'
import { useAuthorization } from '@/auth/useAuthorization'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'
import { useSignOut } from '@/hooks/useSignOut'

/**
 * The signed-in person at the bottom of the left menu. It opens a menu with Settings, Help, the theme
 * and Sign out, so configuration pages stay out of the left menu itself.
 */
export function AccountMenu({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  const { data: snapshot } = useAuthorization()
  const signOut = useSignOut()
  const [open, setOpen] = useState(false)
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
  const name = email.split('@')[0] || 'Account'
  const initial = (name[0] ?? 'J').toUpperCase()
  const go = () => { setOpen(false); onNavigate?.() }
  const itemClass = 'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800'

  return (
    <div ref={ref} className="relative">
      {open && (
        <div role="menu" aria-label="Account menu"
          className="absolute bottom-full left-0 z-30 mb-2 w-64 rounded-xl border border-gray-200 bg-white p-1.5 shadow-xl animate-fade-in dark:border-gray-700 dark:bg-gray-900">
          <p className="truncate px-2.5 pb-1.5 pt-1 text-xs text-gray-500 dark:text-gray-400" title={email}>{email}</p>
          <Link role="menuitem" to="/settings" onClick={go} className={itemClass}>
            <Settings className="h-4 w-4 text-gray-400" />Settings
          </Link>
          <Link role="menuitem" to="/help" onClick={go} className={itemClass}>
            <LifeBuoy className="h-4 w-4 text-gray-400" />Get help
          </Link>
          <div className="my-1 h-px bg-gray-100 dark:bg-gray-800" />
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
        title={collapsed ? email : undefined}
        className={`flex w-full items-center gap-2.5 rounded-lg py-1.5 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800 ${
          collapsed ? 'justify-center px-1' : 'px-2'
        } ${open ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full brand-gradient text-sm font-semibold text-white">
          {initial}
        </span>
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-gray-900 dark:text-gray-100">{name}</span>
              <span className="block truncate text-xs text-gray-500 dark:text-gray-400">{email}</span>
            </span>
            <ChevronsUpDown className="h-4 w-4 shrink-0 text-gray-400" />
          </>
        )}
      </button>
    </div>
  )
}
