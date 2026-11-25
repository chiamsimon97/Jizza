import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { screen, within } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { HubLayout } from './HubLayout'
import { SettingsLayout } from '@/pages/settings/SettingsLayout'

function renderAt(route: string, snapshot = makeSnapshot('PERSONAL')) {
  renderWithSnapshot(
    <Routes>
      <Route element={<HubLayout hub="build" />}>
        <Route path="/workflows" element={<p>workflows page</p>} />
        <Route path="/recordings" element={<p>recordings page</p>} />
      </Route>
      <Route element={<SettingsLayout />}>
        <Route path="/connectors" element={<p>connectors page</p>} />
        <Route path="/settings/general" element={<p>general page</p>} />
      </Route>
    </Routes>,
    snapshot, route,
  )
}

describe('HubLayout', () => {
  it('shows the Build tabs above the open page and marks the open one', () => {
    renderAt('/recordings')
    const tabs = within(screen.getByRole('navigation', { name: 'Build' }))
    expect(tabs.getAllByRole('link').map(l => l.textContent)).toEqual(['Workflows', 'Recordings', 'Forms', 'Functions', 'Procedures'])
    expect(tabs.getByRole('link', { name: 'Recordings' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('recordings page')).toBeInTheDocument()
  })
})

describe('SettingsLayout', () => {
  it('lists the configuration pages for a Personal account', () => {
    renderAt('/connectors')
    const nav = within(screen.getByRole('navigation', { name: 'Settings' }))
    expect(nav.getAllByRole('link').map(l => l.textContent))
      .toEqual(['General', 'Security', 'AI models', 'Devices', 'Connectors', 'Guardrails', 'Usage'])
    expect(nav.getByRole('link', { name: 'Connectors' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('connectors page')).toBeInTheDocument()
  })

  it('shows the organization guardrails and no Usage for an Organization', () => {
    renderAt('/settings/general', makeSnapshot('ORGANIZATION', 'OWNER'))
    const nav = within(screen.getByRole('navigation', { name: 'Settings' }))
    expect(nav.getByRole('link', { name: 'Guardrails' })).toHaveAttribute('href', '/organization/guardrails')
    expect(nav.queryByRole('link', { name: 'Usage' })).not.toBeInTheDocument()
  })
})
