import { type FormEvent, useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { authApi } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { RecoveryCodesPanel } from './RecoveryCodesPanel'
import type { MfaSetup } from '@/types'

/**
 * Enrols an authenticator app: scan the QR code, prove a code from it, then save recovery codes.
 * The QR code is drawn in the browser, so the secret is never sent to any other service.
 * Starting setup asks the user to confirm their password first (the step-up dialog).
 */
export function TwoStepSetup({ onFinished, onCancel }: { onFinished: () => void; onCancel?: () => void }) {
  const [setup, setSetup] = useState<MfaSetup | null>(null)
  const [qr, setQr] = useState('')
  const [code, setCode] = useState('')
  const [codes, setCodes] = useState<string[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [showSecret, setShowSecret] = useState(false)

  async function start() {
    setError('')
    setBusy(true)
    try {
      setSetup(await authApi.setupMfa())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start setup')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    if (!setup) return
    QRCode.toDataURL(setup.provisioningUri, { margin: 1, width: 200, errorCorrectionLevel: 'M' })
      .then(setQr)
      .catch(() => setQr(''))
  }, [setup])

  async function confirm(e: FormEvent) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const result = await authApi.confirmMfa(code.replace(/\s/g, ''))
      setCodes(result.codes)
      setSetup(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That code did not work')
    } finally {
      setBusy(false)
    }
  }

  if (codes) return <RecoveryCodesPanel codes={codes} onDone={onFinished} doneLabel="Finish" />

  if (!setup) {
    return (
      <div className="flex flex-col gap-3">
        <p className="text-sm text-gray-600 dark:text-gray-400">
          Use an authenticator app such as Google Authenticator, 1Password, Authy or Aegis. After your
          password, Jizza will ask for the 6-digit code it shows.
        </p>
        {error && <p className="text-sm text-red-500">{error}</p>}
        <div className="flex gap-2">
          <Button type="button" loading={busy} onClick={start}>Set up authenticator</Button>
          {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>}
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={confirm} className="flex flex-col gap-4">
      <ol className="list-decimal space-y-1 pl-5 text-sm text-gray-600 dark:text-gray-400">
        <li>Scan this code with your authenticator app.</li>
        <li>Enter the 6-digit code it shows.</li>
      </ol>
      <div className="flex justify-center">
        {qr
          ? <img src={qr} alt="QR code for your authenticator app" className="h-48 w-48 rounded-lg bg-white p-2" />
          : <div className="h-48 w-48 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" />}
      </div>
      <button type="button" className="text-left text-xs text-brand-600 hover:underline dark:text-brand-400"
        onClick={() => setShowSecret(s => !s)}>
        {showSecret ? 'Hide the setup key' : "Can't scan? Enter a setup key instead"}
      </button>
      {showSecret && (
        <code className="break-all rounded-lg bg-gray-50 p-3 text-center font-mono text-sm tracking-wider text-gray-800 dark:bg-gray-800/60 dark:text-gray-200">
          {setup.secret.replace(/(.{4})/g, '$1 ').trim()}
        </code>
      )}
      <Input label="Code from the app" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9 ]{6,7}"
        maxLength={7} required autoFocus value={code} onChange={e => setCode(e.target.value)} />
      {error && <p className="text-sm text-red-500">{error}</p>}
      <div className="flex gap-2">
        <Button type="submit" loading={busy}>Turn on</Button>
        {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>}
      </div>
    </form>
  )
}
