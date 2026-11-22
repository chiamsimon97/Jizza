import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Recording } from '@/api/web'
import { RecordingReview } from './RecordingReview'

const outcomes = [
  { name: 'Results', element: { label: 'First train', strategies: [{ by: 'css', value: '.train' }] } },
  { name: 'No trains', text: 'No trains found', then: 'stop' as const, message: 'No trains that day' },
]

const recording: Recording = {
  id: 'r1', name: 'Trains', status: 'review', deviceId: 'd1', startUrl: 'https://trains.example.com', stepCount: 3, updatedAt: '2026-01-01T00:00:00Z',
  steps: [
    { seq: 1, position: 1, deleted: false, step: { action: 'browser.click', label: 'Click "Search"', input: {} } },
    { seq: 2, position: 2, deleted: false, step: { action: 'browser.waitFor', label: 'Wait for "Results"', input: { outcomes } } },
    { seq: 3, position: 3, deleted: false, step: { action: 'browser.click', label: 'Click "Pay now"', input: {}, meta: { irreversible: true } } },
  ],
}

describe('Wait until the page answers and steps that cannot be undone', () => {
  it('shows each outcome and what the run does with it', () => {
    render(<RecordingReview recording={recording} onEdit={vi.fn()} />)
    expect(screen.getByText('(First train)')).toBeInTheDocument()
    expect(screen.getByText('(the text "No trains found")')).toBeInTheDocument()
    expect(screen.getByLabelText('When "No trains" shows')).toHaveValue('stop')
    expect(screen.getByLabelText('Message for "No trains"')).toHaveValue('No trains that day')
  })

  it('stops the run on an outcome, and adds a message outcome', () => {
    const onEdit = vi.fn()
    render(<RecordingReview recording={recording} onEdit={onEdit} />)
    fireEvent.change(screen.getByLabelText('When "Results" shows'), { target: { value: 'stop' } })
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 2, patch: { outcomes: [
      { ...outcomes[0], then: 'stop' }, outcomes[1],
    ] } }])
    fireEvent.click(screen.getByText(/Add what else can happen/))
    fireEvent.change(screen.getByLabelText('Outcome name'), { target: { value: 'Sold out' } })
    fireEvent.change(screen.getByLabelText('Text the page shows'), { target: { value: 'Not available' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 2, patch: { outcomes: [
      ...outcomes, { name: 'Sold out', text: 'Not available', then: 'continue' },
    ] } }])
  })

  it('marks and unmarks a step that cannot be undone', () => {
    const onEdit = vi.fn()
    render(<RecordingReview recording={recording} onEdit={onEdit} />)
    expect(screen.getByText("Can't be undone")).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Mark as one that can be redone'))
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 3, patch: { irreversible: false } }])
    fireEvent.click(screen.getAllByLabelText("Mark as can't be undone")[0])
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 1, patch: { irreversible: true } }])
  })
})

describe('Read as', () => {
  it('reads a text as an amount, or by a pattern once one is written', () => {
    const read: Recording = { ...recording, steps: [
      { seq: 4, position: 1, deleted: false, step: { action: 'browser.getText', label: 'Fare', input: {} } },
    ] }
    const onEdit = vi.fn()
    render(<RecordingReview recording={read} onEdit={onEdit} />)
    fireEvent.change(screen.getByLabelText('Read as'), { target: { value: 'money' } })
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 4, patch: { readAs: { as: 'money' } } }])
    fireEvent.change(screen.getByLabelText('Read as'), { target: { value: 'pattern' } })
    fireEvent.change(screen.getByLabelText('Pattern'), { target: { value: 'PNR:\\s*(\\d+)' } })
    fireEvent.blur(screen.getByLabelText('Pattern'))
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'update', seq: 4, patch: { readAs: { as: 'pattern', pattern: 'PNR:\\s*(\\d+)' } } }])
  })
})
