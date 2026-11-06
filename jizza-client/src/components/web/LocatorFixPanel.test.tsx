import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import type { LocatorFix } from '@/api/web'
import type { RunTrace, TraceStep } from '@/types'

vi.mock('@/api/web', () => ({
  webApi: {
    fixes: vi.fn(),
    applyFix: vi.fn(async () => ({ workflowId: 'wf1', version: 2 })),
    dismissFix: vi.fn(async () => undefined),
    browsers: vi.fn(async () => [{ id: 'b1', name: 'Chromium browser', online: true }]),
    pick: vi.fn(async () => ({ commandId: 'pick1' })),
    pickResult: vi.fn(),
    applyPickedFix: vi.fn(async () => ({ workflowId: 'wf1', version: 3 })),
  },
}))

import { webApi } from '@/api/web'
import { LocatorFixPanel } from './LocatorFixPanel'
import { describeCandidate, errorKindWords, reviewFixLink, scoreText } from './fixModel'
import { WebFailure } from '@/components/history/RunTraceView'

const fix: LocatorFix = {
  id: 'fix1', workflowId: 'wf1', workflowName: 'Supplier invoices', nodeId: 'submit', nodeLabel: 'Click Submit order',
  status: 'open', createdAt: '2026-10-28T09:00:00Z',
  candidates: [
    { element: { label: 'Place order button', strategies: [{ by: 'role', role: 'button', name: 'Place order' }] }, score: 0.88, text: 'Place order' },
    { element: { strategies: [{ by: 'css', value: '#cancel' }] }, text: 'Cancel' },
  ],
}

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter>{children}</MemoryRouter></QueryClientProvider>)
}

afterEach(() => vi.clearAllMocks())

describe('LocatorFixPanel', () => {
  it('applies an element the person picked on the page when no suggestion is right', async () => {
    vi.mocked(webApi.fixes).mockResolvedValue([fix])
    vi.mocked(webApi.pickResult).mockResolvedValue({ status: 'done', element: { label: 'Confirm button', strategies: [{ by: 'css', value: '#confirm' }] }, preview: 'f1', pagePreview: 'f2' })
    const onApplied = vi.fn()
    wrap(<LocatorFixPanel workflowId="wf1" onApplied={onApplied} />)
    const pick = await screen.findByRole('button', { name: 'Pick on page' })
    await waitFor(() => expect(pick).toBeEnabled())
    fireEvent.click(pick)
    await waitFor(() => expect(webApi.pick).toHaveBeenCalledWith({ deviceId: 'b1', prompt: 'Click the element "Click Submit order" should use' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Use this element' }, { timeout: 3000 }))
    await waitFor(() => expect(webApi.applyPickedFix).toHaveBeenCalledWith('fix1', 'pick1'))
    expect(webApi.applyFix).not.toHaveBeenCalled()
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(fix, 3))
  })

  it('applies only the suggestion the person chose', async () => {
    vi.mocked(webApi.fixes).mockResolvedValue([fix, { ...fix, id: 'other', workflowId: 'wf2' }])
    const onApplied = vi.fn()
    wrap(<LocatorFixPanel workflowId="wf1" focusNode="submit" onApplied={onApplied} />)

    expect(await screen.findByText(/Click Submit order could not find its element/)).toBeTruthy()
    expect(screen.getAllByRole('region')).toHaveLength(1)
    expect(screen.getByText('Place order button')).toBeTruthy()
    expect(screen.getByText(/button named “Place order” · 88% match/)).toBeTruthy()
    const apply = screen.getByRole('button', { name: 'Apply fix' }) as HTMLButtonElement
    expect(apply.disabled).toBe(true)

    fireEvent.click(screen.getAllByRole('radio')[0])
    fireEvent.click(apply)

    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(fix, 2))
    expect(webApi.applyFix).toHaveBeenCalledWith('fix1', 0)
    expect(webApi.dismissFix).not.toHaveBeenCalled()
  })

  it('dismisses without changing the workflow', async () => {
    vi.mocked(webApi.fixes).mockResolvedValue([fix])
    const onApplied = vi.fn()
    wrap(<LocatorFixPanel workflowId="wf1" onApplied={onApplied} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }))

    await waitFor(() => expect(webApi.dismissFix).toHaveBeenCalledWith('fix1'))
    expect(webApi.applyFix).not.toHaveBeenCalled()
    expect(onApplied).not.toHaveBeenCalled()
  })

  it('shows nothing when the workflow has no open fix', async () => {
    vi.mocked(webApi.fixes).mockResolvedValue([{ ...fix, workflowId: 'wf2' }])
    const { container } = wrap(<LocatorFixPanel workflowId="wf1" onApplied={vi.fn()} />)
    await waitFor(() => expect(webApi.fixes).toHaveBeenCalledWith('open'))
    expect(container.textContent).toBe('')
  })
})

describe('WebFailure', () => {
  const trace = { run: { workflowId: 'wf1' } } as unknown as RunTrace
  const step = { id: 's1', nodeId: 'submit', label: 'Click Submit order', status: 'FAILED', attempts: 1 } as TraceStep

  it('says what went wrong, shows the screenshot and links to the open fix', async () => {
    vi.mocked(webApi.fixes).mockResolvedValue([fix])
    wrap(<WebFailure trace={trace} step={step}
      failure={{ kind: 'element_not_found', url: 'https://portal.example/cart', files: ['f1'], deviceId: 'd1', candidates: 2 }} />)

    expect(screen.getByText(/could not be found on the page/)).toBeTruthy()
    expect(screen.getByRole('img').getAttribute('src')).toBe('/api/devices/d1/files/f1')
    const link = await screen.findByRole('link', { name: 'Review fix' })
    expect(link.getAttribute('href')).toBe('/workflows?open=wf1&node=submit')
  })

  it('offers no fix link when nothing was suggested', () => {
    wrap(<WebFailure trace={trace} step={step} failure={{ kind: 'not_actionable', candidates: 0 }} />)
    expect(screen.getByText(/hidden, disabled or covered/)).toBeTruthy()
    expect(screen.queryByRole('link')).toBeNull()
    expect(webApi.fixes).not.toHaveBeenCalled()
  })
})

describe('fixModel', () => {
  it('puts error kinds and suggestions into words', () => {
    expect(errorKindWords('site_not_allowed')).toContain('not allowed')
    expect(errorKindWords('weird_kind')).toContain('weird kind')
    expect(describeCandidate(fix.candidates[1])).toEqual({ title: 'Cancel', how: 'css “#cancel”' })
    expect(scoreText(undefined)).toBeNull()
    expect(reviewFixLink('wf 1', 'a&b')).toBe('/workflows?open=wf%201&node=a%26b')
  })
})
