import { NavLink, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { notificationsApi } from '@/api/notifications'
import { ChevronsLeft, ChevronsRight, X } from 'lucide-react'
import { JizzaLogo } from '@/components/brand/JizzaLogo'
import { useAssistantStore } from '@/jizza/assistantStore'
import { useAuthorization } from '@/auth/useAuthorization'
import { AccountSwitcher } from './AccountSwitcher'
import { AccountMenu } from './AccountMenu'
import { menuFor, pathMatches, type NavHub } from './navigation'

interface SidebarProps {
  collapsed: boolean
  onToggleCollapsed: () => void
  /** Set when rendered as the mobile drawer. */
  onClose?: () => void
}

export function Logo({ collapsed = false }: { collapsed?: boolean }) {
  const thinking = useAssistantStore(s => s.thinking)
  return <JizzaLogo size={34} markOnly={collapsed} state={thinking ? 'thinking' : 'idle'} />
}

export function Sidebar({ collapsed, onToggleCollapsed, onClose }: SidebarProps) {
  const { pathname } = useLocation()
  const { data: snapshot, isError } = useAuthorization()
  // Until the account is known, show nothing rather than the wrong account's menu. If it can't be
  // loaded at all, fall back to the personal menu; the server still checks every request.
  const menu = snapshot || isError ? menuFor(snapshot) : { main: [], platform: null }
  // What is waiting on the user shows on the Notifications item, red when any of it is urgent.
  const { data: inbox } = useQuery({
    queryKey: ['notifications', 'summary'],
    queryFn: () => notificationsApi.summary(),
    refetchInterval: 60_000,
  })

  function entry(hub: NavHub) {
    const Icon = hub.icon
    const isActive = hub.tabs.some(t => pathMatches(t, pathname))
    const to = hub.tabs[0].to
    return (
      <NavLink
        key={hub.id}
        to={to}
        onClick={onClose}
        title={collapsed ? hub.label : undefined}
        aria-current={isActive ? 'page' : undefined}
        className={`group relative flex items-center gap-3 rounded-lg py-2 text-sm font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 ${
          collapsed ? 'justify-center px-2' : 'px-3'
        } ${
          isActive
            ? 'bg-brand-100/60 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300'
            : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
        }`}
      >
        <span
          className={`absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-brand-500 transition-all duration-200 ${
            isActive ? 'opacity-100' : 'opacity-0 group-hover:opacity-30'
          }`}
        />
        <Icon className="h-[18px] w-[18px] shrink-0 transition-transform duration-150 group-hover:scale-110" />
        {!collapsed && <span className="truncate">{hub.label}</span>}
        {to === '/notifications' && inbox && inbox.needsYou > 0 && (
          <span
            aria-label={`${inbox.needsYou} waiting on you`}
            className={`grid min-w-[1.25rem] place-items-center rounded-full px-1.5 text-[11px] font-semibold leading-5 text-white ${
              inbox.urgent > 0 ? 'bg-red-500' : 'bg-brand-500'
            } ${collapsed ? 'absolute right-1 top-0.5 min-w-[1rem] px-1 text-[10px] leading-4' : 'ml-auto'}`}
          >
            {inbox.needsYou > 99 ? '99+' : inbox.needsYou}
          </span>
        )}
      </NavLink>
    )
  }

  return (
    <aside
      className={`sidebar-bg flex h-full flex-col border-r border-gray-200 dark:border-gray-800 transition-[width] duration-300 ease-out ${
        collapsed ? 'w-[4.5rem]' : 'w-64'
      }`}
    >
      <div className={`flex h-16 items-center border-b border-gray-200 dark:border-gray-800 ${collapsed ? 'justify-center px-2' : 'justify-between px-4'}`}>
        <Logo collapsed={collapsed} />
        {onClose && (
          <button
            onClick={onClose}
            aria-label="Close menu"
            className="grid h-8 w-8 place-items-center rounded-lg text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      <AccountSwitcher collapsed={collapsed} onNavigate={onClose} />

      <nav className="flex-1 space-y-5 overflow-y-auto px-3 py-4">
        <div className="space-y-0.5">{menu.main.map(entry)}</div>
        {menu.platform && (
          <div>
            {collapsed ? (
              <div className="mx-auto mb-2 h-px w-6 bg-gray-200 dark:bg-gray-800" />
            ) : (
              <p className="mb-1.5 px-3 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">
                Platform
              </p>
            )}
            <div className="space-y-0.5">{entry(menu.platform)}</div>
          </div>
        )}
      </nav>

      <div className={`flex gap-1 border-t border-gray-200 p-3 dark:border-gray-800 ${collapsed ? 'flex-col items-center' : 'items-center'}`}>
        <div className={collapsed ? '' : 'min-w-0 flex-1'}>
          <AccountMenu collapsed={collapsed} onNavigate={onClose} />
        </div>
        {!onClose && (
          <button
            onClick={onToggleCollapsed}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100"
          >
            {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
          </button>
        )}
      </div>
    </aside>
  )
}
