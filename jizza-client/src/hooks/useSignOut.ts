import { useNavigate } from 'react-router-dom'
import { authApi } from '@/api/auth'
import { endLocalSession } from '@/lib/session'

/** Signs out on the server (if the session is still there), clears this browser and goes to sign-in. */
export function useSignOut() {
  const navigate = useNavigate()
  return async () => {
    try { await authApi.logout() } catch { /* session may already be gone */ }
    endLocalSession()
    navigate('/login')
  }
}
