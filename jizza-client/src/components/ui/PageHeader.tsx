import { type ReactNode } from 'react'
import { type LucideIcon } from 'lucide-react'

interface PageHeaderProps {
  icon: LucideIcon
  title: string
  description?: string
  actions?: ReactNode
}

export function PageHeader({ icon: Icon, title, description, actions }: PageHeaderProps) {
  return (
    <div className="mb-6 flex flex-wrap items-center gap-3">
      <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl brand-gradient text-white shadow-glow">
        <Icon className="h-5 w-5" />
      </div>
      <div className="min-w-0 flex-1">
        <h1 className="text-xl font-semibold tracking-tight text-gray-900 dark:text-gray-100">{title}</h1>
        {description && <p className="text-sm text-gray-500 dark:text-gray-400">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
