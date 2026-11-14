import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { NotificationDto } from '@/types'
import { NotificationItem, type InboxActions } from './NotificationItem'

const PASSENGER = {
  id: 'n1', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-14T10:00:00Z',
  context: {
    node: 'step',
    form: {
      action: 'browser.fillForm', title: 'Fill the form',
      fields: [
        { key: 'fields.0.value', label: 'Passenger name', type: 'text', required: true, missing: true },
        { key: 'fields.1.value', label: 'Age', type: 'number', required: false, missing: false },
      ],
      missing: ['fields.0.value'],
    },
  },
} as unknown as NotificationDto

function actions(): InboxActions {
  const done = vi.fn().mockResolvedValue(undefined)
  return {
    approve: done, approveAlways: done, decline: done, reply: done, markRead: done,
    markUnread: done, archive: done, unarchive: done, snooze: done,
  }
}

describe('a card with a mandatory field', () => {
  it('shows Approve only once the mandatory field is filled in', () => {
    const inbox = actions()
    render(
      <MemoryRouter>
        <NotificationItem n={PASSENGER} now={Date.parse('2026-11-14T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: /approve/i })).toBeNull()
    expect(screen.getByTestId('fill-first').textContent).toContain('Passenger name')

    fireEvent.change(screen.getByLabelText(/Passenger name/), { target: { value: 'Chiam' } })

    expect(screen.queryByTestId('fill-first')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /approve/i }))
    expect(inbox.approve).toHaveBeenCalledWith(PASSENGER, expect.objectContaining({ 'fields.0.value': 'Chiam' }))
  })

  it('fills a row for each passenger in a table, and sends them as entries', () => {
    const inbox = actions()
    const rows = {
      ...PASSENGER,
      context: {
        node: 'step',
        form: {
          action: 'browser.fillRows', title: 'Fill a row for each',
          fields: [{
            key: 'rows', label: 'Passengers', type: 'rows', required: true, missing: true,
            columns: [{ key: 'name', label: 'Name' }, { key: 'age', label: 'Age' }],
          }],
          missing: ['rows'],
        },
      },
    } as unknown as NotificationDto
    render(
      <MemoryRouter>
        <NotificationItem n={rows} now={Date.parse('2026-11-14T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
    expect(screen.getByTestId('fill-first').textContent).toContain('Passengers')
    fireEvent.change(screen.getByLabelText('Name 1'), { target: { value: 'Asha' } })
    fireEvent.change(screen.getByLabelText('Age 1'), { target: { value: '34' } })
    fireEvent.click(screen.getByRole('button', { name: /add passenger/i }))
    fireEvent.change(screen.getByLabelText('Name 2'), { target: { value: 'Ravi' } })

    fireEvent.click(screen.getByRole('button', { name: /approve/i }))
    expect(inbox.approve).toHaveBeenCalledWith(rows, {
      rows: JSON.stringify([{ name: 'Asha', age: '34' }, { name: 'Ravi' }]),
    })
  })
})
