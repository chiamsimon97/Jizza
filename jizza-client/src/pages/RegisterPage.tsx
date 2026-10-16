import { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Building2, User } from 'lucide-react'
import { authApi } from '@/api/auth'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { AuthLayout } from '@/components/layout/AuthLayout'
import type { WorkspaceType } from '@/types'

const ACCOUNT_TYPES: { type: WorkspaceType; label: string; description: string; icon: typeof User }[] = [
  { type: 'PERSONAL',     label: 'Individual',   description: 'Just you, with your own agents and memory.', icon: User },
  { type: 'ORGANIZATION', label: 'Organization', description: 'Also set up a team that shares connectors.', icon: Building2 },
]

export function RegisterPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const inviteToken = params.get('invite') ?? undefined
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [accountType, setAccountType] = useState<WorkspaceType>('PERSONAL')
  const [organizationName, setOrganizationName] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  // Arriving from an invitation link: the email is the one the request was sent to.
  const invitation = useQuery({
    queryKey: ['auth', 'invitation', inviteToken],
    queryFn: () => authApi.invitation(inviteToken!),
    enabled: inviteToken != null,
    retry: false,
    staleTime: Infinity,
  })
  const invite = invitation.data
  useEffect(() => { if (invite) setEmail(invite.email) }, [invite])

  const isOrg = accountType === 'ORGANIZATION'
  const orgNameLength = organizationName.trim().length
  const orgNameInvalid = isOrg && organizationName.length > 0 && (orgNameLength < 2 || orgNameLength > 100)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (isOrg && (orgNameLength < 2 || orgNameLength > 100)) {
      setError('Organization name must be 2 to 100 characters')
      return
    }
    setLoading(true)
    try {
      const result = await authApi.register({
        email,
        password,
        accountType,
        organizationName: isOrg ? organizationName.trim() : undefined,
        invitationToken: invite ? inviteToken : undefined,
      })
      // Registration does not sign the user in; send them to the login page with the server's notice.
      navigate('/login', { replace: true, state: { notice: result.message } })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Registration failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <h1 className="mb-8 text-center text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">
        Create account
      </h1>
      {inviteToken && invitation.isLoading && <div className="mb-4 flex justify-center"><Spinner size="sm" /></div>}
      {invite && (
        <p className="mb-4 rounded-lg bg-brand-50 px-3 py-2 text-sm text-brand-700 dark:bg-brand-500/15 dark:text-brand-300">
          You were invited to {invite.workspaceName}. Create your account with this email to see the request.
        </p>
      )}
      {inviteToken && invitation.isError && (
        <p className="mb-4 rounded-lg bg-yellow-50 px-3 py-2 text-sm text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400">
          This invitation link is not valid any more. Ask the person who invited you to send it again.
        </p>
      )}
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <fieldset>
          <legend className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">Account type</legend>
          <div role="radiogroup" aria-label="Account type" className="grid grid-cols-2 gap-2">
            {ACCOUNT_TYPES.map(({ type, label, description, icon: Icon }) => {
              const selected = accountType === type
              return (
                <button key={type} type="button" role="radio" aria-checked={selected} onClick={() => setAccountType(type)}
                  className={`flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 ${
                    selected
                      ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500 dark:bg-brand-500/10'
                      : 'border-gray-300 hover:border-gray-400 dark:border-gray-700 dark:hover:border-gray-600'
                  }`}>
                  <Icon className={`h-5 w-5 ${selected ? 'text-brand-600 dark:text-brand-300' : 'text-gray-400'}`} />
                  <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">{label}</span>
                  <span className="text-xs text-gray-500 dark:text-gray-400">{description}</span>
                </button>
              )
            })}
          </div>
        </fieldset>
        {isOrg && (
          <Input
            label="Organization name"
            type="text"
            autoComplete="organization"
            required
            minLength={2}
            maxLength={100}
            value={organizationName}
            onChange={e => setOrganizationName(e.target.value)}
            error={orgNameInvalid ? 'Use 2 to 100 characters' : undefined}
          />
        )}
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          required
          readOnly={invite != null}
          className={invite ? 'bg-gray-50 text-gray-600 dark:bg-gray-800 dark:text-gray-300' : ''}
          value={email}
          onChange={e => setEmail(e.target.value)}
        />
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          required
          minLength={12}
          value={password}
          onChange={e => setPassword(e.target.value)}
          hint="Minimum 12 characters"
        />
        {error && <p className="text-sm text-red-500">{error}</p>}
        <Button type="submit" loading={loading} className="mt-1">Create account</Button>
      </form>
      <p className="mt-6 text-center text-sm text-gray-500">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-brand-600 transition-colors hover:text-brand-700 hover:underline dark:text-brand-400 dark:hover:text-brand-300">Sign in</Link>
      </p>
    </AuthLayout>
  )
}
