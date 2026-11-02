import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { ShieldCheck } from 'lucide-react'
import { setStepUpHandler } from '@/lib/api'
import { authApi } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'

type AutoApproveMinutes = 15 | 60 | 180

const AUTO_APPROVE: { minutes: AutoApproveMinutes; label: string }[] = [
  { minutes: 15, label: '15 min' },
  { minutes: 60, label: '1 hr' },
  { minutes: 180, label: '3 hr' },
]

/**
 * Asks for the password again when a sensitive action needs a fresh re-authentication, such as
 * publishing a connector or giving an agent a new action. The request that needed it is retried.
 *
 * "Auto approve" skips the question for 15 minutes, 1 hour or 3 hours on this session; without it
 * the confirmation covers only the action that asked for it.
 */
export function StepUpDialog() {
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [autoApprove, setAutoApprove] = useState(false)
  const [minutes, setMinutes] = useState<AutoApproveMinutes>(15)
  const resolver = useRef<((ok: boolean) => void) | null>(null)

  useEffect(() => {
    setStepUpHandler(() => new Promise<boolean>(resolve => {
      resolver.current = resolve
      setPassword('')
      setError('')
      setAutoApprove(false)
      setMinutes(15)
      setOpen(true)
    }))
    return () => setStepUpHandler(null)
  }, [])

  function finish(ok: boolean) {
    setOpen(false)
    resolver.current?.(ok)
    resolver.current = null
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await authApi.stepUp(password, autoApprove ? minutes : 0)
      finish(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That did not work')
    } finally {
      setBusy(false)
    }
  }

  if (!open) return null
  return createPortal(
    <div className="fixed inset-0 z-50 grid place-items-center px-4">
      <div className="absolute inset-0 bg-gray-950/50 backdrop-blur-[2px]" onClick={() => finish(false)} />
      <form onSubmit={submit} role="dialog" aria-label="Confirm it's you"
        className="relative w-full max-w-sm rounded-xl bg-white p-6 shadow-2xl dark:bg-gray-900">
        <div className="mb-4 flex items-center gap-3">
          <div className="grid h-9 w-9 place-items-center rounded-lg brand-gradient text-white"><ShieldCheck className="h-5 w-5" /></div>
          <div>
            <h2 className="font-semibold text-gray-900 dark:text-gray-100">Confirm it's you</h2>
            <p className="text-sm text-gray-500 dark:text-gray-400">This is a sensitive action, so enter your password again to carry on.</p>
          </div>
        </div>
        <div className="flex flex-col gap-3">
          <Input label="Password" type="password" autoFocus required autoComplete="current-password"
            value={password} onChange={e => setPassword(e.target.value)} />
          <div className="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
            <label className="flex cursor-pointer items-center gap-2 text-sm font-medium text-gray-800 dark:text-gray-200">
              <input type="checkbox" checked={autoApprove} onChange={e => setAutoApprove(e.target.checked)}
                className="h-4 w-4 rounded border-gray-300 text-brand-600 focus:ring-brand-500" />
              Auto approve
            </label>
            {autoApprove && (
              <div className="mt-3">
                <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">Don't ask for my password again for:</p>
                <div role="radiogroup" aria-label="Auto approve for" className="grid grid-cols-3 gap-2">
                  {AUTO_APPROVE.map(o => (
                    <button key={o.minutes} type="button" role="radio" aria-checked={minutes === o.minutes}
                      onClick={() => setMinutes(o.minutes)}
                      className={`rounded-md border px-2 py-1.5 text-sm transition-colors ${minutes === o.minutes
                        ? 'border-brand-500 bg-brand-50 font-medium text-brand-700 dark:bg-brand-500/10 dark:text-brand-300'
                        : 'border-gray-200 text-gray-600 hover:border-gray-300 dark:border-gray-700 dark:text-gray-300'}`}>
                      {o.label}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
          {error && <p className="text-sm text-red-500">{error}</p>}
          <div className="mt-1 flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => finish(false)}>Cancel</Button>
            <Button type="submit" loading={busy}>Confirm</Button>
          </div>
        </div>
      </form>
    </div>,
    document.body,
  )
}
