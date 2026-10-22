import { type ReactNode } from 'react'

interface FilterChipProps {
  active: boolean
  onClick: () => void
  children: ReactNode
  count?: number
  /** A CSS colour for the dot before the label. */
  color?: string
  title?: string
}

/** A toggle in a filter row: the label, an optional colour dot and how many it would show. */
export function FilterChip({ active, onClick, children, count, color, title }: FilterChipProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-pressed={active}
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-all ${
        active
          ? 'border-brand-500 bg-brand-50 text-brand-700 shadow-sm dark:border-brand-400 dark:bg-brand-900/30 dark:text-brand-200'
          : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300 hover:text-gray-900 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300 dark:hover:border-gray-600'
      }`}
    >
      {color && <span className="h-2 w-2 rounded-full" style={{ backgroundColor: color }} />}
      {children}
      {count != null && (
        <span className={`tabular-nums ${active ? 'text-brand-600 dark:text-brand-300' : 'text-gray-400'}`}>{count}</span>
      )}
    </button>
  )
}

/** Adds or removes {@code value} from a list, for multi-select chips. */
export function toggled<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter(v => v !== value) : [...list, value]
}
