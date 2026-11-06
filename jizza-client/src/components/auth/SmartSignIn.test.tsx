import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SmartSignInPanel } from './SmartSignInPanel'
import { SmartSignInApprovePage } from '@/pages/SmartSignInApprovePage'
import { smartSignInApi, smartSignInUrl } from '@/api/smartSignIn'
import { useAuthStore } from '@/store/authStore'

const attempt = {
  id: '11111111-1111-1111-1111-111111111111',
  code: 'the-code',
  pollToken: 'the-poll-token',
  expiresAt: new Date(Date.now() + 120_000).toISOString(),
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  useAuthStore.getState().clear()
  sessionStorage.clear()
  window.history.replaceState(null, '', '/')
})

describe('Smart sign-in on the login page', () => {
  it('puts the code after the # so it never reaches a server log', () => {
    expect(smartSignInUrl('abc', 'https://jizza.example')).toBe('https://jizza.example/smart-sign-in#abc')
  })

  it('asks for a code only when the QR is clicked, then signs in once approved', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const start = vi.spyOn(smartSignInApi, 'start').mockResolvedValue(attempt)
    const status = vi.spyOn(smartSignInApi, 'status')
      .mockResolvedValueOnce({ state: 'SCANNED' })
      .mockResolvedValueOnce({ state: 'APPROVED' })
    const complete = vi.spyOn(smartSignInApi, 'complete')
      .mockResolvedValue({ mfaRequired: false, userId: 'u1', enrollmentRequired: false })
    vi.spyOn(smartSignInApi, 'cancel').mockResolvedValue(undefined)
    const onSignedIn = vi.fn()

    render(<SmartSignInPanel onSignedIn={onSignedIn} />)
    expect(start).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /Show QR code/ }))
    expect(await screen.findByAltText(/QR code for smart sign-in/)).toBeInTheDocument()

    await act(async () => { await vi.advanceTimersByTimeAsync(2100) })
    expect(await screen.findByText('Approve on your phone')).toBeInTheDocument()

    await act(async () => { await vi.advanceTimersByTimeAsync(2100) })
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(expect.objectContaining({ userId: 'u1' })))
    expect(status).toHaveBeenCalledWith(attempt.id, attempt.pollToken)
    expect(complete).toHaveBeenCalledWith(attempt.id, attempt.pollToken)
  })

  it('offers a new code once one expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.spyOn(smartSignInApi, 'start').mockResolvedValue(attempt)
    vi.spyOn(smartSignInApi, 'status').mockResolvedValue({ state: 'EXPIRED' })
    vi.spyOn(smartSignInApi, 'cancel').mockResolvedValue(undefined)
    render(<SmartSignInPanel onSignedIn={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Show QR code/ }))
    await screen.findByAltText(/QR code for smart sign-in/)
    await act(async () => { await vi.advanceTimersByTimeAsync(2100) })
    expect(await screen.findByRole('button', { name: /Get a new code/ })).toBeInTheDocument()
  })
})

describe('Approving on the phone', () => {
  it('shows the browser and approves', async () => {
    useAuthStore.getState().setAuthenticated('u1')
    window.history.replaceState(null, '', '/smart-sign-in#the-code')
    vi.spyOn(smartSignInApi, 'scan').mockResolvedValue({
      id: attempt.id, browser: 'Chrome on Windows', ip: '203.0.113.10',
      createdAt: new Date().toISOString(), expiresAt: attempt.expiresAt, sameNetwork: true, state: 'SCANNED',
    })
    const decide = vi.spyOn(smartSignInApi, 'decide').mockResolvedValue({ state: 'APPROVED' })

    render(<MemoryRouter><SmartSignInApprovePage /></MemoryRouter>)
    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument()
    expect(window.location.hash).toBe('')

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith('the-code', true))
    expect(await screen.findByText(/The other browser is signing in now/)).toBeInTheDocument()
  })

  it('asks a signed-out phone to sign in first and keeps the code for after', () => {
    window.history.replaceState(null, '', '/smart-sign-in#the-code')
    const scan = vi.spyOn(smartSignInApi, 'scan')
    render(<MemoryRouter><SmartSignInApprovePage /></MemoryRouter>)
    expect(screen.getByRole('button', { name: 'Sign in on this phone' })).toBeInTheDocument()
    expect(scan).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('jizza-smart-sign-in-code')).toBe('the-code')
  })
})
