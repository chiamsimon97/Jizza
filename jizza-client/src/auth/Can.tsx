import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'
import { usePermission } from './usePermission'

export const VIEW_ONLY = 'You have view-only access'

interface CanProps {
  permission: string
  /** `hide` leaves the children out; `disable` shows them disabled with a view-only tooltip. */
  mode?: 'hide' | 'disable'
  children: ReactNode
}

/**
 * Shows controls only to roles that hold the permission. Purely cosmetic: the server checks every
 * request regardless.
 */
export function Can({ permission, mode = 'hide', children }: CanProps) {
  const granted = usePermission(permission)
  if (granted) return <>{children}</>
  if (mode === 'hide') return null
  return (
    <>
      {Children.map(children, child =>
        isValidElement(child)
          ? cloneElement(child as ReactElement<Record<string, unknown>>, {
              disabled: true,
              'aria-disabled': true,
              title: VIEW_ONLY,
            })
          : child,
      )}
    </>
  )
}
