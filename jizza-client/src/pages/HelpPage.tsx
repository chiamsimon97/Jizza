import { useMemo, useState } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { ArrowLeft, BookOpen, LifeBuoy, Search } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { Card } from '@/components/ui/Card'
import { EmptyState } from '@/components/ui/EmptyState'
import { findGuide, guidesBySection, helpGuides, type HelpGuide } from '@/help/guides'
import { HelpMarkdown, headingsOf } from '@/help/HelpMarkdown'

/** Help: guides on how Jizza's features work, one page per guide. */
export function HelpPage() {
  const { slug } = useParams()
  if (!slug) return <HelpIndex />
  const guide = findGuide(slug)
  if (!guide) return <Navigate to="/help" replace />
  return <GuidePage guide={guide} />
}

function HelpIndex() {
  const [query, setQuery] = useState('')
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return helpGuides
    return helpGuides.filter(g => `${g.title} ${g.summary} ${g.section} ${g.body}`.toLowerCase().includes(q))
  }, [query])
  return (
    <div className="page">
      <PageHeader icon={LifeBuoy} title="Help" description="Guides on how Jizza's features work and what you can do with them." />
      <div className="relative mb-6 max-w-md">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
        <input
          type="search"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search the guides"
          aria-label="Search the guides"
          className="w-full rounded-lg border border-gray-200 bg-white py-2 pl-9 pr-3 text-sm text-gray-900 placeholder:text-gray-400 focus:border-brand-400 focus:outline-none dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
        />
      </div>
      {shown.length === 0 ? (
        <EmptyState icon={BookOpen} title="No guide matches" description="Try another word, or clear the search." />
      ) : (
        <div className="flex flex-col gap-6">
          {guidesBySection(shown).map(({ section, guides }) => (
            <section key={section} aria-label={section}>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{section}</h2>
              <div className="grid gap-3 sm:grid-cols-2">
                {guides.map(g => (
                  <Link key={g.slug} to={`/help/${g.slug}`} className="block">
                    <Card interactive className="h-full">
                      <div className="flex items-start gap-3">
                        <BookOpen className="mt-0.5 h-4 w-4 shrink-0 text-brand-600 dark:text-brand-300" />
                        <div className="min-w-0">
                          <p className="font-medium text-gray-900 dark:text-gray-100">{g.title}</p>
                          {g.summary && <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{g.summary}</p>}
                        </div>
                      </div>
                    </Card>
                  </Link>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  )
}

function GuidePage({ guide }: { guide: HelpGuide }) {
  const contents = useMemo(() => headingsOf(guide.body), [guide.body])
  return (
    <div className="page">
      <Link to="/help" className="mb-3 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100">
        <ArrowLeft className="h-4 w-4" />All guides
      </Link>
      <PageHeader icon={BookOpen} title={guide.title} description={guide.summary} />
      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        <Card padding="lg" className="min-w-0 flex-1">
          <article><HelpMarkdown text={guide.body} /></article>
          {guide.updated && <p className="mt-6 text-xs text-gray-400">Last checked {guide.updated}</p>}
        </Card>
        {contents.length > 1 && (
          <nav aria-label="On this page" className="lg:sticky lg:top-4 lg:w-56 lg:shrink-0">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">On this page</p>
            <ul className="flex flex-col gap-1 text-sm">
              {contents.map(h => (
                <li key={h.id}>
                  <a href={`#${h.id}`} onClick={e => { e.preventDefault(); document.getElementById(h.id)?.scrollIntoView({ behavior: 'smooth' }) }}
                    className="text-gray-600 hover:text-brand-600 dark:text-gray-400 dark:hover:text-brand-300">{h.text}</a>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </div>
    </div>
  )
}
