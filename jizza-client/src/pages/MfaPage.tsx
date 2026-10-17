import { FormEvent, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { destinationAfterSignIn } from '@/auth/signIn'

export function MfaPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from
  const { mfaToken, setAuthenticated, clear } = useAuthStore()
  const [code, setCode] = useState('')
  const [useRecovery, setUseRecovery] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!mfaToken) { navigate('/login'); return }
    setError('')
    setLoading(true)
    try {
      const result = await authApi.verifyMfa({ mfaToken: mfaToken, code: code.trim() })
      if (result.userId) {
        setAuthenticated(result.userId, result.enrollmentRequired)
        if (result.enrollmentRequired) {
          navigate('/setup-2fa', { replace: true })
        } else {
          const { to, state } = await destinationAfterSignIn(from)
          navigate(to, { replace: true, state })
        }
      }
    } catch (err: unknown) {
      // A wrong code spends the sign-in attempt, so the password is needed again.
      clear()
      navigate('/login', { replace: true, state: { notice: err instanceof Error ? `${err.message}. Sign in again.` : 'Sign in again.' } })
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-2 text-center text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">Two-factor auth</h1>
      <p className="mb-8 text-center text-sm text-gray-500">
        {useRecovery
          ? 'Enter one of the recovery codes you saved. Each one works once.'
          : 'Enter the 6-digit code from your authenticator app.'}
      </p>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        {useRecovery ? (
          <Input
            key="recovery"
            label="Recovery code"
            type="text"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            placeholder="xxxx-xxxx-xxxx"
            maxLength={20}
            required
            autoFocus
            value={code}
            onChange={e => setCode(e.target.value)}
          />
        ) : (
          <Input
            key="totp"
            label="Code"
            type="text"
            inputMode="numeric"
            pattern="[0-9]{6}"
            maxLength={6}
            autoComplete="one-time-code"
            required
            autoFocus
            value={code}
            onChange={e => setCode(e.target.value)}
          />
        )}
        {error && <p className="text-sm text-red-500">{error}</p>}
        <Button type="submit" loading={loading}>Verify</Button>
        <button type="button" className="text-sm text-brand-600 hover:underline dark:text-brand-400"
          onClick={() => { setUseRecovery(r => !r); setCode('') }}>
          {useRecovery ? 'Use my authenticator app instead' : 'Lost your phone? Use a recovery code'}
        </button>
        <Button type="button" variant="ghost" onClick={() => { clear(); navigate('/login') }}>
          Back to login
        </Button>
      </form>
    </AuthLayout>
  )
}
