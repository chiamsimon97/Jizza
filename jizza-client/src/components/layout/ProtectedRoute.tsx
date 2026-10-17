import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'

export function ProtectedRoute() {
  const { isAuthenticated, enrollmentRequired } = useAuthStore()
  const location = useLocation()
  if (!isAuthenticated) return <Navigate to="/login" state={{ from: location }} replace />
  // Two-step sign-in is required and not set up yet: nothing opens until it is.
  if (enrollmentRequired) return <Navigate to="/setup-2fa" replace />
  return <Outlet />
}

/** The two-step setup screen: signed in, but only reachable while setup is still owed. */
export function EnrollmentRoute() {
  const { isAuthenticated, enrollmentRequired } = useAuthStore()
  if (!isAuthenticated) return <Navigate to="/login" replace />
  if (!enrollmentRequired) return <Navigate to="/chat" replace />
  return <Outlet />
}
