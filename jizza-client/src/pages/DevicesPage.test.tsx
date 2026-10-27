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
    expect(screen.getByText(/kept in a file rather than secure hardware/)).toBeInTheDocument()
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
  })

  it('reports a test connection round trip', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop])
    vi.spyOn(devicesApi, 'enrollmentTokens').mockResolvedValue([])
    vi.spyOn(devicesApi, 'test').mockResolvedValue(command({}))
    renderWithSnapshot(<DevicesPage />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: /Test/ }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('It answered in 120 ms.'))
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
