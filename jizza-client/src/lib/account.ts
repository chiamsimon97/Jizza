import type { AuthorizationSnapshot } from '@/types'

export function isOrganization(snapshot: AuthorizationSnapshot | undefined): boolean {
  return snapshot?.activeWorkspace.type === 'ORGANIZATION'
}

/** A Family account: members only, with no Chat, no Agents and no organization pages. */
export function isFamily(snapshot: AuthorizationSnapshot | undefined): boolean {
  return snapshot?.activeWorkspace.type === 'FAMILY'
}

/** Whether the account has members, roles and invitations: an Organization or a Family. */
export function hasMembers(snapshot: AuthorizationSnapshot | undefined): boolean {
  return isOrganization(snapshot) || isFamily(snapshot)
}

/** Where an account opens: Chat for Individual, Members for an Organization or a Family. */
export function accountHome(snapshot: AuthorizationSnapshot | undefined): string {
  if (!snapshot || !hasMembers(snapshot)) return '/chat'
  return snapshot.permissions.includes('members_view') ? '/members' : '/notifications'
}
