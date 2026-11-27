import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { MemoryProposal } from '@/api/memoryReview'

const approve = vi.fn()
const dismiss = vi.fn()
const lesson: MemoryProposal = {
  id: 'p1', kind: 'lesson', status: 'open',
  summary: 'Teach Booker to wait for the seat map on example.com',
  reason: 'The seat step failed three times this week because the map had not loaded.',
  content: 'On example.com, wait for the seat map to finish loading before choosing a seat.',
  facts: [], agent: { id: 'a1', key: 'booker', name: 'Booker' }, about: 'example.com',
  evidence: [{ kind: 'step', label: 'Book seats: Choose seat', detail: 'timeout waiting for .seat', link: '/workflows?open=w1&node=n1', at: '2026-11-26T10:00:00Z', ids: null }],
  occurrences: 3, createdAt: '2026-11-27T03:00:00Z', decidedAt: null, resultRef: null,
}

vi.mock('@/api/memoryReview', () => ({
  memoryReviewApi: {
    status: () => Promise.resolve({ enabled: true, last: null, open: 1, nextRunAt: '2026-11-28T03:00:00Z' }),
    proposals: () => Promise.resolve([lesson]),
    settings: () => Promise.resolve({ autoLearn: true, retentionDays: null, review: true }),
    run: vi.fn(),
    setReview: vi.fn(),
    approve: (id: string, content?: string) => approve(id, content),
    dismiss: (id: string) => dismiss(id),
  },
}))

import { MemoryReviewPanel } from './MemoryReviewPanel'

describe('MemoryReviewPanel', () => {
  beforeEach(() => { approve.mockReset(); dismiss.mockReset() })

  it('shows a lesson with who learns it, how often it went wrong and what it is based on', async () => {
    renderWithSnapshot(<MemoryReviewPanel open onClose={() => {}} />, makeSnapshot())
    expect(await screen.findByText(lesson.summary)).toBeInTheDocument()
    expect(screen.getByText(/for Booker · about example.com/)).toBeInTheDocument()
    expect(screen.getByText('went wrong 3 times')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Based on 1 thing/ }))
    expect(screen.getByText('Book seats: Choose seat')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open/ })).toHaveAttribute('href', '/workflows?open=w1&node=n1')
  })

  it('approves the edited wording when the user changes it first', async () => {
    approve.mockResolvedValue({ ...lesson, status: 'approved' })
    renderWithSnapshot(<MemoryReviewPanel open onClose={() => {}} />, makeSnapshot())
    await screen.findByText(lesson.summary)

    fireEvent.click(screen.getByRole('button', { name: /Edit/ }))
    fireEvent.change(screen.getByLabelText('Wording to save'), { target: { value: 'Wait for the seat map first.' } })
    fireEvent.click(screen.getByRole('button', { name: /Save and approve/ }))
    await waitFor(() => expect(approve).toHaveBeenCalledWith('p1', 'Wait for the seat map first.'))
  })

  it('dismisses a suggestion', async () => {
    dismiss.mockResolvedValue({ ...lesson, status: 'dismissed' })
    renderWithSnapshot(<MemoryReviewPanel open onClose={() => {}} />, makeSnapshot())
    await screen.findByText(lesson.summary)
    fireEvent.click(screen.getByRole('button', { name: /Dismiss/ }))
    await waitFor(() => expect(dismiss).toHaveBeenCalledWith('p1'))
  })
})
