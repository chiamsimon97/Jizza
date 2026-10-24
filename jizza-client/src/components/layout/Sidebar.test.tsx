import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { Sidebar } from './Sidebar'

function renderSidebar(snapshot = makeSnapshot()) {
  renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, snapshot)
  return within(screen.getByRole('navigation'))
}

describe('Sidebar', () => {
  it('shows the personal sections, with Connectors, for a Personal account', () => {
    const nav = renderSidebar(makeSnapshot('PERSONAL'))
    for (const label of ['Chat', 'Notifications', 'Agents', 'Memory', 'Tasks', 'Workflows', 'Functions', 'Procedures',
      'Connectors', 'Guardrails', 'Activity', 'Usage', 'Security']) {
      expect(nav.getByRole('link', { name: label })).toBeInTheDocument()
    }
    expect(nav.queryByRole('link', { name: 'Members' })).not.toBeInTheDocument()
    expect(nav.queryByText('Organization')).not.toBeInTheDocument()
  })

  it('leaves Connectors out of a Personal account without connector_view', () => {
    const nav = renderSidebar(makeSnapshot('PERSONAL', 'OWNER', { permissions: [] }))
    expect(nav.getByRole('link', { name: 'Chat' })).toBeInTheDocument()
    expect(nav.queryByRole('link', { name: 'Connectors' })).not.toBeInTheDocument()
  })

  it('shows only the Organization section, Notifications and Security for an Organization Owner', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'OWNER'))
    expect(nav.getByText('Organization')).toBeInTheDocument()
    for (const label of ['Members', 'Connectors', 'Guardrails', 'Notifications', 'Security']) {
      expect(nav.getByRole('link', { name: label })).toBeInTheDocument()
    }
    for (const label of ['Chat', 'Agents', 'Memory', 'Tasks', 'Workflows', 'Procedures', 'Activity', 'Usage']) {
      expect(nav.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
  })

  it('hides Connectors from an organization User', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'USER'))
    expect(nav.getByRole('link', { name: 'Members' })).toBeInTheDocument()
    expect(nav.queryByRole('link', { name: 'Connectors' })).not.toBeInTheDocument()
  })

  it('shows the active account in the switcher', () => {
    renderWithSnapshot(<Sidebar collapsed={false} onToggleCollapsed={() => {}} />, makeSnapshot('ORGANIZATION', 'ADMIN'))
    expect(screen.getByRole('button', { name: 'Account: Acme' })).toBeInTheDocument()
    expect(screen.getByText('Organization · Admin')).toBeInTheDocument()
  })

  it('shows Discover in a Personal account', () => {
    expect(renderSidebar(makeSnapshot('PERSONAL')).getByRole('link', { name: 'Discover' })).toBeInTheDocument()
  })

  it('shows Public listing to an organization Owner', () => {
    expect(renderSidebar(makeSnapshot('ORGANIZATION', 'OWNER')).getByRole('link', { name: 'Public listing' })).toBeInTheDocument()
  })

  it('hides Public listing from a Developer', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'DEVELOPER'))
    expect(nav.getByRole('link', { name: 'Discover' })).toBeInTheDocument()
    expect(nav.queryByRole('link', { name: 'Public listing' })).not.toBeInTheDocument()
  })

  it('shows Listing reviews only to the Super Admin', () => {
    expect(renderSidebar(makeSnapshot('PERSONAL')).queryByRole('link', { name: 'Listing reviews' })).not.toBeInTheDocument()
  })

  it('shows Listing reviews to the Super Admin in any account', () => {
    const nav = renderSidebar(makeSnapshot('ORGANIZATION', 'USER', { platformRole: 'SUPER_ADMIN' }))
    expect(nav.getByText('Platform')).toBeInTheDocument()
    expect(nav.getByRole('link', { name: 'Listing reviews' })).toBeInTheDocument()
  })
})
