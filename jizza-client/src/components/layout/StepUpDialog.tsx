import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { ShieldCheck } from 'lucide-react'
import { setStepUpHandler } from '@/lib/api'
import { authApi } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'

/**
 * Asks for the password again when a sensitive action needs a fresh re-authentication, such as
 * publishing a connector or giving an agent a new action. The request that needed it is retried.
 */
export function StepUpDialog() {
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const resolver = useRef<((ok: boolean) => void) | null>(null)

  useEffect(() => {
    setStepUpHandler(() => new Promise<boolean>(resolve => {
      resolver.current = resolve
      setPassword('')
      setError('')
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
      await authApi.stepUp(password)
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
