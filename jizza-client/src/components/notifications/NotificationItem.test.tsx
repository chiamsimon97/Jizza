import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { NotificationDto } from '@/types'
import { NotificationItem, type InboxActions } from './NotificationItem'
import { webApi } from '@/api/web'

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
    approve: done, approveAlways: done, decline: done, reply: done, goBack: done, markRead: done,
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

describe('a browser asking mid-step', () => {
  const CAPTCHA = {
    id: 'n2', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-15T10:00:00Z',
    expiresAt: '2026-11-15T10:09:30Z', body: 'On Chrome: The page shows a CAPTCHA.',
    context: {
      source: 'device', kind: 'captcha',
      deviceHelp: {
        deviceId: 'd1', commandId: 'c1', kind: 'captcha',
        shot: '0b5c2f1e-6a1d-4c39-9a5e-2f9d1c7b8a01', answer: { secret: true, required: true },
      },
    },
  } as unknown as NotificationDto

  function show(n: NotificationDto, inbox: InboxActions) {
    render(
      <MemoryRouter>
        <NotificationItem n={n} now={Date.parse('2026-11-15T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
  }

  it('shows the picture and a box, and sends what is typed', async () => {
    const inbox = actions()
    show(CAPTCHA, inbox)
    expect(screen.getByText('Solve the CAPTCHA')).toBeTruthy()
    expect(screen.getByAltText('What the page shows').getAttribute('src'))
      .toBe('/api/devices/d1/files/0b5c2f1e-6a1d-4c39-9a5e-2f9d1c7b8a01')
    expect(screen.getByTestId('device-help-countdown').textContent).toMatch(/left/)
    // The ordinary Approve / Decline are not offered: this card answers the browser.
    expect(screen.queryByRole('button', { name: /^approve/i })).toBeNull()
    const send = screen.getByRole('button', { name: /send/i }) as HTMLButtonElement
    expect(send.disabled).toBe(true)
    fireEvent.change(screen.getByTestId('device-help-answer'), { target: { value: ' X7K2P ' } })
    fireEvent.click(send)
    await waitFor(() => expect(inbox.approve).toHaveBeenCalledWith(CAPTCHA, { answer: 'X7K2P' }))
  })

  it('can say it was done in the browser, or stop', () => {
    const inbox = actions()
    show(CAPTCHA, inbox)
    fireEvent.click(screen.getByRole('button', { name: /i did it in the browser/i }))
    expect(inbox.approve).toHaveBeenCalledWith(CAPTCHA, { done: true })
  })

  it('a hand-over asks only for Done', () => {
    const inbox = actions()
    const handOff = {
      ...CAPTCHA,
      context: { deviceHelp: { deviceId: 'd1', commandId: 'c1', kind: 'handoff' } },
    } as unknown as NotificationDto
    show(handOff, inbox)
    expect(screen.getByText('Your turn in the browser')).toBeTruthy()
    expect(screen.queryByTestId('device-help-answer')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /stop/i }))
    expect(inbox.decline).toHaveBeenCalledWith(handOff, 'Stopped')
  })
})

describe('a device layer card', () => {
  it('lists fixed values, shows groups, and sends the button chosen', () => {
    const inbox = actions()
    const layer = {
      ...PASSENGER,
      context: {
        node: 'layer',
        form: {
          action: 'browser.fillLayer', title: 'Device layer',
          fixed: [{ label: 'Stage', value: 'Open' }],
          fields: [
            { key: 'fields.1.value', label: 'Project', type: 'select', value: 'Jizza', required: true, missing: false, group: 'Task details', options: ['Jizza', 'Atlas'] },
            { key: 'fields.2.value', label: 'Milestone (loads after Project is chosen)', type: 'text', required: false, missing: false, group: 'Task details' },
            { key: 'button', label: 'Then click', type: 'select', required: true, missing: true, options: ['Submit', 'Cancel'] },
          ],
          missing: ['button'],
        },
      },
    } as unknown as NotificationDto
    render(
      <MemoryRouter>
        <NotificationItem n={layer} now={Date.parse('2026-11-14T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Task details')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Fixed values \(1\)/ }))
    expect(screen.getByText('Stage')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /approve/i })).toBeNull()

    fireEvent.change(screen.getByLabelText(/Then click/), { target: { value: 'Cancel' } })
    fireEvent.click(screen.getByRole('button', { name: /approve/i }))
    expect(inbox.approve).toHaveBeenCalledWith(layer, expect.objectContaining({ button: 'Cancel' }))
  })

  it('lists a dropdown from the page as the card opens, and refreshes a typed value from its suggestions', async () => {
    const inbox = actions()
    const testStep = vi.spyOn(webApi, 'testStep').mockImplementation(async body => body.action === 'browser.suggestOptions'
      ? { status: 'succeeded', output: { options: ['CHENNAI EGMORE - MS', 'MGR CHENNAI CTL - MAS'], count: 2 }, files: [] }
      : { status: 'succeeded', output: { items: [{ label: 'All Classes' }, { label: 'Sleeper (SL)' }] }, files: [] })
    const element = { strategies: [{ by: 'css', value: '#from' }] }
    const layer = {
      ...PASSENGER,
      context: {
        node: 'layer',
        form: {
          action: 'browser.fillLayer', title: 'Device layer',
          fields: [
            { key: 'fields.0.value', label: 'From', type: 'text', value: 'chen', required: true, missing: false },
            { key: 'fields.1.value', label: 'Class', type: 'text', value: 'All Classes', required: false, missing: false },
            { key: 'fields.2.value', label: 'Berth', type: 'text', value: '', required: false, missing: false },
            { key: 'button', label: 'Then click', type: 'select', value: 'Search', required: true, missing: false, options: ['Search'] },
          ],
          missing: [],
          live: {
            device: 'dev-1', session: 's_1',
            fields: {
              'fields.0.value': { action: 'browser.suggestOptions', version: 1, typed: true, element },
              'fields.1.value': { action: 'browser.listOptions', version: 1, typed: false, element },
              'fields.2.value': { action: 'browser.listOptions', version: 1, typed: false, element, follows: true },
            },
          },
        },
      },
    } as unknown as NotificationDto
    render(
      <MemoryRouter>
        <NotificationItem n={layer} now={Date.parse('2026-11-14T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
    // Class has no list on the card: it is read from the page as the card opens. Berth loads
    // after another field, so it is not.
    await waitFor(() => expect(screen.getByRole('option', { name: 'Sleeper (SL)' })).toBeInTheDocument())
    expect(testStep).toHaveBeenCalledTimes(1)
    expect(testStep).toHaveBeenCalledWith({ deviceId: 'dev-1', action: 'browser.listOptions', version: 1,
      inputs: { session: 's_1', element } })
    const [fromRefresh] = screen.getAllByRole('button', { name: 'Refresh' })
    fireEvent.click(fromRefresh!)
    await waitFor(() => expect(screen.getByRole('button', { name: 'MGR CHENNAI CTL - MAS' })).toBeInTheDocument())
    expect(testStep).toHaveBeenCalledWith({ deviceId: 'dev-1', action: 'browser.suggestOptions', version: 1,
      inputs: { session: 's_1', element, text: 'chen' } })
    fireEvent.click(screen.getByRole('button', { name: 'MGR CHENNAI CTL - MAS' }))
    expect(screen.getByLabelText('From')).toHaveValue('MGR CHENNAI CTL - MAS')

    fireEvent.change(screen.getByLabelText('Class'), { target: { value: 'Sleeper (SL)' } })
    fireEvent.click(screen.getByRole('button', { name: /approve/i }))
    expect(inbox.approve).toHaveBeenCalledWith(layer, expect.objectContaining({
      'fields.0.value': 'MGR CHENNAI CTL - MAS', 'fields.1.value': 'Sleeper (SL)' }))
    testStep.mockRestore()
  })
})

describe('a step that allows going back', () => {
  const CHECK = {
    id: 'n9', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-20T10:00:00Z', runId: 'r1',
    body: 'This step needs your approval before it asks your browser to check availability.',
    context: {
      node: 'check',
      back: [{ node: 'coach', label: 'Choose coach' }, { node: 'train', label: 'Choose train' }],
    },
  } as unknown as NotificationDto

  function show(n: NotificationDto, inbox: InboxActions) {
    render(
      <MemoryRouter>
        <NotificationItem n={n} now={Date.parse('2026-11-20T10:01:00Z')} actions={inbox}
          focused={false} onFocus={() => {}} command={null} onCommandHandled={() => {}} />
      </MemoryRouter>,
    )
  }

  it('offers Back beside Approve, to the latest step that asked, or one picked', async () => {
    const inbox = actions()
    show(CHECK, inbox)
    expect(screen.getByRole('button', { name: /approve/i })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Back to Choose coach' }))
    await waitFor(() => expect(inbox.goBack).toHaveBeenCalledWith(CHECK, 'coach'))

    fireEvent.change(screen.getByRole('combobox', { name: 'Go back to' }), { target: { value: 'train' } })
    fireEvent.click(screen.getByRole('button', { name: 'Back to Choose train' }))
    await waitFor(() => expect(inbox.goBack).toHaveBeenCalledWith(CHECK, 'train'))
  })

  it('shows no Back where the step does not allow it, or once answered', () => {
    show({ ...CHECK, context: { node: 'check' } } as unknown as NotificationDto, actions())
    expect(screen.queryByTestId('go-back')).toBeNull()
  })

  it('shows no Back on an answered item', () => {
    show({ ...CHECK, status: 'SUBMITTED' } as unknown as NotificationDto, actions())
    expect(screen.queryByTestId('go-back')).toBeNull()
  })
})
