import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { ApiError } from '@/lib/api'
import type { HoldingDetail, HoldingsView, TreeNode, TreeView } from '@/types/finance'
import { EarmarkEditor, fromMicro, splitProblem, toMicro } from './EarmarkEditor'
import { Holdings } from './Holdings'
import { TreeEditor, treeProblems } from './AssetTree'
import { ruleWords } from './Rules'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

afterEach(() => vi.restoreAllMocks())

/** Fixture A: 100 shares in one demat, lots of Mar 2023, Aug 2024 and Sep 2026, earmarked 30 / 30 / 40. */
const HOLDING: HoldingDetail = {
  accountId: 'acc', accountLabel: 'Demat', personId: 'me', instrumentId: 'ins', name: 'HDFC Bank', type: 'stock',
  assetClass: 'equity', subCategory: 'large_cap', symbol: 'HDFCBANK', isin: 'INE040A01034', units: 100, value: 200000,
  valueSource: 'price', avgCost: 1675, price: 2000, priceDate: '2026-11-24',
  lots: [
    { id: 'A', acquiredOn: '2023-03-15', dateKnown: true, qty: 40, remaining: 40, costPerUnit: 1600, termToday: 'lt', gainToday: 16000 },
    { id: 'B', acquiredOn: '2024-08-15', dateKnown: true, qty: 30, remaining: 30, costPerUnit: 1550, termToday: 'lt', gainToday: 13500 },
    { id: 'C', acquiredOn: '2026-09-15', dateKnown: true, qty: 30, remaining: 30, costPerUnit: 1900, termToday: 'st', gainToday: 3000 },
  ],
  saleNote: 'A sale here uses the March 2023 lot first.',
  earmarks: [
    { goalId: 'p1', name: 'House', unassigned: false, units: 30, value: 60000 },
    { goalId: 'p2', name: 'Studies', unassigned: false, units: 30, value: 60000 },
    { goalId: 'p3', name: 'Trip', unassigned: false, units: 40, value: 80000 },
  ],
  goals: [
    { id: 'p1', name: 'House', unassigned: false, short: false },
    { id: 'p2', name: 'Studies', unassigned: false, short: false },
    { id: 'p3', name: 'Trip', unassigned: false, short: true },
    { id: 'u', name: 'Unassigned', unassigned: true },
  ],
  directShares: true, history: [], needsLook: [],
}

describe('earmark sums', () => {
  it('adds units exactly to six decimals', () => {
    expect(toMicro('0.1')! + toMicro('0.2')!).toBe(toMicro('0.3'))
    expect(fromMicro(toMicro('12.500000')!)).toBe('12.5')
    expect(toMicro('1.1234567')).toBeNull()
    expect(splitProblem({ a: '30', b: '30', c: '40' }, 100)).toBeNull()
    expect(splitProblem({ a: '30', b: '30', c: '30' }, 100)).toBe('The goals add up to 90 units; the account holds 100.')
  })
})

describe('earmark editor', () => {
  it('shows the total, the FIFO lots and refuses a split that does not add up', async () => {
    vi.spyOn(financeApi, 'holding').mockResolvedValue(HOLDING)
    render(<EarmarkEditor accountId="acc" instrumentId="ins" />, { wrapper: wrap() })
    expect(await screen.findByTestId('earmark-total')).toHaveTextContent('Total 100 / 100 units')
    expect(screen.getByText('A sale here uses the March 2023 lot first.')).toBeInTheDocument()
    const lots = within(screen.getByRole('table', { name: 'Tax lots' })).getAllByRole('row')
    expect(lots[1]).toHaveTextContent('2023')
    expect(lots[3]).toHaveTextContent('Short term')

    fireEvent.change(screen.getByLabelText('Units for Trip'), { target: { value: '30' } })
    expect(screen.getByTestId('earmark-total')).toHaveTextContent('Total 90 / 100 units')
    expect(screen.getByRole('alert')).toHaveTextContent('add up to 90 units')
    expect(screen.getByRole('button', { name: 'Save split' })).toBeDisabled()
  })

  it('asks before keeping shares in a goal under three years away, then saves with the choice', async () => {
    vi.spyOn(financeApi, 'holding').mockResolvedValue(HOLDING)
    const put = vi.spyOn(financeApi, 'putEarmarks')
      .mockRejectedValueOnce(new ApiError(409, { type: 'https://jizza.app/problems/finance-short-goal-shares', title: 'Short goal', status: 409,
        detail: 'This goal is under 3 years away. Shares can fall sharply in that time.' }))
      .mockResolvedValueOnce(HOLDING)
    render(<EarmarkEditor accountId="acc" instrumentId="ins" />, { wrapper: wrap() })
    await screen.findByTestId('earmark-total')
    fireEvent.change(screen.getByLabelText('Units for House'), { target: { value: '20' } })
    fireEvent.change(screen.getByLabelText('Units for Trip'), { target: { value: '50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save split' }))
    expect(await screen.findByRole('dialog', { name: 'Shares in a short goal' })).toHaveTextContent('under 3 years away')
    fireEvent.click(screen.getByRole('button', { name: 'Keep the shares' }))
    await waitFor(() => expect(put).toHaveBeenCalledTimes(2))
    expect(put.mock.calls[0][0].keepShares).toBe(false)
    expect(put.mock.calls[1][0]).toMatchObject({ keepShares: true, split: expect.arrayContaining([{ goalId: 'p3', units: 50 }]) })
  })
})

