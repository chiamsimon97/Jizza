import type { AuthorizationSnapshot } from '@/types'

export function isOrganization(snapshot: AuthorizationSnapshot | undefined): boolean {
  return snapshot?.activeWorkspace.type === 'ORGANIZATION'
}

/** Where an account opens: Chat for Personal, Members for an Organization. */
export function accountHome(snapshot: AuthorizationSnapshot | undefined): string {
  if (!snapshot || !isOrganization(snapshot)) return '/chat'
  return snapshot.permissions.includes('members_view') ? '/members' : '/notifications'
}
