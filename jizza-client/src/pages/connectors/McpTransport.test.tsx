import { afterEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { fireEvent, screen } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { devicesApi } from '@/api/devices'
import type { Device, McpServerInput } from '@/types'
import { TransportFields } from './McpServers'

afterEach(() => vi.restoreAllMocks())

const laptop = {
  id: 'd1', name: 'Work laptop', platform: 'WINDOWS', status: 'ACTIVE', online: true,
  items: { mcp_servers: [{ id: 'files', name: 'files' }] },
} as unknown as Device
const phone = { id: 'd2', name: 'Phone', platform: 'ANDROID', status: 'ACTIVE', online: true, items: {} } as unknown as Device

let latest: McpServerInput = {}
function Harness() {
  const [value, setValue] = useState<McpServerInput>({ authType: 'BEARER' })
  latest = value
  return <TransportFields value={value} onChange={setValue} />
}

describe('How Jizza reaches an MCP server', () => {
  it('offers the computers with the app, and the servers set up on the one chosen', async () => {
    vi.spyOn(devicesApi, 'list').mockResolvedValue([laptop, phone])
    renderWithSnapshot(<Harness />, makeSnapshot('PERSONAL', 'OWNER'))
    fireEvent.change(screen.getByLabelText('How Jizza reaches it'), { target: { value: 'device' } })
    // A server on a computer signs in there, so no sign-in is sent from here.
    expect(latest).toMatchObject({ transport: 'device', authType: 'NONE' })

    const computer = await screen.findByLabelText('Computer')
    await screen.findByRole('option', { name: 'Work laptop' })
    expect(screen.queryByRole('option', { name: 'Phone' })).not.toBeInTheDocument()
    fireEvent.change(computer, { target: { value: 'd1' } })
    fireEvent.change(await screen.findByLabelText('Server on that computer'), { target: { value: 'files' } })
    expect(latest).toMatchObject({ deviceId: 'd1', deviceServer: 'files' })
  })
})
