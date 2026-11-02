import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Monitor, ShieldAlert, CheckCircle2, XCircle } from 'lucide-react'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { Button } from '@/components/ui/Button'
import { ApiError } from '@/lib/api'
import { useAuthStore } from '@/store/authStore'
import { smartSignInApi, type SmartSignInDetails } from '@/api/smartSignIn'

/** Where a scanned code waits while the phone signs in first. Cleared once used. */
const PENDING_KEY = 'jizza-smart-sign-in-code'

type View =
  | { kind: 'no-code' }
  | { kind: 'needs-sign-in' }
  | { kind: 'loading' }
  | { kind: 'review'; details: SmartSignInDetails }
  | { kind: 'done'; approved: boolean }
  | { kind: 'error'; message: string }

/**
 * The page a phone opens when its camera scans the login page's QR code. Shows which browser is
 * asking, then approves or denies. The code arrives after the `#`, so it is read here and never
 * sent in a page request; it is wiped from the address bar straight away.
 */
export function SmartSignInApprovePage() {
  const navigate = useNavigate()
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const [code] = useState(readCode)
  const [view, setView] = useState<View>(() =>
    !code ? { kind: 'no-code' } : isAuthenticated ? { kind: 'loading' } : { kind: 'needs-sign-in' })
  const [busy, setBusy] = useState<'approve' | 'deny' | null>(null)

  useEffect(() => {
    if (!code || !isAuthenticated) return
    let live = true
    smartSignInApi.scan(code)
      .then(details => { if (live) setView({ kind: 'review', details }) })
      .catch(err => {
        if (!live) return
        if (err instanceof ApiError && err.status === 401) setView({ kind: 'needs-sign-in' })
        else { forget(); setView({ kind: 'error', message: messageOf(err) }) }
      })
    return () => { live = false }
  }, [code, isAuthenticated])

  async function decide(approve: boolean) {
    if (!code) return
    setBusy(approve ? 'approve' : 'deny')
    try {
      await smartSignInApi.decide(code, approve)
      forget()
      setView({ kind: 'done', approved: approve })
    } catch (err) {
      setView({ kind: 'error', message: messageOf(err) })
    } finally {
      setBusy(null)
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-6 text-center text-xl font-bold tracking-tight text-gray-900 dark:text-gray-100">
        Smart sign-in
      </h1>

      {view.kind === 'no-code' && (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          Scan the QR code on the Jizza login page to sign that browser in from here.
        </p>
      )}

      {view.kind === 'needs-sign-in' && (
        <div className="flex flex-col gap-4 text-sm text-gray-600 dark:text-gray-300">
          <p>Sign in to Jizza on this phone first. Then this page comes back so you can approve the other browser.</p>
          <Button onClick={() => navigate('/login', { state: { from: { pathname: '/smart-sign-in' } } })}>
            Sign in on this phone
          </Button>
        </div>
      )}

      {view.kind === 'loading' && <p className="text-center text-sm text-gray-500">Checking the code…</p>}

      {view.kind === 'review' && (
        <div className="flex flex-col gap-5">
          <p className="text-sm text-gray-600 dark:text-gray-300">A browser wants to sign in to your Jizza account.</p>
          <dl className="grid grid-cols-[auto,1fr] gap-x-4 gap-y-2 rounded-xl border border-gray-200 p-4 text-sm dark:border-gray-800">
            <dt className="flex items-center gap-1.5 text-gray-500"><Monitor className="h-4 w-4" aria-hidden />Browser</dt>
            <dd className="font-medium text-gray-900 dark:text-gray-100">{view.details.browser}</dd>
            <dt className="text-gray-500">IP address</dt>
            <dd className="font-medium text-gray-900 dark:text-gray-100">
              {view.details.ip ?? 'Unknown'}
              {view.details.sameNetwork && <span className="ml-2 text-xs font-normal text-green-600">same network as this phone</span>}
            </dd>
            <dt className="text-gray-500">Requested</dt>
            <dd className="font-medium text-gray-900 dark:text-gray-100">{new Date(view.details.createdAt).toLocaleTimeString()}</dd>
          </dl>
          <p className="flex gap-2 rounded-lg bg-amber-50 p-3 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-300">
            <ShieldAlert className="h-4 w-4 shrink-0" aria-hidden />
            Only approve if you are signing in on a screen in front of you right now. Never approve a code someone sent you.
          </p>
          <div className="flex gap-3">
            <Button variant="secondary" className="flex-1" loading={busy === 'deny'} disabled={busy !== null} onClick={() => decide(false)}>
              Deny
            </Button>
            <Button className="flex-1" loading={busy === 'approve'} disabled={busy !== null} onClick={() => decide(true)}>
              Approve
            </Button>
          </div>
        </div>
      )}

      {view.kind === 'done' && (
        <div className="flex flex-col items-center gap-3 text-center text-sm text-gray-600 dark:text-gray-300">
          {view.approved
            ? <><CheckCircle2 className="h-10 w-10 text-green-600" aria-hidden />Approved. The other browser is signing in now.</>
            : <><XCircle className="h-10 w-10 text-red-500" aria-hidden />Denied. That browser was not signed in.</>}
          <Button variant="ghost" onClick={() => navigate('/chat', { replace: true })}>Go to Jizza</Button>
        </div>
      )}

      {view.kind === 'error' && (
        <div className="flex flex-col gap-4 text-sm">
          <p className="text-red-500">{view.message}</p>
          {isAuthenticated && <Button variant="ghost" onClick={() => navigate('/chat', { replace: true })}>Go to Jizza</Button>}
        </div>
      )}
    </AuthLayout>
  )
}

/** Takes the code from the address bar's fragment (and wipes it), or from before a sign-in detour. */
function readCode(): string | null {
  const fromHash = window.location.hash.replace(/^#/, '').trim()
  if (fromHash) {
    try { sessionStorage.setItem(PENDING_KEY, fromHash) } catch { /* storage may be unavailable */ }
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
    return fromHash
  }
  try { return sessionStorage.getItem(PENDING_KEY) } catch { return null }
}

function forget() {
  try { sessionStorage.removeItem(PENDING_KEY) } catch { /* storage may be unavailable */ }
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : 'Something went wrong'
}
