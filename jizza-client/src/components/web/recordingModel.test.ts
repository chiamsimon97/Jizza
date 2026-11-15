import { describe, expect, it } from 'vitest'
import type { RecordedStep } from '@/api/web'
import { conditionDepths, dependentListChoices, emptyConditions, fieldName, isCondition, hostOf, liveSteps, mappableFields, missingLogins, movesBetween, pageArrangement, reusableOps, siteAllowed, stepsUsingInput, suggestInputName, unmapOps } from './recordingModel'

const step = (seq: number, over: Partial<RecordedStep['step']> = {}, deleted = false, position = seq): RecordedStep =>
  ({ seq, position, deleted, step: { action: 'browser.setText', label: 'Set "Invoice number" to "INV-2041"', input: { text: 'INV-2041', clearFirst: true }, ...over } })

describe('recordingModel', () => {
  it('lists live steps in their order, without deleted ones', () => {
    expect(liveSteps([step(1, {}, false, 2), step(2, {}, true, 1), step(3, {}, false, 1)]).map(s => s.seq)).toEqual([3, 1])
  })

  it('offers typed values as inputs, but never a password', () => {
    expect(mappableFields(step(1))).toEqual([{ field: 'text', value: 'INV-2041' }])
    expect(mappableFields(step(2, { meta: { sensitive: true } }))).toEqual([])
  })

  it("offers a dropdown's chosen option, a calendar date and a checkbox's state as inputs", () => {
    expect(mappableFields(step(3, { action: 'browser.selectItem', input: { by: 'label', items: ['Sleeper'] } })))
      .toEqual([{ field: 'items', value: 'Sleeper' }])
    expect(mappableFields(step(4, { action: 'browser.pickDate', input: { date: '20 Nov 2026' } })))
      .toEqual([{ field: 'date', value: '20 Nov 2026' }])
    expect(mappableFields(step(5, { action: 'browser.setCheckbox', input: { state: 'checked' } })))
      .toEqual([{ field: 'state', value: 'checked' }])
  })

  it("offers each field of a form step, named by the field's label", () => {
    const form = step(6, { action: 'browser.fillForm', label: 'Passenger details', input: { fields: [
      { kind: 'text', label: 'First name', value: 'Asha' }, { kind: 'text', label: 'Last name', value: '' },
      { kind: 'select', label: 'Berth preference', value: 'Lower' }] } })
    expect(mappableFields(form)).toEqual([{ field: 'fields.0.value', value: 'Asha' }, { field: 'fields.2.value', value: 'Lower' }])
    expect(fieldName(form, 'fields.2.value')).toBe('Berth preference')
    expect(suggestInputName(form, 'fields.0.value')).toBe('firstName')
  })

  it('names the choices of a list by the names given while recording', () => {
    const chosen = step(7, { action: 'browser.clickItem', input: { item: '07:15 PM', within: { item: 'PVR' } },
      meta: { list: { named: true, title: 'Showtime', withinTitle: 'Theatre', inputName: 'showtime', withinInputName: 'theatre' } } })
    expect(fieldName(chosen, 'item')).toBe('Showtime')
    expect(fieldName(chosen, 'within.item')).toBe('Theatre')
    expect(suggestInputName(chosen, 'within.item')).toBe('theatre')
  })

  it('names an input from the quoted label', () => {
    expect(suggestInputName(step(1), 'text')).toBe('invoiceNumber')
  })

  it('finds password steps still without a saved login', () => {
    const pw = step(1, { meta: { sensitive: true } })
    expect(missingLogins([pw])).toHaveLength(1)
    expect(missingLogins([step(1, { meta: { sensitive: true }, secretRef: 'login-1' })])).toHaveLength(0)
  })
})

