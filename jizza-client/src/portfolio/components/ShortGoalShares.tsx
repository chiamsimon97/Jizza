import { AlertTriangle } from 'lucide-react'
import { ApiError } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'

/** Whether a save was refused because it puts direct shares in a goal under three years away. */
export function isShortGoalWarning(e: unknown): e is ApiError {
  return e instanceof ApiError && e.status === 409 && (e.problem.type ?? '').endsWith('finance-short-goal-shares')
}

/**
 * The short-goal warning (spec D15): the server's words, then the user's choice. Keeping the
 * shares saves the change and marks them "Kept by your choice"; nothing is sold either way.
 */
export function ShortGoalSharesDialog({ error, busy, onKeep, onCancel }: {
  error: ApiError | null
  busy?: boolean
  onKeep: () => void
  onCancel: () => void
}) {
  return (
    <Dialog open={!!error} title="Shares in a short goal" onClose={onCancel}>
      <div className="flex flex-col gap-3">
        <p className="flex items-start gap-2 text-sm text-gray-700 dark:text-gray-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
          <span>{error?.message}</span>
        </p>
        <p className="text-sm text-gray-600 dark:text-gray-400">
          If you keep them, Kai shows them as kept by your choice and still flags them when the goal is near.
        </p>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="secondary" onClick={onCancel}>Change the split</Button>
          <Button loading={busy} onClick={onKeep}>Keep the shares</Button>
        </div>
      </div>
    </Dialog>
  )
}
