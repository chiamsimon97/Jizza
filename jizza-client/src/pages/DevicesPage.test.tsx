import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { DevicesPage, ago, explain, matches } from './DevicesPage'
import { devicesApi } from '@/api/devices'
import type { Device, DeviceCommand } from '@/types'

const laptop: Device = {
  id: 'd1', workspaceId: 'w1', ownerUserId: 'user-1', name: 'Work laptop', platform: 'WINDOWS',
  osVersion: '11', appVersion: '0.1.0', protocolVersion: 1, status: 'ACTIVE', tags: ['office'],
  keyStorage: 'file', online: true, state: 'idle', capabilities: {}, actions: ['system.info@1', 'browser.open@1'],
  shellEnabled: false, blockedActions: [], lastSeenAt: new Date().toISOString(), createdAt: '2026-10-28T12:03:00Z',
  hostname: 'chiam-9790', autoUpdate: true, createdBy: 'user-1', createdByEmail: 'chiam@example.com',
  updatedAt: '2026-10-28T12:03:00Z',
  tools: [], items: {},
}

const command = (over: Partial<DeviceCommand>): DeviceCommand => ({
  id: 'c1', deviceId: 'd1', deviceName: 'Work laptop', userId: 'user-1', runId: null, nodeId: null,
  action: 'system.ping', actionVersion: 1, input: {}, status: 'SUCCEEDED', output: { pong: true },
  errorCode: null, error: null, progress: null, files: [], deadlineAt: '', createdAt: '2026-10-27T10:00:00Z',
  dispatchedAt: null, acceptedAt: null, finishedAt: '2026-10-27T10:00:00.120Z', ...over,
})

