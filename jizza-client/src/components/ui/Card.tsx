import { type HTMLAttributes } from 'react'

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  padding?: 'none' | 'sm' | 'md' | 'lg'
}

const paddings = { none: '', sm: 'p-3', md: 'p-4', lg: 'p-6' }

export function Card({ padding = 'md', className = '', children, ...props }: CardProps) {
  return (
    <div
      className={`rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm ${paddings[padding]} ${className}`}
      {...props}
    >
      {children}
    </div>
  )
}
