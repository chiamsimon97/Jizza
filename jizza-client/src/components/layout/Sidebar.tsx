import { NavLink, useNavigate } from 'react-router-dom'
import {
  MessageSquare, Bell, Bot, Brain, ListTodo,
  Plug, Workflow, GitBranch, Shield, Activity, BarChart2,
  ChevronsLeft, ChevronsRight, LogOut, X, ShieldCheck, Users, type LucideIcon,
} from 'lucide-react'
import { authApi } from '@/api/auth'
import { endLocalSession } from '@/lib/session'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'
import { JizzaLogo } from '@/components/brand/JizzaLogo'
import { useAssistantStore } from '@/jizza/assistantStore'
import { useAuthorization } from '@/auth/useAuthorization'
import { PERMISSIONS } from '@/lib/permissions'
import type { AuthorizationSnapshot } from '@/types'
import { AccountSwitcher } from './AccountSwitcher'

interface NavItem { to: string; icon: LucideIcon; label: string; permission?: string }
interface NavSection { title: string; items: NavItem[] }

/** The Individual account's sections. Connectors appear only with `connector_view`. */
const personalSections: NavSection[] = [
  {
    title: 'Workspace',
    items: [
      { to: '/chat',          icon: MessageSquare, label: 'Chat' },
      { to: '/notifications', icon: Bell,          label: 'Notifications' },
    ],
  },
  {
    title: 'Automation',
    items: [
      { to: '/agents',      icon: Bot,       label: 'Agents' },
      { to: '/memory',      icon: Brain,     label: 'Memory' },
      { to: '/tasks',       icon: ListTodo,  label: 'Tasks' },
      { to: '/workflows',   icon: Workflow,  label: 'Workflows' },
      { to: '/procedures',  icon: GitBranch, label: 'Procedures' },
      { to: '/connectors',  icon: Plug,      label: 'Connectors', permission: PERMISSIONS.CONNECTOR_VIEW },
    ],
  },
  {
    title: 'Governance',
    items: [
      { to: '/autonomy', icon: Shield,    label: 'Autonomy' },
      { to: '/activity', icon: Activity,  label: 'Activity' },
      { to: '/usage',    icon: BarChart2, label: 'Usage' },
      { to: '/settings/security', icon: ShieldCheck, label: 'Security' },
    ],
  },
]

/** An Organization account shows only what is workspace-scoped, plus the person's own settings. */
const organizationSections: NavSection[] = [
  {
    title: 'Organization',
    items: [
      { to: '/members',    icon: Users, label: 'Members',    permission: PERMISSIONS.MEMBERS_VIEW },
      { to: '/connectors', icon: Plug,  label: 'Connectors', permission: PERMISSIONS.CONNECTOR_VIEW },
    ],
  },
  {
    title: 'Account',
    items: [
      { to: '/notifications',     icon: Bell,        label: 'Notifications' },
      { to: '/settings/security', icon: ShieldCheck, label: 'Security' },
    ],
  },
]

/** The sidebar's sections for the active account, with items the role can't open left out. */
export function sectionsFor(snapshot: AuthorizationSnapshot): NavSection[] {
  const granted = new Set(snapshot.permissions)
  const base = snapshot.activeWorkspace.type === 'ORGANIZATION' ? organizationSections : personalSections
  return base
    .map(section => ({ ...section, items: section.items.filter(i => !i.permission || granted.has(i.permission)) }))
    .filter(section => section.items.length > 0)
}

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
  const navigate = useNavigate()
  const { data: snapshot, isError } = useAuthorization()
  // Until the account is known, show nothing rather than the wrong account's sections. If it can't
  // be loaded at all, fall back to the personal sections; the server still checks every request.
  const sections = snapshot ? sectionsFor(snapshot) : isError ? personalSections : []
  async function signOut() {
    try { await authApi.logout() } catch { /* session may already be gone */ }
    endLocalSession()
    navigate('/login')
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
        {sections.map(section => (
          <div key={section.title}>
            {collapsed ? (
              <div className="mx-auto mb-2 h-px w-6 bg-gray-200 dark:bg-gray-800" />
            ) : (
              <p className="mb-1.5 px-3 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">
                {section.title}
              </p>
            )}
            <div className="space-y-0.5">
              {section.items.map(({ to, icon: Icon, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  onClick={onClose}
                  title={collapsed ? label : undefined}
                  className={({ isActive }) =>
                    `group relative flex items-center gap-3 rounded-lg py-2 text-sm font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 ${
                      collapsed ? 'justify-center px-2' : 'px-3'
                    } ${
                      isActive
                        ? 'bg-brand-100/60 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300'
                        : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
                    }`
                  }
                >
                  {({ isActive }) => (
                    <>
                      <span
                        className={`absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-brand-500 transition-all duration-200 ${
                          isActive ? 'opacity-100' : 'opacity-0 group-hover:opacity-30'
                        }`}
                      />
                      <Icon className="h-[18px] w-[18px] shrink-0 transition-transform duration-150 group-hover:scale-110" />
                      {!collapsed && <span className="truncate">{label}</span>}
                    </>
                  )}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <div className="space-y-2 border-t border-gray-200 p-3 dark:border-gray-800">
        {collapsed ? (
          <ThemeSwitcher compact className="mx-auto" />
        ) : (
          <ThemeSwitcher />
        )}
        <div className={`flex gap-1 ${collapsed ? 'flex-col items-center' : ''}`}>
          <button
            onClick={signOut}
            title="Sign out"
            className={`flex items-center gap-2 rounded-lg py-2 text-sm text-gray-500 transition-colors hover:bg-red-50 hover:text-red-600 dark:text-gray-400 dark:hover:bg-red-500/10 dark:hover:text-red-400 ${
              collapsed ? 'justify-center px-2' : 'flex-1 px-3'
            }`}
          >
            <LogOut className="h-4 w-4" />
            {!collapsed && 'Sign out'}
          </button>
          {!onClose && (
            <button
              onClick={onToggleCollapsed}
              title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              className="grid h-9 w-9 place-items-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100"
            >
              {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
            </button>
          )}
        </div>
      </div>
    </aside>
  )
}
