import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'

export function RecoverPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await authApi.requestReset(email)
      setSent(true)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 dark:bg-gray-950 px-4">
      <div className="w-full max-w-sm">
        <h1 className="mb-2 text-center text-2xl font-bold text-gray-900 dark:text-gray-100">Recover account</h1>
        {sent ? (
          <p className="mt-4 text-center text-sm text-green-600 dark:text-green-400">
            If that address exists you'll get a reset link shortly.
          </p>
        ) : (
          <form onSubmit={handleSubmit} className="mt-8 flex flex-col gap-4">
            <Input
              label="Email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
            />
            {error && <p className="text-sm text-red-500">{error}</p>}
            <Button type="submit" loading={loading}>Send reset link</Button>
          </form>
        )}
        <p className="mt-6 text-center text-sm text-gray-500">
          <Link to="/login" className="text-brand-500 hover:underline">Back to login</Link>
        </p>
      </div>
    </div>
  )
}
