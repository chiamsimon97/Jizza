import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { VitaForm, VitaProfileView } from '@/types/vita'
import { VitaFormCard, vitaBodyOf, vitaStartValues } from './VitaFormCard'
import { execute } from './assistantStore'

const wrap = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}
const blood: VitaForm = { id: 'vf1', form: 'profile', personId: 'p-me', personName: 'Me', values: { bloodGroup: 'o_pos' } }

afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

describe('VitaFormCard', () => {
  it('saves nothing until Save is pressed, then only the fields on the card', async () => {
    const save = vi.spyOn(vitaApi, 'saveProfile').mockResolvedValue({} as VitaProfileView)
    render(<VitaFormCard card={blood} />, { wrapper: wrap() })
    expect(screen.getByText('Not saved yet. Check it, then press Save.')).toBeInTheDocument()
    expect(screen.getByLabelText('Blood group (optional)')).toHaveValue('o_pos')
    expect(screen.queryByLabelText(/Hospital phone/)).toBeNull()
    expect(save).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('p-me', { bloodGroup: 'o_pos' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Saved to the profile')
  })

  it('an emergency contact needs a name and a phone', async () => {
    const add = vi.spyOn(vitaApi, 'addContact').mockResolvedValue({ id: 'c', name: 'Ravi', relation: null, phone: '1', sortOrder: 0 })
    render(<VitaFormCard card={{ id: 'vf2', form: 'emergency_contact', personId: 'p-mum', values: { name: 'Ravi' } }} />, { wrapper: wrap() })
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '+91 00000 00010' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-mum', { name: 'Ravi', phone: '+91 00000 00010' }))
  })

  it('turns yes/no into true/false and leaves out unknown choices', () => {
    const card: VitaForm = { id: 'x', form: 'profile', personId: 'p', values: { organDonor: true, bloodGroup: 'Z+' } }
    const start = vitaStartValues(card)
    expect(start).toEqual({ organDonor: 'yes' })
    expect(vitaBodyOf(card, start)).toEqual({ organDonor: true })
  })

  it('a vita_form action in a reply becomes a card', () => {
    const { vitaForms } = execute([{ type: 'vita_form', payload: blood }], false)
    expect(vitaForms).toEqual([blood])
  })
})
