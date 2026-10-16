import { describe, expect, it } from 'vitest'
import { makeSnapshot } from '@/test/utils'
import { postSignInDestination, resetSignInFlags } from './session'

describe('postSignInDestination', () => {
  it('goes to Chat for a Personal account with nothing pending', () => {
    expect(postSignInDestination(makeSnapshot('PERSONAL'))).toEqual({ to: '/chat' })
  })

  it('goes to Members for an Organization account with nothing pending', () => {
    expect(postSignInDestination(makeSnapshot('ORGANIZATION', 'USER'))).toEqual({ to: '/members' })
  })

  it('goes back to the page the user was heading for', () => {
    expect(postSignInDestination(makeSnapshot('PERSONAL'), '/tasks')).toEqual({ to: '/tasks' })
  })

  it('shows pending requests first, remembering where to continue, once per sign-in', () => {
    const context = makeSnapshot('PERSONAL', 'OWNER', { pendingInvitationCount: 2 })
    expect(postSignInDestination(context, '/agents')).toEqual({ to: '/invitations', state: { next: '/agents' } })
    expect(postSignInDestination(context, '/agents')).toEqual({ to: '/agents' })
  })

  it('offers pending requests again on the next sign-in', () => {
    const context = makeSnapshot('ORGANIZATION', 'OWNER', { pendingInvitationCount: 1 })
    expect(postSignInDestination(context).to).toBe('/invitations')
    resetSignInFlags()
    expect(postSignInDestination(context)).toEqual({ to: '/invitations', state: { next: '/members' } })
  })
})
