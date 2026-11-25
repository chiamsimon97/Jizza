import { useId, useState, type ReactNode } from 'react'
import { BarChart3, Table2 } from 'lucide-react'

/**
 * The colours a chart picks from, in order. All follow the active theme through the CSS variables
 * in index.css, so a chart reads the same in light, dark and sunset.
 */
export const SERIES_COLOURS = [
  'rgb(var(--brand-500))',
  'rgb(var(--agent-finance))',
  'rgb(var(--accent-500))',
  'rgb(var(--agent-health))',
  'rgb(var(--brand-300))',
  'rgb(var(--gray-400))',
]

export const colourAt = (i: number) => SERIES_COLOURS[i % SERIES_COLOURS.length]

/** The same figures as a table: what a screen reader announces and what "Show table" opens. */
export interface ChartTable {
  columns: string[]
  rows: (string | number)[][]
}

interface ChartFrameProps {
  title: string
  /** One sentence on what the chart shows; also the chart's accessible description. */
  description?: string
  table: ChartTable
  children: ReactNode
  /** Start on the table instead of the picture. */
  initialView?: 'chart' | 'table'
}

/**
 * Every chart sits in one of these: a title, the picture, and a switch to the same figures as a
 * table. Colour is never the only carrier of meaning, so anyone can read the numbers.
 */
export function ChartFrame({ title, description, table, children, initialView = 'chart' }: ChartFrameProps) {
  const [view, setView] = useState(initialView)
  const id = useId()
  const showingTable = view === 'table'
  return (
    <figure className="m-0" aria-labelledby={`${id}-title`} aria-describedby={description ? `${id}-desc` : undefined}>
      <div className="mb-2 flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <figcaption id={`${id}-title`} className="text-sm font-semibold text-gray-900 dark:text-gray-100">{title}</figcaption>
          {description && <p id={`${id}-desc`} className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{description}</p>}
        </div>
        <button type="button" onClick={() => setView(showingTable ? 'chart' : 'table')}
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-gray-500 hover:bg-gray-100 hover:text-gray-800 dark:hover:bg-gray-800 dark:hover:text-gray-200"
          aria-pressed={showingTable}>
          {showingTable ? <BarChart3 className="h-3.5 w-3.5" /> : <Table2 className="h-3.5 w-3.5" />}
          {showingTable ? 'Show chart' : 'Show table'}
        </button>
      </div>
      {showingTable ? <DataTable table={table} /> : <div aria-hidden="true">{children}</div>}
      {!showingTable && <div className="sr-only"><DataTable table={table} /></div>}
    </figure>
  )
}

function DataTable({ table }: { table: ChartTable }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs">
        <thead>
          <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
            {table.columns.map(c => <th key={c} scope="col" className="py-1.5 pr-3 font-medium">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, r) => (
            <tr key={r} className="border-b border-gray-100 last:border-0 dark:border-gray-800/60">
              {row.map((cell, c) => (
                <td key={c} className={`py-1.5 pr-3 text-gray-800 dark:text-gray-200 ${c > 0 ? 'tabular-nums' : ''}`}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** A colour square and a label, for legends. */
export function Swatch({ colour, label, detail }: { colour: string; label: string; detail?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
      <span className="inline-block h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: colour }} />
      <span>{label}</span>
      {detail && <span className="tabular-nums text-gray-500 dark:text-gray-400">{detail}</span>}
    </span>
  )
}
