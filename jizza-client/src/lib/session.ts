import { useAuthStore } from '@/store/authStore'
import { queryClient } from '@/lib/queryClient'

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
