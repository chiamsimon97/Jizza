import { useNavigate } from 'react-router-dom'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { StepUpDialog } from '@/components/layout/StepUpDialog'
import { TwoStepSetup } from '@/components/security/TwoStepSetup'
import { Button } from '@/components/ui/Button'
import { authApi } from '@/api/auth'
import { endLocalSession } from '@/lib/session'
import { useAuthStore } from '@/store/authStore'

/**
 * Shown when this Jizza requires two-step sign-in and the account has not set it up yet. Nothing
 * else opens until it is done.
 */
export function SetupTwoStepPage() {
  const navigate = useNavigate()
  const setEnrollmentRequired = useAuthStore(s => s.setEnrollmentRequired)

  async function signOut() {
    try { await authApi.logout() } catch { /* session may already be gone */ }
    endLocalSession()
    navigate('/login', { replace: true })
  }

  return (
    <AuthLayout>
      <StepUpDialog />
      <h1 className="mb-2 text-center text-2xl font-bold tracking-tight text-gray-900 dark:text-gray-100">
        Protect your account
      </h1>
      <p className="mb-6 text-center text-sm text-gray-500">
        Jizza holds your memory, personal data and the keys to your connected services, so it needs a
        second step at sign-in: a code from an app on your phone.
      </p>
      <TwoStepSetup onFinished={() => { setEnrollmentRequired(false); navigate('/chat', { replace: true }) }} />
      <Button type="button" variant="ghost" className="mt-4 w-full" onClick={signOut}>Sign out</Button>
    </AuthLayout>
  )
}
