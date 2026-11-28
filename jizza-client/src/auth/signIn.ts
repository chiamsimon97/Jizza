import { meApi } from '@/api/me'
import { vitaApi } from '@/api/vita'
import { queryClient } from '@/lib/queryClient'
import { postSignInDestination, type SignInDestination } from '@/lib/session'
import { CONTEXT_KEY } from './useAuthorization'

/**
 * Sign-in is complete (second factor or two-step setup done): load the account snapshot and work
 * out where to go. Pending membership requests come first, once per sign-in.
 */
export async function destinationAfterSignIn(from?: string): Promise<SignInDestination> {
  try {
    const context = await meApi.getContext()
    queryClient.setQueryData(CONTEXT_KEY, context)
    // Only an Individual account heading nowhere in particular asks Vita; a 404 means health is off.
    const openOn = (!from || from === '/') && context.activeWorkspace.type === 'PERSONAL'
      ? await vitaApi.status().then(s => s.openOn ?? null, () => null)
      : null
    return postSignInDestination(context, from, openOn)
  } catch {
    return { to: from ?? '/chat' }
  }
}