describe('Devices', () => {
  it('lists agents with their type, host, switch and status', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    expect(await screen.findByText('Work laptop')).toBeInTheDocument()
    expect(screen.getByText('Desktop agent')).toBeInTheDocument()
    expect(screen.getByText('chiam-9790')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Disable Work laptop' })).toHaveAttribute('aria-checked', 'true')
    // Someone who manages devices also sees enrollment tokens.
    expect(screen.getByText('Enrollment tokens')).toBeInTheDocument()
  })

  it('filters agents with the search box', async () => {
    const phone: Device = { ...laptop, id: 'd2', name: 'Pixel', platform: 'ANDROID', hostname: null, tags: [] }
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop, phone])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    expect(await screen.findAllByText('Pixel')).not.toHaveLength(0)
    fireEvent.change(screen.getByLabelText('Search agents'), { target: { value: 'chiam' } })
    expect(screen.queryByText('Phone agent')).toBeNull()
    expect(screen.getByText('Work laptop')).toBeInTheDocument()
  })

  it('offers the browser extension as a tab of Create agent', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'installers').mockResolvedValue({})
    vi.spyOn(devicesApi, 'pairing').mockResolvedValue({ id: 'p1', expiresAt: '', used: false, expired: false, device: null })
    vi.spyOn(devicesApi, 'pair').mockResolvedValue({
      id: 'p1', code: '482913', expiresAt: new Date(Date.now() + 600_000).toISOString(), qrPayload: 'jizza://pair?code=482913&server=x',
    })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click((await screen.findAllByRole('button', { name: /Create agent/ }))[0])
    fireEvent.click(await screen.findByRole('tab', { name: /Browser/ }))
    expect(screen.getByText(/Load unpacked/)).toBeInTheDocument()
    expect(await screen.findByText(/browser extension is not published yet/)).toBeInTheDocument()
    expect(await screen.findByLabelText('Installation key')).toHaveValue('jizza://pair?code=482913&server=x')
  })

  it('lets the person who paired a phone choose what the Jizza app on it may do', async () => {
    const pixel: Device = { ...laptop, id: 'p1', name: 'Pixel', platform: 'ANDROID', keyStorage: 'keystore' }
    vi.spyOn(devicesApi, 'list').mockResolvedValue([pixel])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'phoneAccess').mockResolvedValue({ access: 'APPROVE' })
    const set = vi.spyOn(devicesApi, 'setPhoneAccess').mockResolvedValue({ access: 'FULL' })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'View details' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: /Read and approve/ })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: /Full/ }))
    await waitFor(() => expect(set).toHaveBeenCalledWith('p1', 'FULL'))
  })

  it('shows no phone access for a computer', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    const read = vi.spyOn(devicesApi, 'phoneAccess').mockResolvedValue({ access: 'APPROVE' })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'View details' }))
    expect(screen.queryByRole('radiogroup', { name: 'Phone access' })).not.toBeInTheDocument()
    expect(read).not.toHaveBeenCalled()
  })

  it('opens Create agent with an installation key to copy', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'installers').mockResolvedValue({ android: 'https://example.com/apk' })
    vi.spyOn(devicesApi, 'pairing').mockResolvedValue({ id: 'p1', expiresAt: '', used: false, expired: false, device: null })
    vi.spyOn(devicesApi, 'pair').mockResolvedValue({
      id: 'p1', code: '482913', expiresAt: new Date(Date.now() + 600_000).toISOString(), qrPayload: 'jizza://pair?code=482913&server=x',
    })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click((await screen.findAllByRole('button', { name: /Create agent/ }))[0])
    expect(await screen.findByLabelText('Installation key')).toHaveValue('jizza://pair?code=482913&server=x')
    expect(screen.getByText("Don't share this key with others.")).toBeInTheDocument()
    expect(screen.getByLabelText('Pairing code')).toHaveTextContent('482913')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    fireEvent.click(screen.getByRole('tab', { name: /Linux/ }))
    expect(screen.getByText('chmod 755 JizzaAgent_Linux.bin')).toBeInTheDocument()
    expect(await screen.findByText(/desktop installer is not published yet/)).toBeInTheDocument()
  })

  it('moves on to naming once the agent has used the key', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'installers').mockResolvedValue({})
    vi.spyOn(devicesApi, 'pairing').mockResolvedValue({ id: 'p1', expiresAt: '', used: true, expired: false, device: laptop })
    vi.spyOn(devicesApi, 'pair').mockResolvedValue({
      id: 'p1', code: '482913', expiresAt: new Date(Date.now() + 600_000).toISOString(), qrPayload: 'jizza://pair?code=482913',
    })
    const update = vi.spyOn(devicesApi, 'update').mockResolvedValue({ ...laptop, name: 'Office Mac' })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click((await screen.findAllByRole('button', { name: /Create agent/ }))[0])
    expect(await screen.findByText(/Work laptop on chiam-9790 is connected/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: 'Office Mac' } })
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('d1', { name: 'Office Mac' }))
  })

  it('pings an agent and reports the round trip', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'test').mockResolvedValue(command({}))
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Ping' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Work laptop answered in 120 ms.'))
  })

  it('shows agent details and turns auto-update off', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'commands').mockResolvedValue([])
    vi.spyOn(devicesApi, 'logs').mockResolvedValue([])
    const update = vi.spyOn(devicesApi, 'update').mockResolvedValue({ ...laptop, autoUpdate: false })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'View details' }))
    const dialog = await screen.findByRole('dialog', { name: 'Agent details' })
    expect(dialog).toHaveTextContent('chiam@example.com')
    expect(dialog).toHaveTextContent('0.1.0')
    expect(dialog).toHaveTextContent(/kept outside secure hardware/)
    expect(dialog).toHaveTextContent('Unregister will disconnect the agent permanently')
    fireEvent.click(screen.getByRole('switch', { name: 'Auto-update' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('d1', { autoUpdate: false }))
  })

  it('lets the owner say what a tool the app added does', async () => {
    const tool = {
      action: 'x-acme.export', version: 1, capability: 'x-acme', actionType: 'MONEY' as const, title: 'Export ledger',
      description: 'Exports the ledger', input: { type: 'object' }, output: null, conditions: [], platforms: [], defaultTimeoutSec: 120,
    }
    const withTool: Device = { ...laptop, actions: [...laptop.actions, 'x-acme.export@1'], tools: [tool], items: { recordings: [{ id: 'r1', name: 'Invoices' }] } }
    vi.spyOn(devicesApi, 'list').mockResolvedValue([withTool])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    const update = vi.spyOn(devicesApi, 'updateTool').mockResolvedValue({ ...withTool, tools: [{ ...tool, actionType: 'READ' }] })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'View details' }))
    expect(screen.getByText('Tools its app added')).toBeInTheDocument()
    expect(screen.getByText(/Saved on it: 1 recordings/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('What Export ledger does'), { target: { value: 'READ' } })
    await waitFor(() => expect(update).toHaveBeenCalledWith('d1', 'x-acme.export', 'READ'))
  })

  it('hides creating agents and tokens without the permissions', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    renderWithSnapshot(<DevicesPage />, makeSnapshot('PERSONAL', 'OWNER', { permissions: ['device_view'] }))
    expect(await screen.findByText('No agents yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Create agent/ })).toBeNull()
    expect(screen.queryByText('Enrollment tokens')).toBeNull()
  })
})

describe('device words', () => {
  it('explains why a command did not finish', () => {
    expect(explain(command({ status: 'EXPIRED', errorCode: 'device_offline' }))).toBe('It was not reachable in time')
    expect(explain(command({ status: 'REJECTED', error: 'Not allowed here' }))).toBe('It refused: Not allowed here')
  })

  it('matches the search on name, host, tags and platform', () => {
    expect(matches(laptop, 'CHIAM')).toBe(true)
    expect(matches(laptop, 'office')).toBe(true)
    expect(matches(laptop, 'windows')).toBe(true)
    expect(matches(laptop, 'pixel')).toBe(false)
    expect(matches(laptop, '  ')).toBe(true)
  })

  it('says how long ago', () => {
    const now = Date.parse('2026-10-27T12:00:00Z')
    expect(ago(null, now)).toBe('never')
    expect(ago('2026-10-27T11:58:00Z', now)).toBe('2 min ago')
    expect(ago('2026-10-25T12:00:00Z', now)).toBe('2 d ago')
  })
})
