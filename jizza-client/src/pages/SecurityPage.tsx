import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, KeyRound, LogOut, Monitor, ShieldCheck, ShieldAlert, Trash2 } from 'lucide-react'
import { authApi } from '@/api/auth'
import { dataApi, DELETE_CONFIRMATION } from '@/api/data'
import { endLocalSession } from '@/lib/session'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { TwoStepSetup } from '@/components/security/TwoStepSetup'
import { RecoveryCodesPanel } from '@/components/security/RecoveryCodesPanel'

function errorText(err: unknown) {
  return err instanceof Error ? err.message : 'That did not work'
}

/** Account protection: two-step sign-in, recovery codes, signed-in devices and the password. */
export function SecurityPage() {
  return (
    <div className="page">
      <PageHeader icon={ShieldCheck} title="Security"
        description="Two-step sign-in, recovery codes, signed-in devices, your password and your data." />
      <div className="grid max-w-3xl gap-4">
        <TwoStepCard />
        <SessionsCard />
        <PasswordCard />
        <DataCard />
      </div>
    </div>
  )
}

function TwoStepCard() {
  const qc = useQueryClient()
  const { data: status, isLoading } = useQuery({ queryKey: ['auth', 'me'], queryFn: authApi.me })
  const [mode, setMode] = useState<'idle' | 'replace' | 'codes' | 'disable'>('idle')
  const [code, setCode] = useState('')
  const [newCodes, setNewCodes] = useState<string[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = () => qc.invalidateQueries({ queryKey: ['auth'] })
  const reset = () => { setMode('idle'); setCode(''); setError('') }

  async function submitCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (mode === 'codes') {
        setNewCodes((await authApi.regenerateRecoveryCodes(code.replace(/\s/g, ''))).codes)
      } else {
        await authApi.disableMfa(code.trim())
      }
      reset()
      refresh()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  if (isLoading || !status) return <Card padding="lg"><Spinner /></Card>

  return (
    <Card padding="lg">
      <div className="mb-4 flex items-start gap-3">
        {status.mfaEnabled
          ? <ShieldCheck className="mt-0.5 h-5 w-5 text-green-600" />
          : <ShieldAlert className="mt-0.5 h-5 w-5 text-amber-500" />}
        <div className="flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-semibold text-gray-900 dark:text-gray-100">Two-step sign-in</h2>
            <Badge variant={status.mfaEnabled ? 'success' : 'warning'}>{status.mfaEnabled ? 'On' : 'Off'}</Badge>
            {status.mfaRequired && <Badge variant="info">Required</Badge>}
          </div>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {status.mfaEnabled
              ? `Signing in needs your password and a code from your authenticator app. ${status.recoveryCodesRemaining} recovery codes left.`
              : 'Add a code from your phone to every sign-in, so a stolen password is not enough.'}
          </p>
        </div>
      </div>

      {newCodes && <RecoveryCodesPanel codes={newCodes} onDone={() => setNewCodes(null)} />}

      {!newCodes && (!status.mfaEnabled || mode === 'replace') && (
        <TwoStepSetup onFinished={() => { reset(); refresh() }} onCancel={status.mfaEnabled ? reset : undefined} />
      )}

      {!newCodes && status.mfaEnabled && mode === 'idle' && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" onClick={() => setMode('codes')}>New recovery codes</Button>
          <Button size="sm" variant="secondary" onClick={() => setMode('replace')}>Replace authenticator</Button>
          {!status.mfaRequired && <Button size="sm" variant="danger" onClick={() => setMode('disable')}>Turn off</Button>}
        </div>
      )}

      {!newCodes && (mode === 'codes' || mode === 'disable') && (
        <form onSubmit={submitCode} className="flex flex-col gap-3">
          <p className="text-sm text-gray-600 dark:text-gray-400">
            {mode === 'codes'
              ? 'Your current recovery codes will stop working. Enter a code from your authenticator app to continue.'
              : 'Sign-in will need only your password. Enter a code from your authenticator app to confirm.'}
          </p>
          <Input label="Authenticator code" inputMode="numeric" autoComplete="one-time-code" required autoFocus
            value={code} onChange={e => setCode(e.target.value)} />
          {error && <p className="text-sm text-red-500">{error}</p>}
          <div className="flex gap-2">
            <Button type="submit" loading={busy} variant={mode === 'disable' ? 'danger' : 'primary'}>
              {mode === 'codes' ? 'Make new codes' : 'Turn off two-step sign-in'}
            </Button>
            <Button type="button" variant="ghost" onClick={reset}>Cancel</Button>
          </div>
        </form>
      )}
    </Card>
  )
}

