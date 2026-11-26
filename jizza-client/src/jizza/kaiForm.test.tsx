import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import type { GoalView } from '@/types/finance'
import { KaiFormCard, bodyOf, missing, startValues } from './KaiFormCard'
import { execute } from './assistantStore'

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

const goal = {
  id: 'f1', form: 'goal' as const, title: 'College fund', intro: 'Check the date and save.',
  values: { type: 'education', name: 'College fund', targetAmountToday: 2500000, bogus: 'x' },
}

describe('Kai forms in chat', () => {
  it('starts from what Kai filled in and says what is still needed', () => {
    const v = startValues(goal)
    expect(v).toEqual({ type: 'education', name: 'College fund', targetAmountToday: 2500000 })
    expect(missing(goal, v)).toEqual(['Needed by'])
    expect(bodyOf(goal, { ...v, targetDate: '2034-06-01', currentValue: null, name: '  College fund ' }))
      .toEqual({ type: 'education', name: 'College fund', targetAmountToday: 2500000, targetDate: '2034-06-01' })
    // A choice Kai got wrong falls back to the first option rather than an unsavable value.
    expect(startValues({ id: 'f2', form: 'loan', values: { kind: 'MORTGAGE' } }).kind).toBe('HOME')
  })

  it('saves through the Portfolio and remembers it was saved', async () => {
    const add = vi.spyOn(financeApi, 'addGoal').mockResolvedValue({} as GoalView)
    const { unmount } = render(<KaiFormCard card={goal} />, { wrapper: wrap() })
    expect(screen.getByText('Check the date and save.')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Needed by'), { target: { value: '2034-06-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith(
      { type: 'education', name: 'College fund', targetAmountToday: 2500000, targetDate: '2034-06-01' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Goal saved')
    unmount()
    render(<KaiFormCard card={goal} />, { wrapper: wrap() })
    expect(screen.getByRole('status')).toHaveTextContent('Goal saved')
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })

  it('shows the server error and keeps the values', async () => {
    vi.spyOn(financeApi, 'addLoan').mockRejectedValue(new Error('outstanding is needed.'))
    render(<KaiFormCard card={{ id: 'f3', form: 'loan', values: { kind: 'HOME', outstanding: 3500000 } }} />, { wrapper: wrap() })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('comes off the reply as a card, and an unknown form is ignored', () => {
    const out = execute([
      { type: 'finance_form', payload: goal },
      { type: 'finance_form', payload: { id: '', form: 'goal' } },
    ], false)
    expect(out.kaiForms).toEqual([goal])
    const { container } = render(<KaiFormCard card={{ id: 'x', form: 'tax' as never }} />, { wrapper: wrap() })
    expect(container).toBeEmptyDOMElement()
  })
})
