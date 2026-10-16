import type { ReactNode } from 'react'
import { Outlet } from 'react-router-dom'
import { Spinner } from '@/components/ui/Spinner'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { useAuthorization } from './useAuthorization'

/** Route guard: the page opens only when the active account grants the permission. */
export function RequirePermission({ permission, children }: { permission: string; children?: ReactNode }) {
  const { data, isLoading } = useAuthorization()
  if (isLoading) return <div className="page flex justify-center py-16"><Spinner /></div>
  if (!data?.permissions.includes(permission)) return <NoAccessPage />
  return <>{children ?? <Outlet />}</>
}
