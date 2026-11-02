import { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { destinationAfterSignIn } from '@/auth/signIn'
import { resetSignInFlags } from '@/lib/session'
import { SmartSignInPanel } from '@/components/auth/SmartSignInPanel'
import { smartSignInApi } from '@/api/smartSignIn'
import type { LoginResult } from '@/types'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { setAuthenticated, setPendingMfa } = useAuthStore()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [smartSignIn, setSmartSignIn] = useState(false)

  useEffect(() => {
    let live = true
    smartSignInApi.config().then(c => { if (live) setSmartSignIn(c.enabled) }).catch(() => {})
    return () => { live = false }
  }, [])

  const from = (location.state as { from?: Location })?.from?.pathname
  const notice = (location.state as { notice?: string })?.notice

  async function signedIn(result: LoginResult) {
    if (!result.userId) return
    setAuthenticated(result.userId, result.enrollmentRequired)
    if (result.enrollmentRequired) {
      navigate('/setup-2fa', { replace: true })
    } else {
      const { to, state } = await destinationAfterSignIn(from)
      navigate(to, { replace: true, state })
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    resetSignInFlags()
    try {
      const result = await authApi.login({ email, password })
      if (result.mfaRequired && result.mfaToken) {
        setPendingMfa(result.mfaToken)
        navigate('/mfa', { state: { from } })
      } else if (result.userId) {
        await signedIn(result)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-8 text-center text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">
        Sign in to <span className="brand-gradient-text">Jizza</span>
      </h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={e => setEmail(e.target.value)}
        />
        <Input
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={e => setPassword(e.target.value)}
        />
        {notice && !error && <p className="text-sm text-green-600">{notice}</p>}
        {error && <p className="text-sm text-red-500">{error}</p>}
        <Button type="submit" loading={loading} className="mt-1">Sign in</Button>
      </form>
      {smartSignIn && (
        <>
          <div className="my-6 flex items-center gap-3 text-xs uppercase tracking-wide text-gray-400">
            <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
            or
            <span className="h-px flex-1 bg-gray-200 dark:bg-gray-800" />
          </div>
          <SmartSignInPanel onSignedIn={result => { resetSignInFlags(); void signedIn(result) }} />
        </>
      )}
      <p className="mt-6 text-center text-sm text-gray-500">
        No account?{' '}
        <Link to="/register" className="font-medium text-brand-600 transition-colors hover:text-brand-700 hover:underline dark:text-brand-400 dark:hover:text-brand-300">Register</Link>
        {' · '}
        <Link to="/recover" className="font-medium text-brand-600 transition-colors hover:text-brand-700 hover:underline dark:text-brand-400 dark:hover:text-brand-300">Forgot password</Link>
      </p>
    </AuthLayout>
  )
}
