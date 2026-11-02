import { useCallback, useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { QrCode, RefreshCw, Smartphone, CheckCircle2, XCircle } from 'lucide-react'
import { smartSignInApi, smartSignInUrl, type SmartSignInStarted, type SmartSignInState } from '@/api/smartSignIn'
import type { LoginResult } from '@/types'

const POLL_MS = 2000

type View =
  | { kind: 'hidden' }
  | { kind: 'loading' }
  | { kind: 'showing'; attempt: SmartSignInStarted; qr: string; state: SmartSignInState }
  | { kind: 'signing-in' }
  | { kind: 'error'; message: string }

/**
 * "Smart sign-in with the Jizza app": a blurred QR that turns into a real one on click. A phone
 * already signed in to Jizza scans it and approves, and this browser signs in by itself.
 *
 * Nothing is asked of the server until the person clicks, and the code is cancelled when they
 * leave the page, so an idle login page holds no sign-in codes open.
 */
export function SmartSignInPanel({ onSignedIn }: { onSignedIn: (result: LoginResult) => void }) {
  const [view, setView] = useState<View>({ kind: 'hidden' })
  const [decoy, setDecoy] = useState<string>()
  const [now, setNow] = useState(() => Date.now())
  const attemptRef = useRef<SmartSignInStarted | null>(null)
  const onSignedInRef = useRef(onSignedIn)
  onSignedInRef.current = onSignedIn

  // A stand-in pattern under the blur, so the hidden state looks like a QR without being one.
  useEffect(() => {
    QRCode.toDataURL('jizza-smart-sign-in', { margin: 1, width: 176 }).then(setDecoy).catch(() => {})
  }, [])

  const cancelCurrent = useCallback(() => {
    const current = attemptRef.current
    attemptRef.current = null
    if (current) void smartSignInApi.cancel(current.id, current.pollToken).catch(() => {})
  }, [])

  useEffect(() => cancelCurrent, [cancelCurrent])

  const show = useCallback(async () => {
    cancelCurrent()
    setView({ kind: 'loading' })
    try {
      const attempt = await smartSignInApi.start()
      const qr = await QRCode.toDataURL(smartSignInUrl(attempt.code), {
        margin: 1, width: 176, errorCorrectionLevel: 'M',
      })
      attemptRef.current = attempt
      setView({ kind: 'showing', attempt, qr, state: 'PENDING' })
    } catch (err) {
      setView({ kind: 'error', message: err instanceof Error ? err.message : 'Could not get a sign-in code' })
    }
  }, [cancelCurrent])

  // Poll while a code is on screen and still open.
  const attemptId = view.kind === 'showing' ? view.attempt.id : null
  const open = view.kind === 'showing' && (view.state === 'PENDING' || view.state === 'SCANNED')
  useEffect(() => {
    if (!attemptId || !open) return
    let stopped = false
    const timer = window.setInterval(async () => {
      const attempt = attemptRef.current
      if (!attempt || attempt.id !== attemptId || stopped) return
      setNow(Date.now())
      try {
        const { state } = await smartSignInApi.status(attempt.id, attempt.pollToken)
        if (stopped) return
        if (state === 'APPROVED') {
          stopped = true
          setView({ kind: 'signing-in' })
          const result = await smartSignInApi.complete(attempt.id, attempt.pollToken)
          attemptRef.current = null
          onSignedInRef.current(result)
          return
        }
        setView(v => (v.kind === 'showing' && v.attempt.id === attemptId && v.state !== state ? { ...v, state } : v))
        if (state === 'DENIED' || state === 'EXPIRED') attemptRef.current = null
      } catch (err) {
        if (stopped) return
        stopped = true
        attemptRef.current = null
        setView({ kind: 'error', message: err instanceof Error ? err.message : 'Smart sign-in stopped' })
      }
    }, POLL_MS)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [attemptId, open])

  const secondsLeft = view.kind === 'showing'
    ? Math.max(0, Math.round((new Date(view.attempt.expiresAt).getTime() - now) / 1000))
    : 0

  return (
    <section aria-labelledby="smart-sign-in-title" className="rounded-xl border border-gray-200 p-4 dark:border-gray-800">
      <div className="mb-3 flex items-center gap-2">
        <Smartphone className="h-4 w-4 text-brand-500" aria-hidden />
        <h2 id="smart-sign-in-title" className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          Smart sign-in with the Jizza app
        </h2>
      </div>

      <div className="flex items-center gap-4">
        <div className="relative h-36 w-36 shrink-0 overflow-hidden rounded-lg bg-white p-1.5 ring-1 ring-gray-200 dark:ring-gray-700">
          {view.kind === 'showing' && (view.state === 'PENDING' || view.state === 'SCANNED') ? (
            <img
              src={view.qr}
              alt="QR code for smart sign-in. Scan it with a phone signed in to Jizza."
              className={`h-full w-full ${view.state === 'SCANNED' ? 'opacity-30' : ''}`}
            />
          ) : (
            <>
              {decoy && <img src={decoy} alt="" aria-hidden className="h-full w-full scale-105 blur-[6px]" />}
              <Overlay view={view} onShow={show} />
            </>
          )}
          {view.kind === 'showing' && view.state === 'SCANNED' && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-1 bg-white/85 px-2 text-center text-xs font-medium text-gray-800">
              <Smartphone className="h-6 w-6 text-brand-600" aria-hidden />
              Approve on your phone
            </div>
          )}
        </div>

        <div className="min-w-0 text-xs leading-relaxed text-gray-500 dark:text-gray-400" aria-live="polite">
          {describe(view, secondsLeft)}
        </div>
      </div>
    </section>
  )
}

function Overlay({ view, onShow }: { view: View; onShow: () => void }) {
  const base = 'absolute inset-0 flex flex-col items-center justify-center gap-1.5 bg-white/40 text-xs font-medium text-gray-800 transition-colors hover:bg-white/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500'
  if (view.kind === 'loading' || view.kind === 'signing-in') {
    return (
      <div className={base}>
        <RefreshCw className="h-5 w-5 animate-spin text-brand-600" aria-hidden />
        {view.kind === 'loading' ? 'Getting a code…' : 'Signing in…'}
      </div>
    )
  }
  if (view.kind === 'showing' && view.state === 'DENIED') {
    return (
      <button type="button" className={base} onClick={onShow}>
        <XCircle className="h-6 w-6 text-red-500" aria-hidden />
        Try again
      </button>
    )
  }
  if (view.kind === 'showing' && view.state === 'APPROVED') {
    return (
      <div className={base}>
        <CheckCircle2 className="h-6 w-6 text-green-600" aria-hidden />
        Approved
      </div>
    )
  }
  const label = view.kind === 'hidden' ? 'Show QR code' : 'Get a new code'
  const Icon = view.kind === 'hidden' ? QrCode : RefreshCw
  return (
    <button type="button" className={base} onClick={onShow}>
      <span className="rounded-full bg-brand-500 p-2 text-white shadow"><Icon className="h-4 w-4" aria-hidden /></span>
      {label}
    </button>
  )
}

function describe(view: View, secondsLeft: number) {
  switch (view.kind) {
    case 'hidden':
    case 'loading':
      return 'Skip the password. Click the QR, then scan it with a phone that is signed in to Jizza.'
    case 'signing-in':
      return 'Approved on your phone. Signing you in…'
    case 'error':
      return <span className="text-red-500">{view.message}</span>
    case 'showing':
      switch (view.state) {
        case 'PENDING':
          return (
            <>
              Open your phone's camera, or the Jizza Agent app, and scan this code.
              <span className="mt-1 block text-gray-400">Expires in {formatSeconds(secondsLeft)}</span>
            </>
          )
        case 'SCANNED':
          return 'Scanned. Check that the browser shown on your phone is this one, then tap Approve.'
        case 'DENIED':
          return 'The sign-in was denied on your phone.'
        case 'EXPIRED':
          return 'This code expired. Get a new one to try again.'
        case 'APPROVED':
          return 'Approved. Signing you in…'
      }
  }
}

function formatSeconds(total: number) {
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}
