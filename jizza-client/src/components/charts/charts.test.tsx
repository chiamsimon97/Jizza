import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { DonutChart } from './DonutChart'
import { BarChart } from './BarChart'
import { LineChart, niceTicks, path } from './LineChart'
import { DriftBand, driftState } from './DriftBand'

describe('DonutChart', () => {
  it('lists each part with its share and amount, and opens the same figures as a table', () => {
    render(<DonutChart title="Allocation" parts={[
      { label: 'Indian equity', value: 600000 }, { label: 'Debt', value: 300000 }, { label: 'Gold', value: 100000 },
    ]} />)
    expect(screen.getByRole('figure', { name: 'Allocation' })).toBeInTheDocument()
    expect(screen.getAllByText('60% · ₹6 L').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: 'Show table' }))
    const table = screen.getByRole('table')
    expect(within(table).getByText('Indian equity')).toBeInTheDocument()
    expect(within(table).getByText('30%')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show chart' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows the total in the middle and ignores empty parts', () => {
    const { container } = render(<DonutChart title="Mix" parts={[{ label: 'A', value: 250000 }, { label: 'B', value: 0 }]} />)
    expect(container.querySelector('svg text')?.textContent).toBe('₹2.5 L')
    expect(screen.queryByText(/^B$/)).not.toBeInTheDocument()
  })
})

describe('BarChart', () => {
  it('adds a target column only when a bar has a target', () => {
    render(<BarChart title="Goals" bars={[{ label: 'Education', value: 400000, target: 600000 }, { label: 'Car', value: 150000 }]} initialTable />)
    const table = screen.getAllByRole('table')[0]
    expect(within(table).getByText('Target')).toBeInTheDocument()
    expect(within(table).getByText('₹6 L')).toBeInTheDocument()
  })
})

describe('LineChart', () => {
  it('puts every point in the table, with a dash for gaps', () => {
    render(<LineChart title="Net worth" labels={['Jan', 'Feb', 'Mar']}
      series={[{ name: 'Net worth', values: [1000000, null, 1200000] }]} />)
    const table = screen.getAllByRole('table', { hidden: true })[0]
    expect(within(table).getByText('—')).toBeInTheDocument()
    expect(within(table).getByText('₹12 L')).toBeInTheDocument()
  })

  it('breaks the line where a value is missing', () => {
    const d = path([1, null, 3, 4], i => i, v => v)
    expect(d).toBe('M0.0,1.0M2.0,3.0L3.0,4.0')
  })

  it('picks round ticks that cover the range', () => {
    const ticks = niceTicks(1_030_000, 1_870_000)
    expect(ticks[0]).toBeLessThanOrEqual(1_030_000)
    expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(1_870_000)
    expect(ticks.length).toBeGreaterThanOrEqual(3)
    expect(ticks.length).toBeLessThanOrEqual(7)
    expect(niceTicks(5, 5).length).toBeGreaterThan(1)
  })
})

describe('DriftBand', () => {
  const rows = [
    { label: 'Mid Cap', target: 0.15, low: 0.1, high: 0.2, actual: 0.24 },
    { label: 'Large Cap', target: 0.35, low: 0.3, high: 0.4, actual: 0.33 },
    { label: 'Gold', target: 0.1, low: 0.075, high: 0.125, actual: 0.05 },
  ]

  it('names each state in words, not only colour', () => {
    expect(rows.map(driftState)).toEqual(['Over', 'In band', 'Under'])
    render(<DriftBand title="Drift" rows={rows} />)
    expect(screen.getByText('24% · Over')).toBeInTheDocument()
    expect(screen.getByText('33% · In band')).toBeInTheDocument()
    expect(screen.getByText('5% · Under')).toBeInTheDocument()
  })
})