describe('site checks for the setup checklist', () => {
  it('reads the host of a full address only', () => {
    expect(hostOf('https://Portal.Example.com/invoices')).toBe('portal.example.com')
    expect(hostOf('https://')).toBeNull()
    expect(hostOf('ftp://example.com')).toBeNull()
  })

  it('matches allowed sites the way the extension does', () => {
    expect(siteAllowed('example.com', ['example.com'])).toBe(true)
    expect(siteAllowed('a.example.com', ['*.example.com'])).toBe(true)
    expect(siteAllowed('example.com', ['*.example.com'])).toBe(false)
    expect(siteAllowed('badexample.com', ['*.example.com'])).toBe(false)
    expect(siteAllowed('other.com', ['example.com'])).toBe(false)
  })
})

describe('list choices', () => {
  const choose = (seq: number, input: Record<string, unknown>, list: Record<string, unknown>, mappings?: Record<string, { name: string; type: string }>) => ({
    seq, position: seq, deleted: false,
    step: { action: 'browser.clickItem', label: 'Choose', input, meta: { list }, ...(mappings ? { mappings } : {}) },
  })

  it('offers the item and the card it is chosen in, named as the recording suggests', () => {
    const s = choose(1, { item: '01:15 PM', within: { item: 'Vijay Park' } }, { kind: 'time', inputName: 'time', withinInputName: 'cinema' })
    expect(mappableFields(s)).toEqual([{ field: 'item', value: '01:15 PM' }, { field: 'within.item', value: 'Vijay Park' }])
    expect(suggestInputName(s, 'item')).toBe('time')
    expect(suggestInputName(s, 'within.item')).toBe('cinema')
  })

  it('makes every list choice reusable once, with unique names and the recorded defaults', () => {
    const steps = [
      choose(1, { item: 'Scene' }, { inputName: 'movie' }),
      choose(2, { item: '2D' }, { inputName: 'format' }, { item: { name: 'format', type: 'text' } }),
      choose(3, { item: '10' }, { inputName: 'date' }),
      choose(4, { item: 'Ramayana' }, { inputName: 'movie' }),
    ]
    expect(reusableOps(steps)).toEqual([
      { op: 'map', seq: 1, field: 'item', input: { name: 'movie', type: 'text', default: 'Scene' } },
      { op: 'map', seq: 3, field: 'item', input: { name: 'date', type: 'text', default: '10' } },
      { op: 'map', seq: 4, field: 'item', input: { name: 'movie2', type: 'text', default: 'Ramayana' } },
    ])
  })
})

describe('conditionDepths', () => {
  it('indents a condition\'s steps on both sides of Otherwise', () => {
    const at = (seq: number, action: string) => ({ seq, position: seq, deleted: false, step: { action } })
    const steps = [at(1, 'browser.click'), at(2, 'flow.if'), at(3, 'browser.click'), at(4, 'flow.else'),
      at(5, 'browser.click'), at(6, 'flow.endIf'), at(7, 'browser.click')]
    expect(conditionDepths(steps)).toEqual([0, 0, 1, 0, 1, 0, 0])
    expect(steps.filter(isCondition).map(s => s.seq)).toEqual([2, 4, 6])
  })
})

describe('emptyConditions', () => {
  const at = (seq: number, action: string) => ({ seq, position: seq, deleted: false, step: { action, label: action, input: {} } }) as unknown as RecordedStep
  it('finds the conditions a save leaves out because nothing is inside', () => {
    expect(emptyConditions([at(1, 'browser.click'), at(2, 'flow.if'), at(3, 'flow.endIf'), at(4, 'browser.clickItem')])).toEqual([2])
    expect(emptyConditions([at(1, 'flow.if'), at(2, 'flow.else'), at(3, 'flow.endIf')])).toEqual([1])
    expect(emptyConditions([at(1, 'flow.if'), at(2, 'browser.click'), at(3, 'flow.endIf')])).toEqual([])
  })
})

