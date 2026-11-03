import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { DevicesPage, ago, explain } from './DevicesPage'
import { devicesApi } from '@/api/devices'
import type { Device, DeviceCommand } from '@/types'

const laptop: Device = {
  id: 'd1', workspaceId: 'w1', ownerUserId: 'user-1', name: 'Work laptop', platform: 'WINDOWS',
  osVersion: '11', appVersion: '0.1.0', protocolVersion: 1, status: 'ACTIVE', tags: ['office'],
  keyStorage: 'file', online: true, state: 'idle', capabilities: {}, actions: ['system.info@1', 'browser.open@1'],
  shellEnabled: false, blockedActions: [], lastSeenAt: new Date().toISOString(), createdAt: new Date().toISOString(),
  tools: [], items: {},
}

const command = (over: Partial<DeviceCommand>): DeviceCommand => ({
  id: 'c1', deviceId: 'd1', deviceName: 'Work laptop', userId: 'user-1', runId: null, nodeId: null,
  action: 'system.ping', actionVersion: 1, input: {}, status: 'SUCCEEDED', output: { pong: true },
  errorCode: null, error: null, progress: null, files: [], deadlineAt: '', createdAt: '2026-10-27T10:00:00Z',
  dispatchedAt: null, acceptedAt: null, finishedAt: '2026-10-27T10:00:00.120Z', ...over,
})

describe('Devices', () => {
  it('lists paired devices with their state and warns about a key kept in a file', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    expect(await screen.findByText('Work laptop')).toBeInTheDocument()
    expect(screen.getByText('Online')).toBeInTheDocument()
    expect(screen.getByText('office')).toBeInTheDocument()
    expect(screen.getByText(/kept outside secure hardware/)).toBeInTheDocument()
    // Someone who manages devices also sees enrollment tokens.
    expect(screen.getByText('Enrollment tokens')).toBeInTheDocument()
  })

  it('shows the pairing code when pairing', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'pair').mockResolvedValue({
      code: '482913', expiresAt: new Date(Date.now() + 600_000).toISOString(), qrPayload: 'jizza://pair?code=482913&server=x',
    })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click((await screen.findAllByRole('button', { name: /Pair a device/ }))[0])
    expect(await screen.findByLabelText('Pairing code')).toHaveTextContent('482913')
    fireEvent.click(screen.getByRole('tab', { name: /Browser/ }))
    expect(screen.getByText(/Load unpacked for testing/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy installation key' })).toBeInTheDocument()
  })

  it('lets the person who paired a phone choose what the Jizza app on it may do', async () => {
    const pixel: Device = { ...laptop, id: 'p1', name: 'Pixel', platform: 'ANDROID', keyStorage: 'keystore' }
    vi.spyOn(devicesApi, 'list').mockResolvedValue([pixel])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'phoneAccess').mockResolvedValue({ access: 'APPROVE' })
    const set = vi.spyOn(devicesApi, 'setPhoneAccess').mockResolvedValue({ access: 'FULL' })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: /Read and approve/ })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: /Full/ }))
    await waitFor(() => expect(set).toHaveBeenCalledWith('p1', 'FULL'))
  })

  it('shows no phone access for a computer', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    const read = vi.spyOn(devicesApi, 'phoneAccess').mockResolvedValue({ access: 'APPROVE' })
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }))
    expect(screen.queryByRole('radiogroup', { name: 'Phone access' })).not.toBeInTheDocument()
    expect(read).not.toHaveBeenCalled()
  })

  it('reports a test connection round trip', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'test').mockResolvedValue(command({}))
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: /Test/ }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('It answered in 120 ms.'))
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
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }))
    expect(screen.getByText('Tools its app added')).toBeInTheDocument()
    expect(screen.getByText(/Saved on it: 1 recordings/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('What Export ledger does'), { target: { value: 'READ' } })
    await waitFor(() => expect(update).toHaveBeenCalledWith('d1', 'x-acme.export', 'READ'))
  })

  it('hides pairing and tokens without the permissions', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([])
    renderWithSnapshot(<DevicesPage />, makeSnapshot('PERSONAL', 'OWNER', { permissions: ['device_view'] }))
    expect(await screen.findByText('No devices yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pair a device/ })).toBeNull()
    expect(screen.queryByText('Enrollment tokens')).toBeNull()
  })
})

describe('device words', () => {
  it('explains why a command did not finish', () => {
    expect(explain(command({ status: 'EXPIRED', errorCode: 'device_offline' }))).toBe('It was not reachable in time')
    expect(explain(command({ status: 'REJECTED', error: 'Not allowed here' }))).toBe('It refused: Not allowed here')
  })

  it('says how long ago', () => {
    const now = Date.parse('2026-10-27T12:00:00Z')
    expect(ago(null, now)).toBe('never')
    expect(ago('2026-10-27T11:58:00Z', now)).toBe('2 min ago')
    expect(ago('2026-10-25T12:00:00Z', now)).toBe('2 d ago')
  })
})
