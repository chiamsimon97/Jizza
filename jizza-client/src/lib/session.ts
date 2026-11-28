import { useAuthStore } from '@/store/authStore'
import { queryClient } from '@/lib/queryClient'
import { accountHome } from '@/lib/account'
import type { AuthorizationSnapshot } from '@/types'

/**
 * Ending a session on this device: forget the sign-in, drop every cached response (conversations,
 * memory, connector details) so nothing personal stays in the page, and tell the other open tabs to
 * do the same.
 */
const channel: BroadcastChannel | null =
  typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('jizza-auth') : null

function wipeLocal() {
  useAuthStore.getState().clear()
  queryClient.clear()
  try { sessionStorage.clear() } catch { /* storage may be unavailable */ }
}

export function endLocalSession() {
  wipeLocal()
  channel?.postMessage({ type: 'signed-out' })
}

/** Another tab signed out: follow it. */
channel?.addEventListener('message', (event: MessageEvent) => {
  if ((event.data as { type?: string } | null)?.type === 'signed-out' && useAuthStore.getState().isAuthenticated) {
    wipeLocal()
  }
})

const INVITATIONS_SHOWN_KEY = 'jizza-invitations-shown'

export interface SignInDestination {
  to: string
  /** Set when going to /invitations: where "Decide later" continues to. */
  state?: { next: string }
}

/**
 * Where to go once sign-in is complete. Pending membership requests are shown first, once per
 * sign-in (signing out clears the flag along with the rest of session storage); otherwise the page
 * the user was heading for, or the active account's home. A person linked to their own health
 * record lands on "My medicines today" when /api/vita/status answers `openOn: my-medicines`.
 */
export function postSignInDestination(context: AuthorizationSnapshot, from?: string, vitaOpenOn?: string | null): SignInDestination {
  const home = vitaOpenOn === 'my-medicines' && context.activeWorkspace.type === 'PERSONAL' ? '/my-medicines' : accountHome(context)
  const next = from && from !== '/invitations' && !(from === '/' && home === '/my-medicines') ? from : home
  if (context.pendingInvitationCount > 0 && !invitationsShown()) {
    markInvitationsShown()
    return { to: '/invitations', state: { next } }
  }
  return { to: next }
}

function invitationsShown(): boolean {
  try { return sessionStorage.getItem(INVITATIONS_SHOWN_KEY) === '1' } catch { return false }
}

function markInvitationsShown() {
  try { sessionStorage.setItem(INVITATIONS_SHOWN_KEY, '1') } catch { /* storage may be unavailable */ }
}

/** A new sign-in is starting: its pending requests should be offered again. */
export function resetSignInFlags() {
  try { sessionStorage.removeItem(INVITATIONS_SHOWN_KEY) } catch { /* storage may be unavailable */ }
}
