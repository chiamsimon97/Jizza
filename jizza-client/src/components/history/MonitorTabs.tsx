import { NavLink } from 'react-router-dom'
import { Activity, BarChart2, History } from 'lucide-react'

const TABS = [
  { to: '/activity', label: 'Activity', icon: Activity },
  { to: '/runs', label: 'Run history', icon: History },
  { to: '/usage', label: 'Usage', icon: BarChart2 },
]

/** The three monitoring views share one strip, so moving between them keeps your place. */
export function MonitorTabs() {
  return (
    <nav aria-label="Monitoring" className="mb-5 inline-flex rounded-xl border border-gray-200 bg-gray-50 p-1 dark:border-gray-800 dark:bg-gray-900">
      {TABS.map(({ to, label, icon: Icon }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) =>
            `flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-all ${
              isActive
                ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-800 dark:text-gray-100'
                : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'
            }`}
        >
          <Icon className="h-4 w-4" />
          {label}
        </NavLink>
      ))}
    </nav>
  )
}
