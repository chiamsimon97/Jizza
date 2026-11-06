import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * A help guide, formatted: headings, paragraphs, lists (one level of nesting), tables, code blocks,
 * and inline code, bold, italics and links. Built from React elements, never HTML. Links that start
 * with `/` open inside the app; only http(s) links leave it.
 */
export function HelpMarkdown({ text }: { text: string }) {
  return <div className="flex flex-col gap-3 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{blocks(text)}</div>
}

/** The guide's `##` headings, for its contents list. */
export function headingsOf(text: string): { id: string; text: string }[] {
  const out: { id: string; text: string }[] = []
  let fenced = false
  for (const line of text.split('\n')) {
    if (/^\s*```/.test(line)) fenced = !fenced
    const m = !fenced && line.match(/^##\s+(.*)$/)
    if (m) out.push({ id: slugify(m[1]), text: m[1].replace(/[*`]/g, '') })
  }
  return out
}

export function slugify(text: string): string {
  return text.toLowerCase().replace(/[*`]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}

const BULLET = /^(\s*)[-*]\s+/
const NUMBERED = /^(\s*)\d+[.)]\s+/
const TABLE_ROW = /^\s*\|.*\|\s*$/

function blocks(text: string): ReactNode[] {
  const out: ReactNode[] = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let i = 0
  let para: string[] = []
  const key = () => `b${out.length}`
  const flush = () => {
    if (para.length) out.push(<p key={key()}>{inline(para.join(' '))}</p>)
    para = []
  }
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*```/.test(line)) {
      flush()
      const code: string[] = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++])
      i++
      out.push(
        <pre key={key()} className="overflow-x-auto rounded-lg border border-gray-200 bg-gray-50 p-3 font-mono text-xs text-gray-800 dark:border-gray-700 dark:bg-gray-950 dark:text-gray-200">
          {code.join('\n')}
        </pre>,
      )
      continue
    }
    const heading = line.match(/^(#{2,4})\s+(.*)$/)
    if (heading) {
      flush()
      const level = heading[1].length
      const content = inline(heading[2])
      if (level === 2) {
        out.push(<h2 key={key()} id={slugify(heading[2])} className="mt-6 scroll-mt-4 text-lg font-semibold text-gray-900 first:mt-0 dark:text-gray-100">{content}</h2>)
      } else {
        out.push(<h3 key={key()} className="mt-3 text-sm font-semibold text-gray-900 dark:text-gray-100">{content}</h3>)
      }
      i++
      continue
    }
    if (TABLE_ROW.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      flush()
      const head = cells(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && TABLE_ROW.test(lines[i])) rows.push(cells(lines[i++]))
      out.push(
        <div key={key()} className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50 text-xs uppercase tracking-wide text-gray-500 dark:bg-gray-800/60 dark:text-gray-400">
              <tr>{head.map((c, j) => <th key={j} className="px-3 py-2 font-medium">{inline(c)}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
              {rows.map((r, j) => <tr key={j}>{head.map((_, k) => <td key={k} className="px-3 py-2 align-top">{inline(r[k] ?? '')}</td>)}</tr>)}
            </tbody>
          </table>
        </div>,
      )
      continue
    }
    if (BULLET.test(line) || NUMBERED.test(line)) {
      flush()
      const [node, next] = list(lines, i, 0)
      out.push(<Fragment key={key()}>{node}</Fragment>)
      i = next
      continue
    }
    if (!line.trim()) flush()
    else para.push(line.trim())
    i++
  }
  flush()
  return out
}

function indentOf(line: string): number {
  return line.match(/^\s*/)![0].length
}

/** A list starting at `start`, with items indented further than `indent` nested inside it. */
function list(lines: string[], start: number, depth: number): [ReactNode, number] {
  const ordered = NUMBERED.test(lines[start])
  const marker = ordered ? NUMBERED : BULLET
  const base = indentOf(lines[start])
  const items: { text: string; children: ReactNode[] }[] = []
  let i = start
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) {
      // A blank line ends the list unless the list goes on right after it.
      const next = lines[i + 1]
      if (next !== undefined && (BULLET.test(next) || NUMBERED.test(next)) && indentOf(next) >= base) { i++; continue }
      break
    }
    const ind = indentOf(line)
    if (ind > base && (BULLET.test(line) || NUMBERED.test(line)) && items.length && depth < 3) {
      const [child, next] = list(lines, i, depth + 1)
      items[items.length - 1].children.push(<Fragment key={i}>{child}</Fragment>)
      i = next
      continue
    }
    if (ind === base && marker.test(line)) {
      items.push({ text: line.replace(marker, ''), children: [] })
      i++
      continue
    }
    if (ind > base && items.length) {
      // A wrapped line of the item above.
      items[items.length - 1].text += ' ' + line.trim()
      i++
      continue
    }
    break
  }
  const List = ordered ? 'ol' : 'ul'
  // A numbered list that goes on from an earlier one (4. after a heading) keeps its numbers.
  const first = ordered ? Number(lines[start].match(/^\s*(\d+)/)?.[1] ?? 1) : 1
  return [
    <List start={ordered && first !== 1 ? first : undefined} className={`${ordered ? 'list-decimal' : 'list-disc'} flex flex-col gap-1 pl-5`}>
      {items.map((item, j) => <li key={j}>{inline(item.text)}{item.children.length > 0 && <div className="mt-1">{item.children}</div>}</li>)}
    </List>,
    i,
  ]
}

function cells(row: string): string[] {
  return row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim())
}

const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\(((?:https?:\/\/|\/)[^\s)]*)\))|(\*[^*\s][^*]*\*)/g

function inline(text: string): ReactNode {
  const parts: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(INLINE)) {
    if (m.index! > last) parts.push(text.slice(last, m.index))
    const [whole] = m
    const k = parts.length
    if (m[1]) {
      parts.push(<code key={k} className="rounded bg-gray-100 px-1 py-0.5 font-mono text-[0.85em] text-gray-800 dark:bg-gray-800 dark:text-gray-200">{whole.slice(1, -1)}</code>)
    } else if (m[2]) {
      parts.push(<strong key={k} className="font-semibold text-gray-900 dark:text-gray-100">{inline(whole.slice(2, -2))}</strong>)
    } else if (m[3]) {
      const label = whole.slice(1, whole.indexOf(']'))
      const href = m[4]
      const cls = 'text-brand-600 underline hover:no-underline dark:text-brand-300'
      parts.push(href.startsWith('/')
        ? <Link key={k} to={href} className={cls}>{label}</Link>
        : <a key={k} href={href} target="_blank" rel="noopener noreferrer" className={cls}>{label}</a>)
    } else {
      parts.push(<em key={k}>{whole.slice(1, -1)}</em>)
    }
    last = m.index! + whole.length
  }
  if (last < text.length) parts.push(text.slice(last))
  return <Fragment>{parts}</Fragment>
}
