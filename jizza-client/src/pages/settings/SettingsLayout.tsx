import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { useAuthorization } from '@/auth/useAuthorization'
import { pathMatches, settingsFor } from '@/components/layout/navigation'

/**
 * Settings: the configuration pages (theme, accounts, security, AI models, devices, connectors and
 * guardrails) in one place, opened from the account menu at the bottom of the left menu. The list sits on
 * the left on a wide screen and scrolls across the top on a phone. Each page keeps its own address.
 */
export function SettingsLayout() {
  const { data: snapshot } = useAuthorization()
  const { pathname } = useLocation()
  const groups = settingsFor(snapshot)
  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col md:flex-row">
      <aside className="shrink-0 px-4 pt-6 sm:px-6 md:sticky md:top-0 md:w-56 md:self-start md:py-6 md:pl-8 md:pr-0">
        <h1 className="mb-3 flex items-center gap-2 px-1 text-lg font-semibold tracking-tight text-gray-900 dark:text-gray-100">
          <Settings className="h-5 w-5 text-gray-400" />Settings
        </h1>
        <nav aria-label="Settings" className="-mx-1 flex gap-1 overflow-x-auto pb-1 md:mx-0 md:flex-col md:gap-0 md:space-y-4 md:overflow-visible">
          {groups.map(group => (
            <div key={group.title} className="contents md:block">
              <p className="hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500 md:block">
                {group.title}
              </p>
              <div className="contents md:block md:space-y-0.5">
                {group.items.map(item => {
                  const active = pathMatches(item, pathname)
                  const Icon = item.icon
                  return (
                    <NavLink key={item.to} to={item.to} aria-current={active ? 'page' : undefined}
                      className={`flex shrink-0 items-center gap-2.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
                        active
                          ? 'bg-brand-100/60 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300'
                          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100'
                      }`}>
                      <Icon className="h-4 w-4" />{item.label}
                    </NavLink>
                  )
                })}
              </div>
            </div>
          ))}
        </nav>
      </aside>
      {/* Pages are centred on their own; beside this list they line up on the left instead. */}
      <div className="min-w-0 flex-1 [&>.page]:mx-0">
        <Outlet />
      </div>
    </div>
  )
}
