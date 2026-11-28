import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { workspacesApi } from '@/api/workspaces'
import { PersonalOnly } from '@/auth/PersonalOnly'
import { accountHome, hasMembers, isFamily } from '@/lib/account'
import { canOpenPage } from '@/jizza/pages'
import { postSignInDestination } from '@/lib/session'
import { MembersPage } from '@/pages/MembersPage'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { menuFor, settingsFor } from './navigation'
import { Sidebar } from './Sidebar'

const family = (role: 'OWNER' | 'ADMIN' | 'USER' = 'OWNER') => makeSnapshot('FAMILY', role)

describe('The Family account', () => {
  it('has members but is neither Individual nor an organization', () => {
    expect(isFamily(family())).toBe(true)
    expect(hasMembers(family())).toBe(true)
    expect(hasMembers(makeSnapshot('ORGANIZATION'))).toBe(true)
    expect(hasMembers(makeSnapshot('PERSONAL'))).toBe(false)
    expect(accountHome(family())).toBe('/members')
    expect(postSignInDestination(family('USER'))).toEqual({ to: '/members' })
  })

  it('shows Members in the left menu and never Chat or Agents', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, family())
    const nav = within(screen.getByRole('navigation'))
    expect(nav.getAllByRole('link').map(l => l.textContent)).toEqual(['Members'])
    for (const label of ['Chat', 'Agents', 'Notifications', 'Discover', 'Organization', 'Tasks', 'Build']) {
      expect(nav.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
    expect(menuFor(family()).main.map(h => h.label)).toEqual(['Members'])
  })

  it('has General and Security only in Settings', () => {
    const items = settingsFor(family()).flatMap(g => g.items.map(i => i.label))
    expect(items).toEqual(['General', 'Security'])
  })

  it('lets Jizza open Members only', () => {
    expect(canOpenPage('members', family())).toBe(true)
    for (const key of ['chat', 'agents', 'memory', 'tasks', 'workflows', 'connections', 'notifications']) {
      expect(canOpenPage(key, family())).toBe(false)
    }
    expect(canOpenPage('members', makeSnapshot('PERSONAL'))).toBe(false)
  })

  it('sends a Family away from Chat to Members', () => {
    renderWithSnapshot(
      <Routes>
        <Route element={<PersonalOnly />}><Route path="/chat" element={<p>Chat page</p>} /></Route>
        <Route path="/members" element={<p>Members page</p>} />
      </Routes>, family(), '/chat')
    expect(screen.getByText('Members page')).toBeInTheDocument()
    expect(screen.queryByText('Chat page')).not.toBeInTheDocument()
  })

  it('names the Family on the no-access page', () => {
    renderWithSnapshot(<NoAccessPage />, family('USER'))
    expect(screen.getByText(/Ask an Owner or Admin of The Raos/)).toBeInTheDocument()
  })
})

describe('The account menu with a Family', () => {
  it('lists the Family with the word Family and the role', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('PERSONAL', 'OWNER', {
      workspaces: [
        { id: 'ws-personal', type: 'PERSONAL', name: 'Personal', role: 'OWNER' },
        { id: 'ws-family', type: 'FAMILY', name: 'The Raos', role: 'USER' },
      ],
    }))
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }))
    const item = screen.getByRole('menuitem', { name: /The Raos/ })
    expect(item).toHaveTextContent('Family')
    expect(item).toHaveTextContent('Member')
    // A member of someone else's Family can still create their own.
    expect(screen.getByRole('menuitem', { name: 'Create a Family' })).toBeInTheDocument()
  })

  it('offers no second Family to a person who owns one', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, family())
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }))
    expect(screen.queryByRole('menuitem', { name: 'Create a Family' })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Create organization' })).toBeInTheDocument()
    expect(screen.getAllByText('Family · Owner').length).toBeGreaterThan(0)
  })

  it('creates a Family from one field and offers to switch to it', async () => {
    const create = vi.spyOn(workspacesApi, 'createFamily')
      .mockResolvedValue({ id: 'ws-new', type: 'FAMILY', name: 'The Raos', role: 'OWNER' })
    const org = vi.spyOn(workspacesApi, 'create')
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('PERSONAL'))
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Create a Family' }))
    const dialog = within(screen.getByRole('dialog'))
    const create1 = dialog.getByRole('button', { name: 'Create' })
    fireEvent.change(dialog.getByLabelText(/Family name/), { target: { value: 'R' } })
    expect(dialog.getByText('Use 2 to 100 characters')).toBeInTheDocument()
    expect(create1).toBeDisabled()
    fireEvent.change(dialog.getByLabelText(/Family name/), { target: { value: '  The Raos ' } })
    fireEvent.click(create1)
    await waitFor(() => expect(create).toHaveBeenCalledWith('The Raos'))
    expect(org).not.toHaveBeenCalled()
    expect(await screen.findByText('Family created')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Switch to The Raos' })).toBeInTheDocument()
  })
})

describe('Members in a Family', () => {
  it('uses Family wording, offers Admin and Member only, and has no join requests', async () => {
    vi.spyOn(workspacesApi, 'members').mockResolvedValue([
      { id: 'm1', userId: 'user-1', email: 'me@example.com', role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-11-28T08:00:00Z', you: true },
      { id: 'm2', userId: 'user-2', email: 'amma@example.com', role: 'USER', status: 'ACTIVE', joinedAt: '2026-11-28T09:00:00Z', you: false },
    ])
    vi.spyOn(workspacesApi, 'invitations').mockResolvedValue([])
    renderWithSnapshot(<MembersPage />, family())
    expect(screen.getByText(/Who is in The Raos/)).toBeInTheDocument()
    const select = await screen.findByLabelText('Role for amma@example.com')
    expect(within(select).getAllByRole('option').map(o => o.textContent)).toEqual(['Member', 'Admin'])
    expect(screen.queryByText('Join requests')).not.toBeInTheDocument()
    expect(screen.getByText("As its Owner you can't leave the Family.")).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Add member/ }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getAllByRole('option').map(o => o.textContent)).toEqual(['Admin', 'Member'])
  })

  it('lets a member leave the Family', () => {
    vi.spyOn(workspacesApi, 'members').mockResolvedValue([])
    vi.spyOn(workspacesApi, 'invitations').mockResolvedValue([])
    renderWithSnapshot(<MembersPage />, family('USER'))
    expect(screen.getByRole('button', { name: /Leave the Family/ })).toBeInTheDocument()
  })
})
