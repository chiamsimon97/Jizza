import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { connectorHubApi } from '@/api/connectorHub'
import { servicesApi } from '@/api/services'
import { agentsApi } from '@/api/agents'
import { mcpApi } from '@/api/mcp'
import type { AgentDto, ConnectorCard, McpServer, McpTool } from '@/types'
import { ConnectorsPage } from './ConnectorsPage'
import { ToolTypesReview, groupOf, reviewable } from './McpServers'
import { lastUsed, toolSummary } from './ConnectorHub'

const agents = [
  { id: 'a1', key: 'jizza', name: 'Jizza', orchestrator: true, status: 'active' },
  { id: 'a2', key: 'finance', name: 'Kai', orchestrator: false, status: 'active' },
] as AgentDto[]

const zoho: ConnectorCard = {
  kind: 'service', id: 's1', name: 'Zoho', detail: 'CRM', status: 'ready', toolCount: 3,
  types: { read: 2, change: 1, money: 0, unset: 0 }, agentKeys: ['jizza'], partial: false,
  lastUsedAt: null, path: '/connectors/s1',
}
const kite: ConnectorCard = {
  kind: 'mcp', id: 'm1', name: 'Kite', detail: 'https://mcp.kite.trade/mcp', status: 'needs_sign_in',
  problem: 'Sign in again after Jizza restarted.', toolCount: 3, types: { read: 2, change: 0, money: 1, unset: 0 },
  agentKeys: ['jizza', 'finance'], partial: false, lastUsedAt: null, path: '/connectors/mcp/m1',
}
const laptop: ConnectorCard = {
  kind: 'device', id: 'd1', name: 'Work laptop', detail: 'windows', status: 'ready', toolCount: 12,
  types: { read: 8, change: 4, money: 0, unset: 0 }, agentKeys: ['jizza'], partial: false,
  lastUsedAt: null, path: '/settings/devices?device=d1',
}

afterEach(() => vi.restoreAllMocks())

function renderHub(route = '/connectors') {
  vi.spyOn(connectorHubApi, 'list').mockResolvedValue([zoho, kite, laptop])
  vi.spyOn(agentsApi, 'list').mockResolvedValue(agents)
  return renderWithSnapshot(<ConnectorsPage />, makeSnapshot('PERSONAL', 'OWNER'), route)
}

describe('Connectors hub', () => {
  it('shows services and servers as one kind of card, the ones that need you first', async () => {
    renderHub()
    const names = (await screen.findAllByText(/^(Zoho|Kite)$/)).map(n => n.textContent)
    expect(names).toEqual(['Kite', 'Zoho'])
    expect(screen.getByText('Needs sign-in')).toBeInTheDocument()
    expect(screen.getByText('Sign in again after Jizza restarted.')).toBeInTheDocument()
    expect(screen.getByText('3 actions: 2 read, 1 change things')).toBeInTheDocument()
    expect(await screen.findByText('Jizza, Kai')).toBeInTheDocument()
    // Devices have their own tab.
    expect(screen.queryByText('Work laptop')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Devices' }))
    expect(await screen.findByText('Work laptop')).toBeInTheDocument()
  })

  it('gives a connector to another agent from its card', async () => {
    const set = vi.spyOn(connectorHubApi, 'setAgents').mockResolvedValue({ ...zoho, agentKeys: ['jizza', 'finance'] })
    renderHub()
    const card = (await screen.findByText('Zoho')).closest('div.flex.flex-col') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: 'Change' }))
    fireEvent.click(within(card).getByLabelText('Kai'))
    await waitFor(() => expect(set).toHaveBeenCalledWith('service', 's1', ['jizza', 'finance']))
    expect(await within(card).findByText('Jizza, Kai')).toBeInTheDocument()
  })

  it('tests a connector and says how it went', async () => {
    vi.spyOn(servicesApi, 'testAuth').mockResolvedValue({ ok: false, httpStatus: 401, message: '' })
    renderHub()
    const card = (await screen.findByText('Zoho')).closest('div.flex.flex-col') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: /Test/ }))
    expect(await within(card).findByRole('status')).toHaveTextContent('It answered with an error (HTTP 401).')
  })

  it('opens on the tab in the link', async () => {
    vi.spyOn(mcpApi, 'list').mockResolvedValue([])
    vi.spyOn(mcpApi, 'catalog').mockResolvedValue({ entries: [], redirectUri: '' })
    renderHub('/connectors?tab=build')
    expect(await screen.findByText('A service from any HTTP API')).toBeInTheDocument()
    expect(screen.getByText('An MCP server by its URL')).toBeInTheDocument()
  })

  it('puts tool counts and last use in words', () => {
    expect(toolSummary({ ...kite, types: { read: 0, change: 0, money: 1, unset: 2 } })).toBe('3 tools: 1 moves money, 2 not typed yet')
    expect(toolSummary({ ...kite, toolCount: 0 })).toBe('No tools yet')
    expect(lastUsed(null)).toBe('Not used yet')
    expect(lastUsed(new Date(Date.now() - 3 * 3600_000).toISOString())).toBe('Used 3 h ago')
  })
})

const tool = (name: string, actionType: McpTool['actionType'], suggested = true): McpTool =>
  ({ name, toolName: `mcp_kite__${name}`, enabled: true, actionType, suggested, inputSchema: {} }) as McpTool

const server = {
  id: 'm1', name: 'Kite', slug: 'kite', url: 'https://mcp.kite.trade/mcp', authType: 'NONE', secretPresent: false,
  signedIn: false, enabled: true, agentKeys: ['jizza'], redactArguments: false, status: 'connected',
  tools: [tool('get_holdings', 'READ'), tool('get_quote', 'READ'), tool('place_order', 'MONEY'), tool('mystery', null, false), tool('settled', 'READ', false)],
} as McpServer

describe('Reviewing a new server\'s tool types', () => {
  it('groups what Jizza guessed and accepts them all in one press', async () => {
    const save = vi.spyOn(mcpApi, 'updateToolTypes').mockResolvedValue(server)
    const onSaved = vi.fn()
    renderWithSnapshot(<ToolTypesReview server={server} open onClose={() => {}} onSaved={onSaved} />)
    expect(screen.getByText('These 2 look like reads')).toBeInTheDocument()
    expect(screen.getByText('This one moves money')).toBeInTheDocument()
    expect(screen.getByText("Jizza couldn't tell what this one does")).toBeInTheDocument()
    // A type the person already chose is not up for review.
    expect(screen.queryByText('settled')).not.toBeInTheDocument()
    expect(screen.getByText('1 left without a type is treated as moving money.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Accept all' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('m1', { get_holdings: 'READ', get_quote: 'READ', place_order: 'MONEY' }))
    expect(onSaved).toHaveBeenCalled()
  })

  it('saves a changed type with the rest', async () => {
    const save = vi.spyOn(mcpApi, 'updateToolTypes').mockResolvedValue(server)
    renderWithSnapshot(<ToolTypesReview server={server} open onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(screen.getByLabelText('What mystery does'), { target: { value: 'UPDATE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save these types' }))
    await waitFor(() => expect(save.mock.calls[0][1]).toMatchObject({ mystery: 'UPDATE', place_order: 'MONEY' }))
  })

  it('knows which tools need a look', () => {
    expect(reviewable(server).map(t => t.name)).toEqual(['get_holdings', 'get_quote', 'place_order', 'mystery'])
    expect([groupOf('DRAFT'), groupOf('SEND'), groupOf('MONEY'), groupOf(null)]).toEqual(['read', 'change', 'money', 'unset'])
  })
})
