interface BadgeProps {
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info'
  children: React.ReactNode
  className?: string
}

const variants = {
  default: 'bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300 ring-gray-500/20',
  success: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 ring-green-500/20',
  warning: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400 ring-yellow-500/20',
  danger:  'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 ring-red-500/20',
  info:    'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 ring-blue-500/20',
}

export function Badge({ variant = 'default', className = '', children }: BadgeProps) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${variants[variant]} ${className}`}>
      {children}
    </span>
  )
}
