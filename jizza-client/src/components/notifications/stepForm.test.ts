import { describe, expect, it } from 'vitest'
import type { NotificationDto } from '@/types'
import { answerValues, initialValues, mapped, stepFormOf, unfilled } from './stepForm'

function approval(form: Record<string, unknown>): NotificationDto {
  return { id: 'n1', kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: '2026-11-08T10:00:00Z', context: { node: 'step', form } }
}

const BOOKING = approval({
  action: 'browser.fillForm',
  title: 'Fill form',
  fields: [
    { key: 'fields.0.value', label: 'Name', type: 'text', value: 'Asha', required: true, missing: false, source: '{{ input.name }}' },
    { key: 'fields.1.value', label: 'Phone', type: 'text', required: true, missing: true },
    { key: 'fields.2.value', label: 'Interests', type: 'list', value: ['Music'], required: false, missing: false },
    { key: 'fields.3.value', label: 'Terms', type: 'checkbox', value: true, required: false, missing: false },
  ],
  missing: ['fields.1.value'],
  data: [{ path: 'nodes.lookup.phone', value: '98450 00000' }],
})

describe('step approval form', () => {
  it('reads the values the step resolved, in each input\'s shape', () => {
    const form = stepFormOf(BOOKING)!
    expect(form.fields.map(f => f.label)).toEqual(['Name', 'Phone', 'Interests', 'Terms'])
    expect(initialValues(form)).toEqual({ 'fields.0.value': 'Asha', 'fields.1.value': '', 'fields.2.value': ['Music'], 'fields.3.value': true })
    expect(stepFormOf(approval({ fields: [] }))).toBeNull()
  })

  it('keeps Approve waiting until every mandatory value is filled in', () => {
    const form = stepFormOf(BOOKING)!
    const values = initialValues(form)
    expect(unfilled(form, values)).toEqual(['Phone'])
    values['fields.1.value'] = mapped(form.fields[1], form.data[0].value)
    expect(unfilled(form, values)).toEqual([])
    values['fields.0.value'] = ''
    expect(unfilled(form, values)).toEqual(['Name'])
  })

  it('sends back what changed and every value that was missing', () => {
    const form = stepFormOf(BOOKING)!
    const values = { ...initialValues(form), 'fields.1.value': '98450 00000', 'fields.2.value': ['Music', 'Food'] }
    expect(answerValues(form, values)).toEqual({ 'fields.1.value': '98450 00000', 'fields.2.value': ['Music', 'Food'] })
  })

  it('offers a run-time choice only from what the page lists now', () => {
    const form = stepFormOf(approval({
      action: 'browser.clickItem', title: 'Choose from a list', choicesAt: '2026-11-08T10:00:00Z',
      fields: [{ key: 'item', label: 'Item to choose', type: 'select', value: 'Ramayana', required: true, missing: false, options: ['Kantara', 'Dune'] }],
      missing: [],
    }))!
    // The run asked for Ramayana, which the page no longer lists: nothing is chosen yet.
    expect(initialValues(form)).toEqual({ item: '' })
    expect(unfilled(form, initialValues(form))).toEqual(['Item to choose'])
  })
})