describe('holdings list', () => {
  it('puts holdings that need a look first and opens one', async () => {
    const view: HoldingsView = {
      asOf: '2026-11-26', priceAsOf: '2026-11-24', stalePrices: false, value: 200000, cost: 167500, pnl: 32500,
      rows: [{
        instrumentId: 'ins', name: 'HDFC Bank', type: 'stock', assetClass: 'equity', subCategory: null, symbol: 'HDFCBANK', isin: null,
        units: 100, value: 200000, valueSource: 'price', priceDate: '2026-11-24', pnl: 32500, pnlPct: 19.4,
        accounts: [{ accountId: 'acc', label: 'Demat', personId: 'me', personName: 'Me', units: 100, value: 200000 }],
        split: [{ goalId: 'u', name: 'Unassigned', unassigned: true, units: 100 }], unassignedUnits: 100,
        needsLook: ['Units were sold outside Kai. Check which goal the sale came from.'],
      }],
      assets: [], goals: [{ id: 'u', name: 'Unassigned', type: 'unassigned', unassigned: true, value: 200000 }],
      needsLook: [{ instrumentId: 'ins', name: 'HDFC Bank', reason: 'Units were sold outside Kai. Check which goal the sale came from.' }],
    }
    vi.spyOn(financeApi, 'holdings').mockResolvedValue(view)
    vi.spyOn(financeApi, 'holding').mockResolvedValue(HOLDING)
    render(<Holdings person={null} goal={null} onOpen={() => {}} />, { wrapper: wrap() })
    expect(await screen.findByText(/Needs a look \(1\)/)).toBeInTheDocument()
    expect(screen.getByText('Units were sold outside Kai. Check which goal the sale came from.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open' }))
    expect(await screen.findByTestId('earmark-total')).toHaveTextContent('Total 100 / 100 units')
  })
})

const node = (id: string, parentId: string | null, name: string, targetPct: number | null, kind: TreeNode['kind'] = parentId ? 'sub_category' : 'asset_class'): TreeNode =>
  ({ id, parentId, name, targetPct, kind, key: id })

describe('asset tree', () => {
  const nodes = [node('eq', null, 'Equity', 70), node('eq/l', 'eq', 'Large cap', 60), node('eq/m', 'eq', 'Mid cap', 40), node('debt', null, 'Debt', 30)]

  it('lists every group that does not add up to 100%', () => {
    expect(treeProblems(nodes)).toEqual([])
    expect(treeProblems(nodes.map(n => (n.id === 'eq/m' ? { ...n, targetPct: 35 } : n)))).toEqual(['The parts of Equity add up to 95%, not 100%.'])
    expect(treeProblems([...nodes, node('ins', 'eq/l', 'A fund', null, 'instrument')])).toEqual([])
  })

  it('keeps Save off until siblings add up to 100', async () => {
    const tree: TreeView = { goalId: 'g', goalName: 'House', goalType: 'home', bucket: 'long', saved: false, neverRebalanced: false, nodes, problems: [] }
    vi.spyOn(financeApi, 'tree').mockResolvedValue(tree)
    const save = vi.spyOn(financeApi, 'saveTree').mockResolvedValue({ ...tree, saved: true })
    render(<TreeEditor goalId="g" />, { wrapper: wrap() })
    const equity = await screen.findByLabelText('Target for Equity')
    fireEvent.change(equity, { target: { value: '75' } })
    expect(screen.getByRole('alert')).toHaveTextContent('The asset classes add up to 105%, not 100%.')
    expect(screen.getByRole('button', { name: /Save as this goal/ })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Target for Debt'), { target: { value: '25' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Save as this goal/ }))
    await waitFor(() => expect(save).toHaveBeenCalled())
    expect(save.mock.calls[0][1].find(n => n.id === 'eq')?.targetPct).toBe(75)
  })
})

describe('rule words', () => {
  it('says each rule plainly', () => {
    expect(ruleWords('stop_loss', { kind: 'trailing', pct: 10 })).toBe('Trailing, 10% below')
    expect(ruleWords('rebalance_band', { mode: 'five_twenty_five' })).toBe('5/25 rule')
    expect(ruleWords('execution_mode', { mode: 'observe' })).toBe('Observe')
    expect(ruleWords('execution_mode', null)).toBe('Off')
  })
})

