import { describe, expect, it } from 'vitest'
import type { NotificationDto } from '@/types'
import { CHECKED_RETRY, NO_FILTERS, buildInbox, runChoicesFor, countsByView, defaultChoiceOf, fixLinkOf, signInLinkOf, memoryReviewLinkOf, isFailedStep, matches, outcomeOf, snoozeOptions, timeLeft, viewOf } from './inbox'

const NOW = Date.parse('2026-10-23T10:00:00Z')
const at = (mins: number) => new Date(NOW + mins * 60_000).toISOString()

let seq = 0
function n(over: Partial<NotificationDto> = {}): NotificationDto {
  return { id: `n${++seq}`, kind: 'PERMISSION_REQUEST', status: 'UNREAD', createdAt: at(-60), priority: 'NORMAL', ...over }
}

describe('inbox', () => {
  it('puts each item in the tab it belongs to', () => {
    expect(viewOf(n(), NOW)).toBe('needs_you')
    expect(viewOf(n({ status: 'READ' }), NOW)).toBe('needs_you')
    expect(viewOf(n({ status: 'APPROVED' }), NOW)).toBe('done')
    expect(viewOf(n({ status: 'EXPIRED' }), NOW)).toBe('done')
    expect(viewOf(n({ kind: 'INFO' }), NOW)).toBe('updates')
    expect(viewOf(n({ kind: 'INFO', archivedAt: at(-1) }), NOW)).toBe('done')
    expect(viewOf(n({ snoozedUntil: at(30) }), NOW)).toBe('snoozed')
    expect(viewOf(n({ snoozedUntil: at(-30) }), NOW)).toBe('needs_you')
  })

  it('orders what needs the user by priority, then by what expires first', () => {
    const later = n({ expiresAt: at(300) })
    const sooner = n({ expiresAt: at(20) })
    const noExpiry = n({ createdAt: at(-1) })
    const urgent = n({ priority: 'URGENT', expiresAt: at(600) })
    const low = n({ priority: 'LOW', expiresAt: at(5) })

    const sections = buildInbox([later, low, noExpiry, sooner, urgent], 'needs_you', NO_FILTERS, NOW)

    expect(sections.map(s => s.title)).toEqual(['Urgent', 'Waiting on you', 'When you have a moment'])
    expect(sections.flatMap(s => s.items).map(i => i.id)).toEqual([urgent.id, sooner.id, later.id, noExpiry.id, low.id])
  })

  it('shows unread updates before read ones', () => {
    const read = n({ kind: 'INFO', status: 'READ', createdAt: at(-1) })
    const unread = n({ kind: 'INFO', createdAt: at(-100) })
    expect(buildInbox([read, unread], 'updates', NO_FILTERS, NOW)[0].items.map(i => i.id)).toEqual([unread.id, read.id])
  })

  it('counts waiting items and unread updates', () => {
    const counts = countsByView([n(), n({ kind: 'INFO' }), n({ kind: 'INFO', status: 'READ' }), n({ snoozedUntil: at(5) })], NOW)
    expect(counts).toEqual({ needs_you: 1, updates: 1, snoozed: 1, done: 0 })
  })

  it('filters by kind, agent, urgency and text', () => {
    const item = n({ body: 'Kai needs approval to run send_invoice', sourceAgentName: 'Kai', context: { tool: 'send_invoice' } })
    expect(matches(item, { ...NO_FILTERS, q: 'INVOICE' })).toBe(true)
    expect(matches(item, { ...NO_FILTERS, q: 'refund' })).toBe(false)
    expect(matches(item, { ...NO_FILTERS, kinds: ['INFO'] })).toBe(false)
    expect(matches(item, { ...NO_FILTERS, agent: 'Kai' })).toBe(true)
    expect(matches(item, { ...NO_FILTERS, agent: 'Jizza' })).toBe(false)
    expect(matches(item, { ...NO_FILTERS, urgentOnly: true })).toBe(false)
  })

  it('never offers a snooze that outlasts the request', () => {
    const labels = snoozeOptions(n({ expiresAt: at(120) }), NOW).map(o => o.label)
    expect(labels).toEqual(['For 1 hour'])
    expect(snoozeOptions(n({ kind: 'INFO' }), NOW)).toHaveLength(4)
  })

  it('says how long a request has left', () => {
    expect(timeLeft(at(30), NOW)).toEqual({ text: 'Expires in 30 min', soon: true })
    expect(timeLeft(at(180), NOW)).toEqual({ text: 'Expires in 3h', soon: false })
    expect(timeLeft(null, NOW)).toBeNull()
  })

  it('links a web fix notification to the builder, and nothing else', () => {
    const link = '/workflows?open=0b8a6c1e-1111-4a2b-9c3d-123456789abc&node=submit'
    expect(fixLinkOf(n({ kind: 'INFO', context: { source: 'webauto', kind: 'locator_fix', link } }))).toBe(link)
    expect(fixLinkOf(n({ kind: 'INFO', context: { source: 'webauto', kind: 'locator_fix', link: 'https://evil.test' } }))).toBeNull()
    expect(fixLinkOf(n({ kind: 'INFO', context: { source: 'device', link } }))).toBeNull()
    expect(fixLinkOf(n({ kind: 'INFO' }))).toBeNull()
  })

  it('links a lost connector sign-in to that connector only', () => {
    const id = '7f0c2b1e-1111-4a4a-9c9c-123456789abc'
    const lost = (link: string) => n({ kind: 'INFO', context: { source: 'connector', kind: 'sign_in', link } })
    expect(signInLinkOf(lost(`/connectors/mcp/${id}`))).toBe(`/connectors/mcp/${id}`)
    expect(signInLinkOf(lost(`/connectors/${id}/authentication`))).toBe(`/connectors/${id}/authentication`)
    expect(signInLinkOf(lost('https://evil.test/connectors/x'))).toBeNull()
    expect(signInLinkOf(lost('/settings/security'))).toBeNull()
    expect(signInLinkOf(n({ kind: 'INFO', context: { source: 'webauto', kind: 'sign_in', link: `/connectors/${id}` } }))).toBeNull()
  })

  it('links a finished memory review to its suggestions, and nothing else', () => {
    const done = (link: unknown) => n({ kind: 'INFO', context: { source: 'memory', kind: 'memory_review', link } })
    expect(memoryReviewLinkOf(done('/memory?review=open'))).toBe('/memory?review=open')
    expect(memoryReviewLinkOf(done('https://evil.test/memory?review=open'))).toBeNull()
    expect(memoryReviewLinkOf(n({ kind: 'INFO', context: { source: 'connector', kind: 'memory_review', link: '/memory?review=open' } }))).toBeNull()
  })

  it('offers the run choices only on questions about a failed workflow step', () => {
    expect(isFailedStep(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', context: { failedStep: true } }))).toBe(true)
    // Raised before the server marked them, so read from the wording.
    expect(isFailedStep(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', body: "Step 'Open' could not complete: refused. How would you like to proceed?" }))).toBe(true)
    expect(isFailedStep(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', body: 'Which supplier?' }))).toBe(false)
    expect(isFailedStep(n({ kind: 'INSTRUCTION_REQUIRED', context: { failedStep: true } }))).toBe(false)
    expect(isFailedStep(n({ kind: 'PERMISSION_REQUEST', runId: 'r1', context: { failedStep: true } }))).toBe(false)
  })

  it('knows what a failed step question does when nobody answers', () => {
    expect(defaultChoiceOf(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', context: { failedStep: true, defaultChoice: 'Skip this step' } }))).toBe('Skip this step')
    // Older questions, and anything unrecognised, stop the run.
    expect(defaultChoiceOf(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', body: "Step 'Open' could not complete: refused." }))).toBe('Stop the run')
    expect(defaultChoiceOf(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', context: { failedStep: true, defaultChoice: 'Dance' } }))).toBe('Stop the run')
    expect(defaultChoiceOf(n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', body: 'Which supplier?' }))).toBeNull()
  })

  it('offers Try again on a step that cannot be undone only once the person has checked', () => {
    const pay = n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', context: { failedStep: true, irreversible: true } })
    expect(runChoicesFor(pay).map(c => c.answer)).toEqual([CHECKED_RETRY, 'Skip this step', 'Stop the run'])
    const plain = n({ kind: 'INSTRUCTION_REQUIRED', runId: 'r1', context: { failedStep: true } })
    expect(runChoicesFor(plain)[0].answer).toBe('Try again')
    const auto = n({ kind: 'INSTRUCTION_REQUIRED', status: 'SUBMITTED', context: { answeredByDefault: true } })
    expect(outcomeOf(auto)?.text).toBe('No answer in time, so Jizza used the default')
  })

  it('says why a question was closed without an answer', () => {
    const closed = n({ kind: 'INSTRUCTION_REQUIRED', status: 'EXPIRED', responseNote: 'Closed: the run was stopped' })
    expect(outcomeOf(closed)?.text).toBe('Closed: the run was stopped')
    expect(viewOf(closed, NOW)).toBe('done')
    expect(outcomeOf(n({ status: 'EXPIRED' }))?.text).toBe('Expired before anyone answered')
  })
})
