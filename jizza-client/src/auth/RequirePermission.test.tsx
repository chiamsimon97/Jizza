import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { meApi } from '@/api/me'
import { RequirePermission } from './RequirePermission'

describe('<RequirePermission>', () => {
  it('renders the page when the permission is granted', () => {
    renderWithSnapshot(
      <RequirePermission permission="connector_view"><p>Connectors page</p></RequirePermission>,
      makeSnapshot('ORGANIZATION', 'DEVELOPER'),
    )
    expect(screen.getByText('Connectors page')).toBeInTheDocument()
  })

  it('shows the No Access page naming the organization when it is not', () => {
    renderWithSnapshot(
      <RequirePermission permission="connector_view"><p>Connectors page</p></RequirePermission>,
      makeSnapshot('ORGANIZATION', 'USER'),
    )
    expect(screen.queryByText('Connectors page')).not.toBeInTheDocument()
    expect(screen.getByText("You don't have access to this area. Ask an Owner or Admin of Acme.")).toBeInTheDocument()
  })

  it('shows the No Access page for Members in a Personal account', () => {
    renderWithSnapshot(
      <RequirePermission permission="members_view"><p>Members page</p></RequirePermission>,
      makeSnapshot('PERSONAL'),
    )
    expect(screen.queryByText('Members page')).not.toBeInTheDocument()
    expect(screen.getByText('No access')).toBeInTheDocument()
  })

  it('shows a spinner while the snapshot loads', () => {
    vi.spyOn(meApi, 'getContext').mockReturnValue(new Promise(() => {}))
    renderWithSnapshot(<RequirePermission permission="connector_view"><p>Connectors page</p></RequirePermission>)
    expect(screen.getByLabelText('Loading')).toBeInTheDocument()
    expect(screen.queryByText('Connectors page')).not.toBeInTheDocument()
    expect(screen.queryByText('No access')).not.toBeInTheDocument()
  })
})
