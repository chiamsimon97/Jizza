import { Fragment, useState, type ReactNode } from 'react'
import { Check, Copy } from 'lucide-react'

/**
 * Jizza's replies, formatted: paragraphs, headings, lists, code blocks with a copy button, and
 * inline code, bold, italics and links. Built from React elements, never HTML, so nothing in a
 * reply can run in the page.
 */
export function Markdown({ text, hideLinks }: { text: string; hideLinks?: string[] }) {
  const shown = hideLinks?.length ? withoutLinks(text, hideLinks) : text
  return <div className="flex flex-col gap-2">{blocks(shown)}</div>
}

/**
 * The reply without links a card already shows as buttons: a line that is only the link goes,
 * a link inside a sentence keeps its words, and a bare copy of the address goes.
 */
export function withoutLinks(text: string, urls: string[]): string {
  let out = text
  for (const url of urls) {
    const esc = url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const link = () => new RegExp(`\\[([^\\]]+)\\]\\(${esc}\\)`, 'g')
    const bare = () => new RegExp(`<?${esc}>?`, 'g')
    const onlyTheLink = (line: string) =>
      line.includes(url) && !line.replace(link(), '').replace(bare(), '').replace(/[*_\s:.,-]/g, '')
    out = out.split('\n').filter(line => !onlyTheLink(line)).join('\n')
      .replace(link(), '$1')
      .replace(bare(), '')
  }
  return out.replace(/\n{3,}/g, '\n\n')
}

function blocks(text: string): ReactNode[] {
  const out: ReactNode[] = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let i = 0
  let para: string[] = []
  const flush = () => {
    if (para.length) out.push(<p key={`p${out.length}`}>{inline(para.join('\n'))}</p>)
    para = []
  }
  while (i < lines.length) {
    const line = lines[i]
    const fence = line.match(/^\s*```(\S*)/)
    if (fence) {
      flush()
      const code: string[] = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++])
      i++
      out.push(<CodeBlock key={`c${out.length}`} lang={fence[1]} code={code.join('\n')} />)
      continue
    }
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      flush()
      out.push(<p key={`h${out.length}`} className="font-semibold text-gray-900 dark:text-gray-100">{inline(heading[2])}</p>)
      i++
      continue
    }
    const bullet = /^\s*[-*•]\s+/
    const numbered = /^\s*\d+[.)]\s+/
    if (bullet.test(line) || numbered.test(line)) {
      flush()
      const ordered = numbered.test(line)
      const items: string[] = []
      while (i < lines.length && (ordered ? numbered : bullet).test(lines[i])) {
        items.push(lines[i].replace(ordered ? numbered : bullet, ''))
        i++
      }
      const List = ordered ? 'ol' : 'ul'
      out.push(
        <List key={`l${out.length}`} className={`${ordered ? 'list-decimal' : 'list-disc'} flex flex-col gap-0.5 pl-5`}>
          {items.map((item, j) => <li key={j}>{inline(item)}</li>)}
        </List>,
      )
      continue
    }
    if (!line.trim()) flush()
    else para.push(line)
    i++
  }
  flush()
  return out
}

const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\((https?:\/\/[^\s)]+)\))|(\*[^*\s][^*]*\*)/g

function inline(text: string): ReactNode {
  const parts: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(INLINE)) {
    if (m.index! > last) parts.push(text.slice(last, m.index))
    const [whole] = m
    const key = parts.length
    if (m[1]) parts.push(<code key={key} className="rounded bg-gray-100 px-1 py-0.5 font-mono text-[0.85em] text-gray-800 dark:bg-gray-800 dark:text-gray-200">{whole.slice(1, -1)}</code>)
    else if (m[2]) parts.push(<strong key={key} className="font-semibold">{inline(whole.slice(2, -2))}</strong>)
    else if (m[3]) parts.push(<a key={key} href={m[4]} target="_blank" rel="noopener noreferrer" className="text-brand-600 underline hover:no-underline dark:text-brand-300">{whole.slice(1, whole.indexOf(']'))}</a>)
    else parts.push(<em key={key}>{whole.slice(1, -1)}</em>)
    last = m.index! + whole.length
  }
  if (last < text.length) parts.push(text.slice(last))
  return <Fragment>{parts}</Fragment>
}

function CodeBlock({ lang, code }: { lang: string; code: string }) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) })
  }
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
      <div className="flex items-center justify-between bg-gray-50 px-2.5 py-1 text-[11px] text-gray-500 dark:bg-gray-800 dark:text-gray-400">
        <span>{lang || 'code'}</span>
        <button type="button" onClick={copy} className="inline-flex items-center gap-1 hover:text-gray-900 dark:hover:text-gray-100" aria-label="Copy code">
          {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}{copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="overflow-x-auto bg-white p-2.5 font-mono text-xs leading-relaxed text-gray-800 dark:bg-gray-950 dark:text-gray-200">{code}</pre>
    </div>
  )
}
