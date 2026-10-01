import { NavLink } from 'react-router-dom'
import {
  MessageSquare, History, Bell, Bot, ListTodo,
  Plug, Workflow, GitBranch, Shield, Activity, BarChart2, Settings,
} from 'lucide-react'

const nav = [
  { to: '/chat',          icon: MessageSquare, label: 'Chat' },
  { to: '/conversations', icon: History,       label: 'History' },
  { to: '/notifications', icon: Bell,          label: 'Notifications' },
  { to: '/agents',        icon: Bot,           label: 'Agents' },
  { to: '/tasks',         icon: ListTodo,      label: 'Tasks' },
  { to: '/connections',   icon: Plug,          label: 'Connections' },
  { to: '/workflows',     icon: Workflow,      label: 'Workflows' },
  { to: '/procedures',    icon: GitBranch,     label: 'Procedures' },
  { to: '/autonomy',      icon: Shield,        label: 'Autonomy' },
  { to: '/activity',      icon: Activity,      label: 'Activity' },
  { to: '/usage',         icon: BarChart2,     label: 'Usage' },
  { to: '/settings',      icon: Settings,      label: 'Settings' },
]

export function Sidebar() {
  return (
    <aside className="flex h-full w-56 flex-col border-r border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">
      <div className="flex h-14 items-center px-4 border-b border-gray-200 dark:border-gray-700">
        <span className="text-lg font-bold text-brand-500">Jizza</span>
      </div>
      <nav className="flex-1 overflow-y-auto py-2">
        {nav.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `flex items-center gap-3 px-4 py-2.5 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-brand-50 dark:bg-brand-900/20 text-brand-600 dark:text-brand-400'
                  : 'text-gray-600 dark:text-gray-400 hover:bg-gray-50 dark:hover:bg-gray-800 hover:text-gray-900 dark:hover:text-gray-100'
              }`
            }
          >
            <Icon className="h-4 w-4 shrink-0" />
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
