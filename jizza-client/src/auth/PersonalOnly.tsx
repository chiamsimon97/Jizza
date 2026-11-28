import { Navigate, Outlet } from 'react-router-dom'
import { Spinner } from '@/components/ui/Spinner'
import { accountHome, hasMembers, useAuthorization } from './useAuthorization'

/**
 * Chat, agents, memory and the other personal modules belong to the Individual account. While an
 * Organization or a Family is active they send the user to that account's home instead.
 */
export function PersonalOnly() {
  const { data, isLoading } = useAuthorization()
  if (isLoading) return <div className="page flex justify-center py-16"><Spinner /></div>
  if (hasMembers(data)) return <Navigate to={accountHome(data)} replace />
  return <Outlet />
}
