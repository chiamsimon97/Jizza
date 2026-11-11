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
