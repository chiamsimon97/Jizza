import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StepUpDialog } from './StepUpDialog'
import { authApi } from '@/api/auth'
import * as apiModule from '@/lib/api'

afterEach(() => vi.restoreAllMocks())

function openDialog() {
  let handler: (() => Promise<boolean>) | null = null
  vi.spyOn(apiModule, 'setStepUpHandler').mockImplementation(h => { handler = h })
  render(<StepUpDialog />)
  let result!: Promise<boolean>
  act(() => { result = handler!() })
  return () => result
}

describe('Confirm it\'s you', () => {
  it('confirms for this action only unless Auto approve is ticked', async () => {
    const stepUp = vi.spyOn(authApi, 'stepUp').mockResolvedValue({ message: 'ok' })
    const result = openDialog()
    expect(screen.queryByRole('radiogroup')).toBeNull()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(stepUp).toHaveBeenCalledWith('secret', 0))
    await expect(result()).resolves.toBe(true)
  })

  it('offers 15 min, 1 hr and 3 hr with 15 min chosen, and sends the choice', async () => {
    const stepUp = vi.spyOn(authApi, 'stepUp').mockResolvedValue({ message: 'ok' })
    openDialog()
    fireEvent.click(screen.getByLabelText('Auto approve'))
    expect(screen.getByRole('radio', { name: '15 min' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('radio', { name: '1 hr' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('radio', { name: '3 hr' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: '1 hr' }))
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(stepUp).toHaveBeenCalledWith('secret', 60))
  })
})
