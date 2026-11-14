import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { NotificationDto } from '@/types'
import { StepApprovalForm } from './StepApprovalForm'
import { type FormValues, answerValues, initialValues, optionsFor, stepFormOf } from './stepForm'

const SHOWTIME = {
  id: 'n1', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-09T10:00:00Z',
  context: {
    node: 'step',
    form: {
      action: 'browser.clickItem', title: 'Choose from a list', choicesAt: '2026-11-09T10:00:00Z',
      fields: [
        { key: 'within.item', label: 'Card to choose in', type: 'select', value: 'INOX Nexus', required: true, missing: false, options: ['PVR Phoenix', 'INOX Nexus'] },
        { key: 'item', label: 'Item to choose', type: 'select', required: true, missing: true, options: ['11:30 AM', '07:30 PM'],
          dependsOn: 'within.item', optionsBy: { 'PVR Phoenix': ['10:00 AM', '07:00 PM'], 'INOX Nexus': ['11:30 AM', '07:30 PM'] } },
      ],
      missing: ['item'],
    },
  },
} as unknown as NotificationDto

function Card() {
  const form = stepFormOf(SHOWTIME)!
  const [values, setValues] = useState<FormValues>(() => initialValues(form))
  return (
    <>
      <StepApprovalForm form={form} values={values} onChange={(k, v) => setValues(old => ({ ...old, [k]: v }))} />
      <output data-testid="answer">{JSON.stringify(answerValues(form, values))}</output>
    </>
  )
}

const options = (label: string) =>
  Array.from((screen.getByLabelText(label) as HTMLSelectElement).options).map(o => o.textContent)

describe('a choice inside a card of another list', () => {
  it('lists the theatres, and the showtimes of the theatre chosen', () => {
    render(<Card />)
    expect(options('Card to choose in')).toEqual(['Choose one…', 'PVR Phoenix', 'INOX Nexus'])
    expect(options('Item to choose')).toEqual(['Choose one…', '11:30 AM', '07:30 PM'])

    fireEvent.change(screen.getByLabelText('Item to choose'), { target: { value: '07:30 PM' } })
    fireEvent.change(screen.getByLabelText('Card to choose in'), { target: { value: 'PVR Phoenix' } })
    // Another theatre: its own showtimes, and the one chosen before is cleared.
    expect(options('Item to choose')).toEqual(['Choose one…', '10:00 AM', '07:00 PM'])
    expect((screen.getByLabelText('Item to choose') as HTMLSelectElement).value).toBe('')

    fireEvent.change(screen.getByLabelText('Item to choose'), { target: { value: '07:00 PM' } })
    expect(JSON.parse(screen.getByTestId('answer').textContent!)).toEqual({ 'within.item': 'PVR Phoenix', item: '07:00 PM' })
  })

  it('types the item when the page gave no list for that card', () => {
    const form = stepFormOf(SHOWTIME)!
    const item = form.fields[1]
    expect(optionsFor(item, { 'within.item': 'Somewhere else' })).toBeUndefined()
    expect(optionsFor(item, { 'within.item': 'INOX Nexus' })).toEqual(['11:30 AM', '07:30 PM'])
  })

  it('waits for the theatre when the one asked for is not on the page', () => {
    const notOnPage = structuredClone(SHOWTIME) as unknown as { context: { form: { fields: { value?: string; missing?: boolean }[]; missing: string[] } } }
    notOnPage.context.form.fields[0].value = 'Rakki Cinemas'
    notOnPage.context.form.fields[0].missing = true
    notOnPage.context.form.missing = ['within.item', 'item']
    const form = stepFormOf(notOnPage as unknown as NotificationDto)!
    function Missing() {
      const [values, setValues] = useState<FormValues>(() => initialValues(form))
      return <StepApprovalForm form={form} values={values} onChange={(k, v) => setValues(old => ({ ...old, [k]: v }))} />
    }
    render(<Missing />)
    // A choice, not a text box, that waits on the theatre.
    const time = screen.getByLabelText('Item to choose') as HTMLSelectElement
    expect(time.tagName).toBe('SELECT')
    expect(time.disabled).toBe(true)
    expect(options('Item to choose')).toEqual(['Choose Card to choose in first'])

    fireEvent.change(screen.getByLabelText('Card to choose in'), { target: { value: 'INOX Nexus' } })
    expect((screen.getByLabelText('Item to choose') as HTMLSelectElement).disabled).toBe(false)
    expect(options('Item to choose')).toEqual(['Choose one…', '11:30 AM', '07:30 PM'])
  })
})

