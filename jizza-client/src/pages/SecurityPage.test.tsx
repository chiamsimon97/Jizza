import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { renderWithSnapshot, makeSnapshot } from '@/test/utils'
import { DataCard } from './SecurityPage'
import { dataApi } from '@/api/data'

describe('Your data', () => {
  it('only deletes after the exact phrase is typed', async () => {
    const hardDelete = vi.spyOn(dataApi, 'hardDelete').mockResolvedValue({ message: 'ok' })
    renderWithSnapshot(<DataCard />, makeSnapshot())

    fireEvent.click(screen.getByRole('button', { name: /Delete everything/ }))
    const confirm = screen.getByRole('button', { name: 'Delete my account' })
    expect(confirm).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Confirmation'), { target: { value: 'delete everything' } })
    expect(confirm).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Confirmation'), { target: { value: 'DELETE EVERYTHING' } })
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)
    await waitFor(() => expect(hardDelete).toHaveBeenCalledWith('DELETE EVERYTHING'))
  })

  it('shows why an export failed', async () => {
    vi.spyOn(dataApi, 'export').mockRejectedValue(new Error('Could not reach the server'))
    renderWithSnapshot(<DataCard />, makeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: /Download my data/ }))
    expect(await screen.findByText('Could not reach the server')).toBeInTheDocument()
  })

  it('cancelling the delete puts the card back', () => {
    renderWithSnapshot(<DataCard />, makeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: /Delete everything/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Delete my account' })).toBeNull()
  })
})
