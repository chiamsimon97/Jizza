import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const liveView = vi.fn()
const livePicture = vi.fn()
vi.mock('@/api/runs', () => ({
  runsApi: { liveView: (id: string) => liveView(id), livePicture: (id: string, c: string) => livePicture(id, c) },
}))

import { LiveView, takeLivePicture } from './LiveView'

describe('live view', () => {
  beforeEach(() => {
    liveView.mockReset()
    livePicture.mockReset()
  })

  it('follows a picture that is still being taken', async () => {
    liveView.mockResolvedValue({ status: 'pending', commandId: 'c1' })
    livePicture.mockResolvedValue({ status: 'ready', device: 'd1', file: 'f1' })
    expect(await takeLivePicture('r1')).toEqual({ status: 'ready', device: 'd1', file: 'f1' })
    expect(livePicture).toHaveBeenCalledWith('r1', 'c1')
  })

  it('shows the picture only once asked, and how to take over', async () => {
    liveView.mockResolvedValue({ status: 'ready', device: 'd1', file: 'f1' })
    render(<LiveView runId="r1" />)
    expect(liveView).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /watch the browser/i }))
    await waitFor(() => expect(screen.getByRole('img').getAttribute('src')).toBe('/api/devices/d1/files/f1'))
    expect(screen.getByText(/pause automation/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /stop watching/i }))
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('says when the browser cannot show the page', async () => {
    liveView.mockResolvedValue({ status: 'failed', problem: 'The browser is offline' })
    render(<LiveView runId="r1" />)
    fireEvent.click(screen.getByRole('button', { name: /watch the browser/i }))
    expect(await screen.findByText('The browser is offline')).toBeTruthy()
  })
})
