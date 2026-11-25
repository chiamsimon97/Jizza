import { CheckCircle2, HelpCircle, TriangleAlert, XCircle } from 'lucide-react'
import type { CheckStatus } from '@/types/finance'

export const STATUS_WORDS: Record<CheckStatus, string> = {
  green: 'On track', amber: 'Close', red: 'Fix first', unknown: 'Not enough data',
}

const STYLES: Record<CheckStatus, string> = {
  green: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
  amber: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300',
  red: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300',
  unknown: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300',
}

const ICONS = { green: CheckCircle2, amber: TriangleAlert, red: XCircle, unknown: HelpCircle }

/** A check's status as an icon and a word, never colour alone. */
export function StatusBadge({ status, label }: { status: CheckStatus; label?: string }) {
  const Icon = ICONS[status]
  return (
    <span className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${STYLES[status]}`}>
      <Icon className="h-3 w-3" aria-hidden />{label ?? STATUS_WORDS[status]}
    </span>
  )
}
