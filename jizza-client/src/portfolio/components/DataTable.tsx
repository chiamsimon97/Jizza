import type { ReactNode } from 'react'

export interface Column<T> {
  header: string
  cell: (row: T) => ReactNode
  /** Right-aligned with tabular figures. */
  numeric?: boolean
}

/** A plain table that scrolls sideways on a phone instead of squeezing its columns. */
export function DataTable<T>({ columns, rows, rowKey, caption, empty = 'Nothing here yet.' }: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  caption?: string
  empty?: string
}) {
  if (!rows.length) return <p className="text-sm text-gray-500 dark:text-gray-400">{empty}</p>
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[28rem] text-left text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr className="border-b border-gray-200 text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">
            {columns.map(c => <th key={c.header} scope="col" className={`py-2 pr-3 font-medium ${c.numeric ? 'text-right' : ''}`}>{c.header}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={rowKey(r)} className="border-b border-gray-100 last:border-0 dark:border-gray-800/60">
              {columns.map(c => (
                <td key={c.header} className={`py-2 pr-3 align-top text-gray-800 dark:text-gray-200 ${c.numeric ? 'text-right tabular-nums' : ''}`}>{c.cell(r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
