import { describe, expect, it } from 'vitest'
import { findGuide, guidesBySection, helpGuides, parseGuide } from './guides'
import { headingsOf, slugify } from './HelpMarkdown'

describe('help guides', () => {
  it('reads the header and the body of a guide', () => {
    const g = parseGuide('./guides/sample-guide.md', '---\ntitle: Sample\nsummary: A test.\nsection: Tests\norder: 3\nupdated: 2026-11-01\n---\n\n## One\n\nText')
    expect(g).toMatchObject({ slug: 'sample-guide', title: 'Sample', summary: 'A test.', section: 'Tests', order: 3, updated: '2026-11-01' })
    expect(g.body.trim()).toBe('## One\n\nText')
  })

  it('falls back to the file name and the General section without a header', () => {
    const g = parseGuide('./guides/plain.md', '## Only text')
    expect(g).toMatchObject({ slug: 'plain', title: 'plain', section: 'General', order: 100 })
  })

  it('ships the browser recording guide with a title and a summary', () => {
    const g = findGuide('browser-recording')
    expect(g?.title).toBe('Browser recording')
    expect(g?.summary).not.toBe('')
    expect(headingsOf(g!.body).map(h => h.text)).toContain('Record a task')
  })

  it('gives every guide a title, a summary and a unique address', () => {
    expect(helpGuides.length).toBeGreaterThan(0)
    expect(new Set(helpGuides.map(g => g.slug)).size).toBe(helpGuides.length)
    for (const g of helpGuides) {
      expect(g.title).not.toBe(g.slug)
      expect(g.summary).not.toBe('')
    }
  })

  it('groups guides by section in list order', () => {
    const a = parseGuide('./guides/a.md', '---\ntitle: A\nsection: X\n---\n')
    const b = parseGuide('./guides/b.md', '---\ntitle: B\nsection: X\n---\n')
    const c = parseGuide('./guides/c.md', '---\ntitle: C\nsection: Y\n---\n')
    expect(guidesBySection([a, b, c]).map(s => [s.section, s.guides.length])).toEqual([['X', 2], ['Y', 1]])
  })

  it('makes heading ids from their text', () => {
    expect(slugify('Sign-ins and **when** Jizza needs you')).toBe('sign-ins-and-when-jizza-needs-you')
  })
})
