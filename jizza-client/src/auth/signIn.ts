import { meApi } from '@/api/me'
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
    return postSignInDestination(context, from)
  } catch {
    return { to: from ?? '/chat' }
  }
}
