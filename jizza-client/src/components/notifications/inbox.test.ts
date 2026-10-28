import { describe, expect, it } from 'vitest'
import type { NotificationDto } from '@/types'
import { NO_FILTERS, buildInbox, countsByView, fixLinkOf, matches, snoozeOptions, timeLeft, viewOf } from './inbox'

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
})
