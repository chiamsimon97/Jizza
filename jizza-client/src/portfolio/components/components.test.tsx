import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { HealthCheck } from '@/types/finance'
import { AsOf, daysUntil, formatDay, formatMonth } from './AsOf'
import { CheckTile } from './CheckTile'
import { DataTable } from './DataTable'
import { AmountField, parseAmount } from './Fields'
import { ScoreRing, scoreBand } from './ScoreRing'
import { SkeletonRows } from './SkeletonRows'
import { Sparkline } from './Sparkline'
import { StackedBar } from './StackedBar'
import { StatusBadge } from './StatusBadge'

const check = (over: Partial<HealthCheck>): HealthCheck => ({
  key: 'emergencyFund', status: 'amber', value: 3.2, shown: 3.2, unit: 'months', target: 6, weight: 20, points: 10, facts: {}, ...over,
})

describe('ScoreRing', () => {
  it('shows the score out of 100 and says so to screen readers', () => {
    render(<ScoreRing score={38} />)
    expect(screen.getByRole('img', { name: 'Money Health score 38 out of 100' })).toBeInTheDocument()
    expect(screen.getByText('38')).toBeInTheDocument()
  })

  it('has no score until a check is known', () => {
    render(<ScoreRing score={null} />)
    expect(screen.getByRole('img', { name: 'No score yet' })).toBeInTheDocument()
  })

  it('bands scores', () => {
    expect(scoreBand(38)).toBe('red')
    expect(scoreBand(60)).toBe('amber')
    expect(scoreBand(80)).toBe('green')
  })
})

describe('StatusBadge', () => {
  it('carries a word, not only a colour', () => {
    render(<><StatusBadge status="red" /><StatusBadge status="unknown" /></>)
    expect(screen.getByText('Fix first')).toBeInTheDocument()
    expect(screen.getByText('Not enough data')).toBeInTheDocument()
  })
})

describe('CheckTile', () => {
  it('shows the number, target and points, and a way to fix it', () => {
    const onOpen = vi.fn()
    render(<CheckTile check={check({})} onOpen={onOpen} />)
    expect(screen.getByText('3.2 months')).toBeInTheDocument()
    expect(screen.getByText('Target 6 months · 10 of 20 points')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Fix this' }))
    expect(onOpen).toHaveBeenCalledWith('net-worth')
  })

  it('is grey with its question when there is no data, never red', () => {
    const onAnswer = vi.fn()
    render(<CheckTile check={check({ key: 'protection', status: 'unknown', shown: null, value: null, points: 0 })} onAnswer={onAnswer} />)
    expect(screen.getByText('Not enough data')).toBeInTheDocument()
    expect(screen.queryByText('Fix first')).not.toBeInTheDocument()
    expect(screen.getByText('What term and health cover do you have?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Answer this' }))
    expect(onAnswer).toHaveBeenCalledWith('insurance')
  })

  it('says when a loan is above the high-interest line', () => {
    render(<CheckTile check={check({ key: 'debt', status: 'red', shown: 16.2, target: 30, points: 0, facts: { highInterestLoanIds: ['l1'] } })} />)
    expect(screen.getByText('16.2% of income on loans')).toBeInTheDocument()
    expect(screen.getByText(/A loan costs more than the high-interest line/)).toBeInTheDocument()
  })
})

describe('Sparkline', () => {
  it('draws two or more points and labels them', () => {
    render(<Sparkline values={[31, 34, 37.5]} label="Score 31, 34, 38" />)
    expect(screen.getByRole('img', { name: 'Score 31, 34, 38' })).toBeInTheDocument()
  })

  it('draws nothing for one point', () => {
    const { container } = render(<Sparkline values={[31]} label="x" />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('StackedBar', () => {
  it('has a table view with each part’s amount and share', () => {
    render(<StackedBar title="Needs, wants and saved" segments={[
      { label: 'Needs', value: 78000, className: 'bg-brand-500' },
      { label: 'Wants', value: 50000, className: 'bg-cyan-600' },
    ]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Show table' }))
    expect(screen.getAllByText('₹78,000').length).toBeGreaterThan(0)
    expect(screen.getAllByText('60.9%').length).toBeGreaterThan(0)
  })
})

describe('SkeletonRows', () => {
  it('announces loading', () => {
    render(<SkeletonRows rows={2} />)
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument()
  })
})

describe('AsOf and dates', () => {
  it('reads dates as written', () => {
    expect(formatDay('2026-11-25')).toBe('25 Nov 2026')
    expect(formatMonth('2027-05')).toBe('May 2027')
    expect(daysUntil('2026-12-05', new Date(2026, 10, 25))).toBe(10)
    render(<AsOf date="2026-11-25" />)
    expect(screen.getByText('As of 25 Nov 2026')).toBeInTheDocument()
  })
})

describe('DataTable', () => {
  it('lists rows, or says there are none', () => {
    const { rerender } = render(<DataTable rows={[{ id: 'a', n: 'FD' }]} rowKey={r => r.id} columns={[{ header: 'Asset', cell: r => r.n }]} />)
    expect(screen.getByRole('columnheader', { name: 'Asset' })).toBeInTheDocument()
    expect(screen.getByText('FD')).toBeInTheDocument()
    rerender(<DataTable rows={[] as { id: string }[]} rowKey={r => r.id} columns={[]} empty="No assets" />)
    expect(screen.getByText('No assets')).toBeInTheDocument()
  })
})

describe('AmountField', () => {
  it('reads typed amounts and echoes them in Indian grouping', () => {
    expect(parseAmount('1,20,000')).toBe(120000)
    expect(parseAmount('')).toBeNull()
    const onChange = vi.fn()
    render(<AmountField label="Take-home a month" value={120000} onChange={onChange} />)
    expect(screen.getByText(/₹1,20,000 \(₹1\.2 L\)/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Take-home a month'), { target: { value: '65,000' } })
    expect(onChange).toHaveBeenCalledWith(65000)
  })
})