const JOURNEY = {
  id: 'n2', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-14T10:00:00Z',
  context: {
    node: 'step',
    form: {
      action: 'browser.setDate', title: 'Set date',
      fields: [
        { key: 'value', label: 'Date', type: 'date', format: 'DD/MM/YYYY', value: '20/11/2026', required: true, missing: false },
      ],
      missing: [],
    },
  },
} as unknown as NotificationDto

const DEPARTS = {
  ...JOURNEY,
  context: {
    node: 'step',
    form: {
      action: 'browser.setDate', title: 'Set date',
      fields: [
        { key: 'value', label: 'Departs', type: 'datetime', format: 'DD/MM/YYYY hh:mm A', value: '2026-11-20T18:30', required: true, missing: false },
      ],
      missing: [],
    },
  },
} as unknown as NotificationDto

function DateCard({ n }: { n: NotificationDto }) {
  const form = stepFormOf(n)!
  const [values, setValues] = useState<FormValues>(() => initialValues(form))
  return (
    <>
      <StepApprovalForm form={form} values={values} onChange={(k, v) => setValues(old => ({ ...old, [k]: v }))} />
      <output data-testid="answer">{JSON.stringify(answerValues(form, values) ?? null)}</output>
    </>
  )
}

describe('a date on the approval card', () => {
  it("shows a date picker, and how the site gets the date picked", () => {
    render(<DateCard n={JOURNEY} />)
    const picker = screen.getByLabelText('Date') as HTMLInputElement
    expect(picker.type).toBe('date')
    expect(picker.value).toBe('2026-11-20')
    expect(screen.getByTestId('date-as-sent').textContent).toBe('The site gets 20/11/2026')
    expect(JSON.parse(screen.getByTestId('answer').textContent!)).toBeNull()

    fireEvent.change(picker, { target: { value: '2026-12-01' } })
    expect(screen.getByTestId('date-as-sent').textContent).toBe('The site gets 01/12/2026')
    // The date goes as ISO; the browser writes it the site's way.
    expect(JSON.parse(screen.getByTestId('answer').textContent!)).toEqual({ value: '2026-12-01' })
  })

  it('shows a date and time picker for a format with a time', () => {
    render(<DateCard n={DEPARTS} />)
    const picker = screen.getByLabelText('Departs') as HTMLInputElement
    expect(picker.type).toBe('datetime-local')
    expect(picker.value).toBe('2026-11-20T18:30')
    expect(screen.getByTestId('date-as-sent').textContent).toBe('The site gets 20/11/2026 06:30 PM')
  })
})

const TRAINS = {
  id: 'n2', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-14T10:00:00Z',
  context: {
    node: 'step',
    form: {
      action: 'browser.clickItem', title: 'Choose from a list',
      fields: [
        { key: '_note.0', label: 'Check the running days before booking', type: 'note', required: false, missing: false },
        { key: 'item', label: 'Train', type: 'select', value: 'CDG MDU SF EXP', required: true, missing: false, options: ['CDG MDU SF EXP', 'TEJAS EXPRESS'] },
      ],
      missing: [],
      shots: { device: 'dev-1', byOption: { 'CDG MDU SF EXP': 'file-1', 'TEJAS EXPRESS': 'file-2' } },
    },
  },
} as unknown as NotificationDto

function TrainCard() {
  const form = stepFormOf(TRAINS)!
  const [values, setValues] = useState<FormValues>(() => initialValues(form))
  return <StepApprovalForm form={form} values={values} onChange={(k, v) => setValues(old => ({ ...old, [k]: v }))} />
}

describe('notes and pictures on the card', () => {
  it('shows the note as text and the picture of the item chosen', () => {
    render(<TrainCard />)
    expect(screen.getByText('Check the running days before booking')).toBeTruthy()
    const shot = () => screen.getByTestId('option-shot') as HTMLImageElement
    expect(shot().getAttribute('src')).toBe('/api/devices/dev-1/files/file-1')
    fireEvent.change(screen.getByLabelText('Train'), { target: { value: 'TEJAS EXPRESS' } })
    expect(shot().getAttribute('src')).toBe('/api/devices/dev-1/files/file-2')
  })
})