describe('pageArrangement and movesBetween', () => {
  const on = (seq: number, url: string, extra: Record<string, unknown> = {}) =>
    ({ seq, position: seq, deleted: false,
      step: { action: 'browser.click', label: `s${seq}`, input: {}, meta: { url }, ...extra } }) as unknown as RecordedStep

  it('groups steps by page in first-visit order, keeping order within a page', () => {
    const steps = [
      on(1, 'https://portal.example.com/search'),
      on(2, 'https://portal.example.com/details'),
      on(3, 'https://portal.example.com/search'),   // recorded late, belongs to page 1
      on(4, 'https://portal.example.com/details'),
    ]
    const plan = pageArrangement(steps)
    expect(plan && 'order' in plan ? plan.order : null).toEqual([1, 3, 2, 4])
    expect(plan && 'moved' in plan ? [...plan.moved] : null).toEqual([3, 2])
  })

  it('returns null when the steps are already in page order', () => {
    expect(pageArrangement([on(1, 'https://a.example.com/x'), on(2, 'https://a.example.com/y')])).toBeNull()
  })

  it('refuses recordings with conditions', () => {
    const plan = pageArrangement([on(1, 'https://a.example.com/x'),
      { seq: 2, position: 2, deleted: false, step: { action: 'flow.if', input: {} } } as unknown as RecordedStep])
    expect(plan && 'blocked' in plan).toBe(true)
  })

  it('steps with no page (an inserted wait) stay with the page before them', () => {
    const wait = { seq: 3, position: 3, deleted: false,
      step: { action: 'browser.wait', label: 'Wait', input: {}, meta: {} } } as unknown as RecordedStep
    const plan = pageArrangement([
      on(1, 'https://a.example.com/x'), on(2, 'https://a.example.com/y'), wait,
      on(4, 'https://a.example.com/x')])
    expect(plan && 'order' in plan ? plan.order : null).toEqual([1, 4, 2, 3])
  })

  it('movesBetween produces the server moves that reach the order', () => {
    expect(movesBetween([1, 2, 3, 4], [1, 3, 2, 4])).toEqual([{ op: 'move', seq: 3, toIndex: 1 }])
    expect(movesBetween([1, 2, 3], [1, 2, 3])).toEqual([])
    // Applying the inverse gets back to where it started.
    expect(movesBetween([1, 3, 2, 4], [1, 2, 3, 4])).toEqual([{ op: 'move', seq: 2, toIndex: 1 }])
  })
})

describe('unmap helpers', () => {
  const mapped = (seq: number, mappings: Record<string, { name: string; type: string }>) =>
    ({ seq, position: seq, deleted: false,
      step: { action: 'browser.setText', input: {}, mappings } }) as unknown as RecordedStep

  it('finds every step using an input name and builds the unmap edits', () => {
    const steps = [mapped(1, { text: { name: 'city', type: 'text' } }),
      mapped(2, { text: { name: 'city', type: 'text' } }),
      mapped(3, { text: { name: 'date', type: 'text' } })]
    const using = stepsUsingInput(steps, 'city')
    expect(using.map(s => s.seq)).toEqual([1, 2])
    expect(unmapOps(using.map(step => ({ step, field: 'text' })))).toEqual([
      { op: 'map', seq: 1, field: 'text', input: null },
      { op: 'map', seq: 2, field: 'text', input: null },
    ])
  })
})

describe('dependentListChoices', () => {
  it('names the list choices that follow a deleted list', () => {
    const list = (seq: number, title: string, dependsOn?: { title: string; relation: 'after' }) =>
      ({ seq, position: seq, deleted: false,
        step: { action: 'browser.clickItem', label: title, input: {},
          meta: { list: { title, kind: 'text', count: 2, ...(dependsOn ? { dependsOn } : {}) } } } }) as unknown as RecordedStep
    const steps = [list(1, 'Date'), list(2, 'Theatre', { title: 'Date', relation: 'after' })]
    expect(dependentListChoices(steps, steps[0]).map(s => s.seq)).toEqual([2])
    expect(dependentListChoices(steps, steps[1])).toEqual([])
  })
})
