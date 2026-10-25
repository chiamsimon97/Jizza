import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { publicRolesApi } from '@/api/listings'
import type { Member } from '@/types'
import { PublicRolesPage } from './PublicRolesPage'

function member(overrides: Partial<Member>): Member {
  return { id: 'm1', userId: 'u1', email: 'ann@example.com', role: 'USER', status: 'ACTIVE', joinedAt: '2026-10-25T08:00:00Z', you: false, ...overrides }
}

describe('Public roles page', () => {
  it('lets the Super Admin make someone a Developer, and keeps the Owner fixed', async () => {
    vi.spyOn(publicRolesApi, 'list').mockResolvedValue({
      workspaceId: 'ws-public', workspaceName: 'Jizza Public',
      members: [member({ id: 'm0', email: 'boss@example.com', role: 'OWNER', you: true }), member({})],
    })
    const setRole = vi.spyOn(publicRolesApi, 'setRole').mockResolvedValue(member({ role: 'DEVELOPER' }))
    renderWithSnapshot(<PublicRolesPage />, makeSnapshot('PERSONAL', 'OWNER', { platformRole: 'SUPER_ADMIN' }))

    const select = await screen.findByLabelText('Public role for ann@example.com')
    expect(screen.queryByLabelText('Public role for boss@example.com')).not.toBeInTheDocument()
    expect(screen.getByText(/Everyone on Jizza belongs to Jizza Public/)).toBeInTheDocument()
    fireEvent.change(select, { target: { value: 'DEVELOPER' } })
    await waitFor(() => expect(setRole).toHaveBeenCalledWith('m1', 'DEVELOPER'))
  })

  it('is closed to everyone else', () => {
    const list = vi.spyOn(publicRolesApi, 'list')
    renderWithSnapshot(<PublicRolesPage />, makeSnapshot('ORGANIZATION', 'ADMIN'))
    expect(screen.getByText('Only a Super Admin can change Public roles.')).toBeInTheDocument()
    expect(list).not.toHaveBeenCalled()
  })
})
