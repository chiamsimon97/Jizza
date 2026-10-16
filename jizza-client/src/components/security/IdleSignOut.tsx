import { createPortal } from 'react-dom'
import { Clock } from 'lucide-react'
import { useIdleSignOut } from '@/hooks/useIdleSignOut'
import { Button } from '@/components/ui/Button'

/** The warning shown before an idle sign-out. */
export function IdleSignOut() {
  const { secondsLeft, stay } = useIdleSignOut()
  if (secondsLeft == null) return null
  return createPortal(
    <div role="alertdialog" aria-live="assertive"
      className="fixed bottom-4 left-1/2 z-50 flex w-[min(28rem,calc(100%-2rem))] -translate-x-1/2 items-center gap-3 rounded-xl border border-gray-200 bg-white p-4 shadow-2xl dark:border-gray-800 dark:bg-gray-900">
      <Clock className="h-5 w-5 shrink-0 text-amber-500" />
      <p className="flex-1 text-sm text-gray-700 dark:text-gray-300">
        You'll be signed out in {secondsLeft}s because nothing has happened for a while.
      </p>
      <Button size="sm" onClick={stay}>Stay signed in</Button>
    </div>,
    document.body,
  )
}
