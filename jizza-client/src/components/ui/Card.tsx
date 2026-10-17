import { type HTMLAttributes } from 'react'

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  padding?: 'none' | 'sm' | 'md' | 'lg'
  /** Adds hover lift and a brand-tinted border, for cards that can be acted on. */
  interactive?: boolean
}

const paddings = { none: '', sm: 'p-3', md: 'p-4', lg: 'p-6' }

export function Card({ padding = 'md', interactive = false, className = '', children, ...props }: CardProps) {
  return (
    <div
      className={`rounded-xl border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 shadow-sm transition-all duration-200 ${
        interactive
          ? 'hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md dark:hover:border-brand-700'
          : 'hover:border-gray-300 dark:hover:border-gray-700'
      } ${paddings[padding]} ${className}`}
      {...props}
    >
      {children}
    </div>
  )
}
