import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { JournalEntry } from '@/types/vita'
import { localDay } from '../medicines'
import { ShareContext, scopeOf } from '../share'
import { Journal } from './Journal'

const today = localDay(0)
const entry: JournalEntry = {
  id: 'j1', personId: 'p-mum', entryDate: today, mood: 2, energy: 3, pain: 6, symptoms: ['Headache', 'Tingling'], notes: 'Slept badly',
  source: 'manual', createdByUserId: 'u1', createdAt: `${today}T03:00:00Z`, updatedAt: null,
}

afterEach(() => vi.restoreAllMocks())

describe('Journal', () => {
  beforeEach(() => { vi.spyOn(vitaApi, 'journal').mockResolvedValue([]) })

  it('saves a day only when Save is pressed, with what was picked', async () => {
    const put = vi.spyOn(vitaApi, 'putJournal').mockResolvedValue(entry)
    renderWithSnapshot(<Journal personId="p-mum" personName="Mother" readOnly={false} />, makeSnapshot())
    const save = await screen.findByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Mood: Good' }))
    expect(screen.getByRole('button', { name: 'Mood: Good' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Energy: Low' }))
    fireEvent.change(screen.getByLabelText(/^Pain/), { target: { value: '4' } })
    expect(screen.getByText('Pain: 4 of 10, moderate')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Headache' }))
    fireEvent.change(screen.getByLabelText('Another symptom'), { target: { value: 'Tingling feet' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    expect(screen.getByRole('button', { name: 'Tingling feet' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: '  Walked in the evening ' } })
    expect(put).not.toHaveBeenCalled()
    fireEvent.click(save)
    await waitFor(() => expect(put).toHaveBeenCalledWith('p-mum', today, {
      mood: 4, energy: 2, pain: 4, symptoms: ['Headache', 'Tingling feet'], notes: 'Walked in the evening',
    }))
    expect(await screen.findByText('Saved.')).toBeInTheDocument()
  })

  it('marks days with entries in the month grid and opens one to change or remove', async () => {
    vi.mocked(vitaApi.journal).mockResolvedValue([entry])
    const remove = vi.spyOn(vitaApi, 'removeJournal').mockResolvedValue(undefined)
    renderWithSnapshot(<Journal personId="p-mum" personName="Mother" readOnly={false} />, makeSnapshot())
    expect(await screen.findByRole('gridcell', { name: /noted, mood low/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(/1 day noted/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mood: Low' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Tingling' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Remove this day' }))
    await waitFor(() => expect(remove).toHaveBeenCalledWith('p-mum', today))
    // A day still to come cannot be picked, and the next month is out of reach.
    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled()
  })

  it('shows a shared journal in words, with nothing to change', async () => {
    vi.mocked(vitaApi.journal).mockResolvedValue([entry])
    const scope = scopeOf({ personId: 'p-mum', displayName: 'Mother', asSelf: false, managerEmail: 'a@b.c', sections: { journal: 'view' } } as never, 'p-mum')
    renderWithSnapshot(
      <ShareContext.Provider value={scope}><Journal personId="p-mum" personName="Mother" readOnly /></ShareContext.Provider>, makeSnapshot())
    expect(await screen.findByText('6 of 10, moderate')).toBeInTheDocument()
    expect(screen.getByText('Headache, Tingling')).toBeInTheDocument()
    expect(screen.getByText('Slept badly')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(screen.getByText(/Shared with you/)).toBeInTheDocument()
  })
})
