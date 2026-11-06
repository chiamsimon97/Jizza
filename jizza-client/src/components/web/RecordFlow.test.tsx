import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { webApi, type Recording } from '@/api/web'
import { RecordFlow, SetupChecklist } from './RecordFlow'
import type { BrowserAgent } from '@/api/web'

const recording = (over: Partial<Recording> = {}): Recording => ({
  id: 'r1', name: 'Invoices', status: 'recording', deviceId: 'd1', deviceName: 'Chromium browser', startUrl: 'https://portal.example.com',
  stepCount: 1, updatedAt: '2026-01-01T00:00:00Z',
  steps: [{ seq: 1, position: 1, deleted: false, step: { action: 'browser.open', label: 'Open portal', input: {} } }],
  ...over,
})

/** A stand-in for Chrome's Document Picture-in-Picture: the "window" is a detached document. */
function stubFloatingWindow() {
  const body = document.body.appendChild(document.createElement('div'))
  const doc = { body, head: document.createElement('head'), documentElement: document.createElement('html'), title: '' }
  const closed = vi.fn()
  const pipWindow = { document: doc, closed: false, close: closed, addEventListener: vi.fn() } as unknown as Window
  const requestWindow = vi.fn().mockResolvedValue(pipWindow)
  vi.stubGlobal('documentPictureInPicture', { requestWindow, window: null })
  return { doc, requestWindow, closed }
}

describe('RecordFlow floating controls', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('floats the live steps and Stop over the site while Chrome records', async () => {
    const { doc, requestWindow } = stubFloatingWindow()
    vi.spyOn(webApi, 'getRecording').mockResolvedValue(recording())
    const stop = vi.spyOn(webApi, 'stopRecording').mockResolvedValue(recording({ status: 'review' }))
    renderWithSnapshot(<RecordFlow open mode="module" recordingId="r1" onClose={() => {}} />, makeSnapshot())
    fireEvent.click(await screen.findByRole('button', { name: 'Float controls over the site' }))
    await waitFor(() => expect(requestWindow).toHaveBeenCalledWith({ width: 340, height: 420 }))
    const floating = within(doc.body)
    expect(await floating.findByText('1. Open portal')).toBeInTheDocument()
    fireEvent.click(floating.getByRole('button', { name: 'Stop recording' }))
    await waitFor(() => expect(stop).toHaveBeenCalledWith('r1'))
  })

  it('offers no floating controls where the browser cannot float a window', async () => {
    vi.spyOn(webApi, 'getRecording').mockResolvedValue(recording())
    renderWithSnapshot(<RecordFlow open mode="module" recordingId="r1" onClose={() => {}} />, makeSnapshot())
    expect(await screen.findByText('1. Open portal')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Float controls over the site' })).not.toBeInTheDocument()
  })
})

const browser = (over: Partial<BrowserAgent> = {}): BrowserAgent => ({
  id: 'b1', workspaceId: null, name: 'Chromium browser', online: true, family: 'chrome', version: '0.1.0',
  allowedSites: ['portal.example.com'], sessions: 0, ...over,
} as BrowserAgent)

describe('SetupChecklist', () => {
  it('walks a first-time person through install, pairing and the site', () => {
    renderWithSnapshot(<SetupChecklist browsers={[]} url="https://portal.example.com" />, makeSnapshot())
    expect(screen.getByRole('region', { name: 'Before you record' })).toBeInTheDocument()
    expect(screen.getAllByLabelText('To do')).toHaveLength(4)
    expect(screen.getByRole('link', { name: 'Settings › Devices' })).toHaveAttribute('href', '/settings/devices')
  })

  it('asks only for the site when the browser is ready but the site is not allowed', () => {
    const b = browser({ allowedSites: ['other.com'] })
    renderWithSnapshot(<SetupChecklist browsers={[b]} chosen={b} url="https://portal.example.com/x" />, makeSnapshot())
    expect(screen.getAllByLabelText('Done')).toHaveLength(3)
    expect(screen.getByText('Allow portal.example.com')).toBeInTheDocument()
  })

  it('hides itself once everything is set up', () => {
    const b = browser()
    renderWithSnapshot(<SetupChecklist browsers={[b]} chosen={b} url="https://portal.example.com/x" />, makeSnapshot())
    expect(screen.queryByRole('region', { name: 'Before you record' })).not.toBeInTheDocument()
  })
})
