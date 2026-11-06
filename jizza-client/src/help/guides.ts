/**
 * The Help section's guides. Each guide is one Markdown file in `./guides`, with a short header:
 *
 * ```
 * ---
 * title: Browser recording
 * summary: One sentence shown on the Help list.
 * section: Web automation
 * order: 10
 * updated: 2026-11-01
 * ---
 * ```
 *
 * Adding a file is all it takes for a guide to show up; see `README.md` next to this file.
 */
export interface HelpGuide {
  /** The file name without `.md`; the guide opens at `/help/<slug>`. */
  slug: string
  title: string
  summary: string
  /** The group the guide is listed under on the Help page. */
  section: string
  /** Lower comes first within its section. */
  order: number
  /** When the guide was last checked against the app, as written in its header. */
  updated?: string
  /** The Markdown after the header. */
  body: string
}

const files = import.meta.glob('./guides/*.md', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

export function parseGuide(path: string, raw: string): HelpGuide {
  const slug = path.replace(/^.*\//, '').replace(/\.md$/, '')
  const text = raw.replace(/\r\n/g, '\n')
  const header = text.match(/^---\n([\s\S]*?)\n---\n?/)
  const fields: Record<string, string> = {}
  if (header) {
    for (const line of header[1].split('\n')) {
      const m = line.match(/^(\w+):\s*(.*)$/)
      if (m) fields[m[1]] = m[2].trim()
    }
  }
  const order = Number(fields.order)
  return {
    slug,
    title: fields.title || slug,
    summary: fields.summary ?? '',
    section: fields.section || 'General',
    order: Number.isFinite(order) ? order : 100,
    updated: fields.updated || undefined,
    body: header ? text.slice(header[0].length) : text,
  }
}

export const helpGuides: HelpGuide[] = Object.entries(files)
  .map(([path, raw]) => parseGuide(path, raw))
  .sort((a, b) => a.section.localeCompare(b.section) || a.order - b.order || a.title.localeCompare(b.title))

export function findGuide(slug: string | undefined): HelpGuide | undefined {
  return slug ? helpGuides.find(g => g.slug === slug) : undefined
}

/** The guides grouped by section, in list order. */
export function guidesBySection(guides: HelpGuide[] = helpGuides): { section: string; guides: HelpGuide[] }[] {
  const out: { section: string; guides: HelpGuide[] }[] = []
  for (const g of guides) {
    const last = out[out.length - 1]
    if (last?.section === g.section) last.guides.push(g)
    else out.push({ section: g.section, guides: [g] })
  }
  return out
}
