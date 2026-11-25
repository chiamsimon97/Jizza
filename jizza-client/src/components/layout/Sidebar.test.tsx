import { describe, expect, it } from 'vitest'
import { cleanup, fireEvent, screen, within } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { Sidebar } from './Sidebar'

function renderSidebar(snapshot = makeSnapshot()) {
  renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, snapshot)
  return within(screen.getByRole('navigation'))
}

describe('Sidebar', () => {
  it('shows a short menu of grouped entries for a Personal account', () => {
    const nav = renderSidebar(makeSnapshot('PERSONAL'))
    const labels = nav.getAllByRole('link').map(l => l.textContent)
    expect(labels).toEqual(['Chat', 'Notifications', 'Discover', 'Agents', 'Tasks', 'Build', 'Activity'])
    expect(nav.getByRole('link', { name: 'Build' })).toHaveAttribute('href', '/workflows')
    expect(nav.getByRole('link', { name: 'Activity' })).toHaveAttribute('href', '/activity')
  })

  it('keeps configuration pages out of the left menu', () => {
    const nav = renderSidebar(makeSnapshot('PERSONAL'))
    for (const label of ['Connectors', 'Guardrails', 'Usage', 'Devices', 'AI models', 'Security', 'Help', 'Workflows', 'Run history']) {
      expect(nav.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
  })

  it('opens Settings, Help and Sign out from the account menu', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('PERSONAL'))
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }))
    const menu = within(screen.getByRole('menu', { name: 'Account menu' }))
    expect(menu.getByRole('menuitem', { name: 'Settings' })).toHaveAttribute('href', '/settings')
    expect(menu.getByRole('menuitem', { name: 'Get help' })).toHaveAttribute('href', '/help')
    expect(menu.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument()
    expect(menu.getByRole('radiogroup', { name: 'Theme' })).toBeInTheDocument()
    expect(menu.getByText('me@example.com')).toBeInTheDocument()
  })

  it('groups an Organization Owner\'s pages under Organization', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'OWNER'))
    const labels = nav.getAllByRole('link').map(l => l.textContent)
    expect(labels).toEqual(['Notifications', 'Discover', 'Organization'])
    expect(nav.getByRole('link', { name: 'Organization' })).toHaveAttribute('href', '/members')
  })

  it('leaves Organization out when the role can open none of its pages', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'USER', { permissions: [] }))
    expect(nav.queryByRole('link', { name: 'Organization' })).not.toBeInTheDocument()
  })

  it('shows the active account in the switcher', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('ORGANIZATION', 'ADMIN'))
    expect(screen.getByRole('button', { name: 'Account: Acme' })).toBeInTheDocument()
    expect(screen.getByText('Private organization · Admin')).toBeInTheDocument()
  })

  it('shows Platform only to the Super Admin', () => {
    expect(renderSidebar(makeSnapshot('PERSONAL')).queryByRole('link', { name: 'Platform' })).not.toBeInTheDocument()
    cleanup()
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'USER', { platformRole: 'SUPER_ADMIN' }))
    expect(nav.getByRole('link', { name: 'Platform' })).toHaveAttribute('href', '/admin/listings')
  })

  it('marks public organizations and the Super Admin in the account switcher', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />,
      makeSnapshot('ORGANIZATION', 'OWNER', { platformRole: 'SUPER_ADMIN', publicWorkspaceIds: ['ws-acme'] }))
    expect(screen.getByText('Public')).toBeInTheDocument()
    expect(screen.getByText('Public organization · Owner')).toBeInTheDocument()
    expect(screen.getByText('Super Admin')).toBeInTheDocument()
  })

  it('calls an unlisted organization private', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('ORGANIZATION', 'ADMIN'))
    expect(screen.getByText('Private organization · Admin')).toBeInTheDocument()
    expect(screen.queryByText('Super Admin')).not.toBeInTheDocument()
  })
})
