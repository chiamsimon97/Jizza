import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuthorization } from '@/auth/useAuthorization'
import { HUBS, pathMatches, visibleHub, type HubId } from './navigation'

/**
 * The tabs of one grouped menu entry (such as Build: Workflows, Recordings, Forms…), above the page
 * that is open. Each tab is its own address, so the page itself does not change.
 */
export function HubLayout({ hub: id }: { hub: HubId }) {
  const { data: snapshot } = useAuthorization()
  const { pathname } = useLocation()
  const hub = visibleHub(HUBS[id], snapshot)
  if (!hub || hub.tabs.length < 2) return <Outlet />
  const wide = hub.tabs.some(t => t.wide && pathMatches(t, pathname))
  return (
    // The page below keeps its own width; the tabs take the same width and the page's top gap shrinks.
    <div className="[&>.page]:pt-4">
      <div className={`mx-auto w-full px-4 pt-4 sm:px-6 lg:px-8 ${wide ? '' : 'max-w-6xl'}`}>
        <nav aria-label={hub.label}
          className="-mb-2 flex gap-1 overflow-x-auto border-b border-gray-200 dark:border-gray-800">
          {hub.tabs.map(tab => {
            const active = pathMatches(tab, pathname)
            const Icon = tab.icon
            return (
              <NavLink key={tab.to} to={tab.to} aria-current={active ? 'page' : undefined}
                className={`-mb-px flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                  active
                    ? 'border-brand-500 text-brand-700 dark:text-brand-300'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-900 dark:text-gray-400 dark:hover:border-gray-600 dark:hover:text-gray-100'
                }`}>
                <Icon className="h-4 w-4" />{tab.label}
              </NavLink>
            )
          })}
        </nav>
      </div>
      <Outlet />
    </div>
  )
}
