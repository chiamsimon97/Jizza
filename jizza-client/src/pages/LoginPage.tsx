import { FormEvent, useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { setAuthenticated, setPendingMfa } = useAuthStore()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const from = (location.state as { from?: Location })?.from?.pathname ?? '/chat'

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const result = await authApi.login({ email, password })
      if (result.mfaRequired && result.mfaToken) {
        setPendingMfa(result.mfaToken)
        navigate('/mfa')
      } else if (result.userId) {
        setAuthenticated(result.userId)
        navigate(from, { replace: true })
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 dark:bg-gray-950 px-4">
      <div className="w-full max-w-sm">
        <h1 className="mb-8 text-center text-2xl font-bold text-gray-900 dark:text-gray-100">
          Sign in to <span className="text-brand-500">Jizza</span>
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
          {error && <p className="text-sm text-red-500">{error}</p>}
          <Button type="submit" loading={loading} className="mt-1">Sign in</Button>
        </form>
        <p className="mt-6 text-center text-sm text-gray-500">
          No account?{' '}
          <Link to="/register" className="text-brand-500 hover:underline">Register</Link>
          {' · '}
          <Link to="/recover" className="text-brand-500 hover:underline">Forgot password</Link>
        </p>
      </div>
    </div>
  )
}
