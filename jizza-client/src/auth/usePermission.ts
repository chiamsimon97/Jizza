import { useQuery } from '@tanstack/react-query'
import { meApi } from '@/api/me'
import { PERMISSION_MODULE, type PermissionName } from '@/lib/permissions'
import type { PermissionDefinition } from '@/types'
import { useAuthorization } from './useAuthorization'

/**
 * Whether the active account grants this permission. Always read from the server's snapshot, never
 * worked out from the role name. False while the snapshot is still loading.
 */
export function usePermission(name: string): boolean {
  const { data } = useAuthorization()
  return data?.permissions.includes(name) ?? false
}

/** Labels, descriptions and areas for every permission, cached until the server's file changes. */
export function usePermissionDefinitions() {
  const { data: snapshot } = useAuthorization()
  return useQuery({
    queryKey: ['permissions', snapshot?.permissionsVersion],
    queryFn: meApi.permissions,
    enabled: snapshot != null,
    staleTime: Infinity,
  })
}

function moduleOf(name: string, definitions: PermissionDefinition[] | undefined): string {
  const fromServer = definitions?.find(d => d.name === name)?.module
  if (fromServer) return fromServer
  return PERMISSION_MODULE[name as PermissionName] ?? name.split('_')[0]
}

/** Whether any permission in this area (`connector`, `members`) is granted, e.g. to show its nav item. */
export function useModuleVisible(module: string): boolean {
  const { data: snapshot } = useAuthorization()
  const { data: definitions } = usePermissionDefinitions()
  return snapshot?.permissions.some(p => moduleOf(p, definitions) === module) ?? false
}