function SessionsCard() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: sessions, isLoading } = useQuery({ queryKey: ['auth', 'sessions'], queryFn: authApi.sessions })
  const revoke = useMutation({
    mutationFn: (id: string) => authApi.revokeSession(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['auth', 'sessions'] }),
  })

  async function signOutEverywhere() {
    try { await authApi.logoutEverywhere() } catch { /* signed out regardless */ }
    endLocalSession()
    navigate('/login', { replace: true, state: { notice: 'Signed out on every device.' } })
  }

  return (
    <Card padding="lg">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900 dark:text-gray-100">Where you're signed in</h2>
          <p className="text-sm text-gray-500 dark:text-gray-400">Sign out anything you don't recognise.</p>
        </div>
        <Button size="sm" variant="danger" onClick={signOutEverywhere}><LogOut className="h-4 w-4" />Sign out everywhere</Button>
      </div>
      {isLoading ? <Spinner /> : (
        <ul className="divide-y divide-gray-100 dark:divide-gray-800">
          {(sessions ?? []).map(s => (
            <li key={s.id} className="flex items-center gap-3 py-3">
              <Monitor className="h-5 w-5 shrink-0 text-gray-400" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100" title={s.device}>
                  {describeDevice(s.device)} {s.current && <Badge variant="success" className="ml-1">This device</Badge>}
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {s.ip ? `${s.ip} · ` : ''}signed in {new Date(s.issuedAt).toLocaleString()}
                </p>
              </div>
              {!s.current && (
                <Button size="sm" variant="ghost" loading={revoke.isPending && revoke.variables === s.id}
                  onClick={() => revoke.mutate(s.id)}>Sign out</Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function PasswordCard() {
  const qc = useQueryClient()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setMessage('')
    setError('')
    if (next !== confirm) { setError('The new passwords do not match'); return }
    setBusy(true)
    try {
      const result = await authApi.changePassword(current, next)
      setMessage(result.message)
      setCurrent(''); setNext(''); setConfirm('')
      qc.invalidateQueries({ queryKey: ['auth', 'sessions'] })
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card padding="lg">
      <div className="mb-4 flex items-center gap-2">
        <KeyRound className="h-5 w-5 text-gray-400" />
        <h2 className="font-semibold text-gray-900 dark:text-gray-100">Password</h2>
      </div>
      <form onSubmit={submit} className="grid gap-3 sm:max-w-sm">
        <Input label="Current password" type="password" autoComplete="current-password" required
          value={current} onChange={e => setCurrent(e.target.value)} />
        <Input label="New password" type="password" autoComplete="new-password" required minLength={12}
          hint="At least 12 characters, mixing three of: upper case, lower case, digits, symbols."
          value={next} onChange={e => setNext(e.target.value)} />
        <Input label="Repeat new password" type="password" autoComplete="new-password" required
          value={confirm} onChange={e => setConfirm(e.target.value)} />
        {error && <p className="text-sm text-red-500">{error}</p>}
        {message && <p className="text-sm text-green-600">{message}</p>}
        <Button type="submit" loading={busy} className="justify-self-start">Change password</Button>
      </form>
    </Card>
  )
}

/** Take a copy of everything, or delete the account and all of it. */
export function DataCard() {
  const navigate = useNavigate()
  const [exporting, setExporting] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function download() {
    setExporting(true)
    setError('')
    try {
      const data = await dataApi.export()
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
      const a = document.createElement('a')
      a.href = url
      a.download = `jizza-export-${new Date().toISOString().slice(0, 10)}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setExporting(false)
    }
  }

  async function deleteEverything(e: FormEvent) {
    e.preventDefault()
    if (typed !== DELETE_CONFIRMATION) return
    setBusy(true)
    setError('')
    try {
      await dataApi.hardDelete(typed)
      endLocalSession()
      navigate('/login', { replace: true, state: { notice: 'Your account and everything in it has been deleted.' } })
    } catch (err) {
      setError(errorText(err))
      setBusy(false)
    }
  }

  return (
    <Card padding="lg">
      <div className="mb-1 flex items-center gap-2">
        <Download className="h-5 w-5 text-gray-400" />
        <h2 className="font-semibold text-gray-900 dark:text-gray-100">Your data</h2>
      </div>
      <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
        Download a copy of your agents, workflows, memory and activity, or delete your account and everything in it.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" loading={exporting} onClick={download}>
          <Download className="h-4 w-4" />Download my data
        </Button>
        {!confirming && (
          <Button size="sm" variant="danger" onClick={() => setConfirming(true)}>
            <Trash2 className="h-4 w-4" />Delete everything
          </Button>
        )}
      </div>
      {confirming && (
        <form onSubmit={deleteEverything} className="mt-4 flex flex-col gap-3 rounded-lg border border-red-200 p-3 dark:border-red-900/60">
          <p className="text-sm text-gray-700 dark:text-gray-300">
            This deletes your account, agents, conversations, memory, workflows and connections. It cannot be undone.
            Type <strong>{DELETE_CONFIRMATION}</strong> to confirm.
          </p>
          <Input label="Confirmation" autoComplete="off" value={typed} onChange={e => setTyped(e.target.value)} />
          <div className="flex gap-2">
            <Button type="submit" variant="danger" loading={busy} disabled={typed !== DELETE_CONFIRMATION}>Delete my account</Button>
            <Button type="button" variant="ghost" onClick={() => { setConfirming(false); setTyped('') }}>Cancel</Button>
          </div>
        </form>
      )}
      {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
    </Card>
  )
}

/** Browser and platform from a user agent, rather than the whole string. */
function describeDevice(ua?: string) {
  if (!ua) return 'Unknown device'
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : null
  const platform = /iPhone|iPad/.test(ua) ? 'iOS' : /Android/.test(ua) ? 'Android'
    : /Mac OS X|Macintosh/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : null
  if (!browser && !platform) return ua.slice(0, 60)
  return [browser, platform].filter(Boolean).join(' on ')
}
