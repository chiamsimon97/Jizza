import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/store/authStore'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { AuthLayout } from '@/components/layout/AuthLayout'

export function MfaPage() {
  const navigate = useNavigate()
  const { mfaToken, setAuthenticated, clear } = useAuthStore()
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!mfaToken) { navigate('/login'); return }
    setError('')
    setLoading(true)
    try {
      const result = await authApi.verifyMfa({ mfaToken: mfaToken, code })
      if (result.userId) {
        setAuthenticated(result.userId)
        navigate('/chat', { replace: true })
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Invalid code')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-2 text-center text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">Two-factor auth</h1>
      <p className="mb-8 text-center text-sm text-gray-500">Enter the 6-digit code from your authenticator app.</p>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Input
          label="Code"
          type="text"
          inputMode="numeric"
          pattern="[0-9]{6}"
          maxLength={6}
          autoComplete="one-time-code"
          required
          value={code}
          onChange={e => setCode(e.target.value)}
        />
        {error && <p className="text-sm text-red-500">{error}</p>}
        <Button type="submit" loading={loading}>Verify</Button>
        <Button type="button" variant="ghost" onClick={() => { clear(); navigate('/login') }}>
          Back to login
        </Button>
      </form>
    </AuthLayout>
  )
}
