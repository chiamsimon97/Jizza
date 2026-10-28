import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Recording } from '@/api/web'
import { RecordingReview } from './RecordingReview'

const recording: Recording = {
  id: 'r1', name: 'Invoices', status: 'review', deviceId: 'd1', startUrl: 'https://portal.example.com', stepCount: 3, updatedAt: '2026-01-01T00:00:00Z',
  steps: [
    { seq: 1, position: 1, deleted: false, step: { action: 'browser.open', label: 'Open portal', input: { url: 'https://portal.example.com' } } },
    { seq: 2, position: 2, deleted: false, step: { action: 'browser.setText', label: 'Set "Invoice number" to "INV-2041"', input: { text: 'INV-2041' } } },
    { seq: 3, position: 3, deleted: false, step: { action: 'browser.setText', label: 'Type password', input: {}, meta: { sensitive: true } } },
  ],
}

describe('RecordingReview', () => {
  it('deletes, moves and renames a step', () => {
    const onEdit = vi.fn()
    render(<RecordingReview recording={recording} onEdit={onEdit} />)
    fireEvent.click(screen.getAllByLabelText('Delete step')[1])
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'delete', seq: 2 }])
    fireEvent.click(screen.getAllByLabelText('Move down')[0])
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'move', seq: 1, toIndex: 1 }])
    fireEvent.click(screen.getAllByLabelText('Rename step')[0])
    fireEvent.change(screen.getByLabelText('Step name'), { target: { value: 'Open the portal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rename' }))
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'relabel', seq: 1, label: 'Open the portal' }])
  })

  it('makes a typed value an input with the value as its default', () => {
    const onEdit = vi.fn()
    render(<RecordingReview recording={recording} onEdit={onEdit} />)
    expect(screen.getAllByLabelText('Make this an input')).toHaveLength(2)
    fireEvent.click(screen.getAllByLabelText('Make this an input')[1])
    expect((screen.getByLabelText('Input name') as HTMLInputElement).value).toBe('invoiceNumber')
    fireEvent.click(screen.getByRole('button', { name: 'Make input' }))
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'map', seq: 2, field: 'text', input: { name: 'invoiceNumber', type: 'text', default: 'INV-2041' } }])
  })

  it('asks for a saved login on a password step and never offers it as an input', () => {
    const onEdit = vi.fn()
    render(<RecordingReview recording={recording} onEdit={onEdit} />)
    const login = screen.getByLabelText('Saved login')
    fireEvent.change(login, { target: { value: 'login-7' } })
    fireEvent.blur(login)
    expect(onEdit).toHaveBeenLastCalledWith([{ op: 'secret', seq: 3, secretRef: 'login-7' }])
  })
})
