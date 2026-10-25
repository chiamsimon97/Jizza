import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { accountRegistryApi } from '@/api/listings'
import type { RegisteredAccount } from '@/types'
import { RegisteredAccountsPage } from './RegisteredAccountsPage'

function account(overrides: Partial<RegisteredAccount>): RegisteredAccount {
  return {
    workspaceId: 'ws-ann', name: 'Personal', type: 'PERSONAL', ownerUserId: 'u-ann', ownerEmail: 'ann@example.com',
    source: 'INDIVIDUAL_SIGNUP', registeredAt: '2026-10-25T08:00:00Z', publicRole: 'USER', ...overrides,
  }
}

describe('Registered accounts page', () => {
  it('lists Individual and Team accounts and makes a person a Developer', async () => {
    vi.spyOn(accountRegistryApi, 'list').mockResolvedValue({
      workspaceId: 'ws-public', workspaceName: 'Jizza Public',
      accounts: [
        account({}),
        account({ workspaceId: 'ws-boss', ownerUserId: 'u-boss', ownerEmail: 'boss@example.com', publicRole: 'OWNER' }),
        account({ workspaceId: 'ws-acme', name: 'Acme', type: 'ORGANIZATION', source: 'ORGANIZATION_SIGNUP' }),
      ],
    })
    const setRole = vi.spyOn(accountRegistryApi, 'setRole').mockResolvedValue({ userId: 'u-ann', role: 'DEVELOPER' })
    renderWithSnapshot(<RegisteredAccountsPage />, makeSnapshot('PERSONAL', 'OWNER', { platformRole: 'SUPER_ADMIN' }))

    await screen.findByLabelText('Role in Jizza Public for ann@example.com')
    expect(screen.getByText('Acme')).toBeInTheDocument()
    expect(screen.getByText(/Team sign-up/)).toBeInTheDocument()
    expect(screen.getByText('2 Individual and 1 Team account.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Role in Jizza Public for boss@example.com')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Account type'), { target: { value: 'ORGANIZATION' } })
    expect(screen.queryByText('ann@example.com')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Account type'), { target: { value: '' } })

    fireEvent.change(screen.getByLabelText('Role in Jizza Public for ann@example.com'), { target: { value: 'DEVELOPER' } })
    await waitFor(() => expect(setRole).toHaveBeenCalledWith('u-ann', 'DEVELOPER'))
  })

  it('is closed to everyone else', () => {
    const list = vi.spyOn(accountRegistryApi, 'list')
    renderWithSnapshot(<RegisteredAccountsPage />, makeSnapshot('ORGANIZATION', 'ADMIN'))
    expect(screen.getByText('Only a Super Admin can see registered accounts.')).toBeInTheDocument()
    expect(list).not.toHaveBeenCalled()
  })
})
