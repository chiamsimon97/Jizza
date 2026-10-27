import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { mcpApi } from '@/api/mcp'
import { agentsApi } from '@/api/agents'
import type { AgentDto, McpCatalogEntry, McpServer } from '@/types'
import { McpServersSection } from './McpServers'

const gmail: McpCatalogEntry = {
  id: 'gmail', name: 'Gmail', publisher: 'Google', category: 'personal', description: 'Search your inbox.',
  url: 'https://gmailmcp.googleapis.com/mcp/v1', authType: 'OAUTH', ownClient: true,
  oauthScopes: 'https://www.googleapis.com/auth/gmail.readonly', agentKeys: ['jizza', 'professional'],
  note: 'Google asks for an OAuth client of your own.',
}
const kite: McpCatalogEntry = {
  id: 'kite', name: 'Zerodha Kite', publisher: 'Zerodha', category: 'finance', description: 'Holdings and orders.',
  url: 'https://mcp.kite.trade/mcp', authType: 'NONE', ownClient: false, agentKeys: ['jizza', 'finance'],
}
const installedKite = {
  id: 'srv-1', name: 'Zerodha Kite', slug: 'zerodha_kite', url: 'https://mcp.kite.trade/mcp', authType: 'NONE',
  secretPresent: false, signedIn: false, enabled: true, agentKeys: ['jizza'], redactArguments: false,
  status: 'connected', tools: [],
} as McpServer
const agents = [
  { id: 'a1', key: 'jizza', name: 'Jizza', orchestrator: true, status: 'active' },
  { id: 'a2', key: 'professional', name: 'Cato', orchestrator: false, status: 'active' },
] as AgentDto[]

describe('Ready-made MCP servers', () => {
  it('lists them by category, marks installed ones, and installs one with its settings filled in', async () => {
    vi.spyOn(mcpApi, 'list').mockResolvedValue([installedKite])
    vi.spyOn(mcpApi, 'catalog').mockResolvedValue({ entries: [gmail, kite], redirectUri: 'https://jizza.example/api/mcp/oauth/callback' })
    vi.spyOn(agentsApi, 'list').mockResolvedValue(agents)
    const create = vi.spyOn(mcpApi, 'create').mockResolvedValue({ ...installedKite, id: 'srv-2', name: 'Gmail' })
    renderWithSnapshot(<McpServersSection />, makeSnapshot('ORGANIZATION', 'OWNER'))

    await screen.findByText('Gmail')
    fireEvent.click(screen.getByRole('button', { name: /Finance and investing/ }))
    expect(screen.queryByText('Gmail')).not.toBeInTheDocument()
    expect(screen.getByText('Installed')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^All/ }))

    const card = screen.getByText('Search your inbox.').closest('div.flex.flex-col') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: /Install/ }))
    await screen.findByText('Install Gmail')
    expect(screen.getByText('https://jizza.example/api/mcp/oauth/callback')).toBeInTheDocument()
    expect(screen.getByLabelText(/Server URL/)).toHaveValue('https://gmailmcp.googleapis.com/mcp/v1')
    fireEvent.change(screen.getByLabelText(/^Client id/), { target: { value: 'cid' } })
    fireEvent.change(screen.getByLabelText(/^Client secret/), { target: { value: 'csecret' } })
    fireEvent.click(screen.getAllByRole('button', { name: 'Install' }).find(b => b.getAttribute('type') === 'submit')!)

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({
      name: 'Gmail', url: 'https://gmailmcp.googleapis.com/mcp/v1', authType: 'OAUTH',
      oauthClientId: 'cid', oauthClientSecret: 'csecret',
      oauthScopes: 'https://www.googleapis.com/auth/gmail.readonly', agentKeys: ['jizza', 'professional'],
    })
  })
})
