import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'

/** A titled card, as the Portfolio screens use them. */
export function Panel({ title, children, action, className = '' }: { title: ReactNode; children: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <section className={`flex min-w-0 flex-col gap-2 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900 ${className}`}>
      <div className="flex items-start justify-between gap-2">
        <h3 className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  )
}

/** A label on the left and its value on the right. */
export function Row({ label, children, strong = false }: { label: ReactNode; children: ReactNode; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-2 text-sm ${strong ? 'font-semibold' : ''}`}>
      <span className="text-gray-600 dark:text-gray-400">{label}</span>
      <span className="text-right text-gray-900 dark:text-gray-100">{children}</span>
    </div>
  )
}

/** A thin progress bar; the figure itself is always written next to it. */
export function Meter({ pct, label, className = 'bg-brand-500' }: { pct: number; label: string; className?: string }) {
  const w = Math.max(0, Math.min(100, pct))
  return (
    <div className="h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800" role="progressbar" aria-label={label}
      aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(w)}>
      <div className={`h-full rounded-full ${className}`} style={{ width: `${w}%` }} />
    </div>
  )
}

const PILL = {
  neutral: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300',
  info: 'bg-cyan-100 text-cyan-800 dark:bg-cyan-900/30 dark:text-cyan-300',
  warn: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300',
  bad: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300',
  ok: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
  brand: 'bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200',
}

export function Pill({ tone = 'neutral', children, title }: { tone?: keyof typeof PILL; children: ReactNode; title?: string }) {
  return <span title={title} className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${PILL[tone]}`}>{children}</span>
}

/** A failed load, with a way to try again. */
export function LoadFailed({ text, onRetry }: { text: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-2 p-6 text-center">
      <p className="text-sm text-red-700 dark:text-red-300">{text}</p>
      <Button size="sm" variant="secondary" onClick={onRetry}>Try again</Button>
    </div>
  )
}
