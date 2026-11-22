import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { UserSteps } from '@/types'
import { UserStepsCard, cardLinks, safePage } from './UserStepsCard'
import { withoutLinks } from './Markdown'

const KITE = 'https://mcp.kite.trade/authorize?session_id=kitemcp-497e%7C1793.FPHS8q%3D'

const card: UserSteps = {
  title: 'Sign in to Kite',
  source: 'auto',
  steps: [
    { label: 'Log in to Kite', button: { kind: 'link', label: 'Log in to Kite', url: KITE } },
    { label: 'Come back here and continue', button: { kind: 'reply', label: 'Done, continue', text: "I've logged in to Kite. Please continue." } },
  ],
}

describe('UserStepsCard', () => {
  it('opens the sign-in page, then sends the done reply', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const onSend = vi.fn()
    render(<UserStepsCard card={card} onSend={onSend} />)

    fireEvent.click(screen.getByRole('button', { name: /Log in to Kite/ }))
    expect(open).toHaveBeenCalledWith(KITE, '_blank', 'noopener,noreferrer')

    fireEvent.click(screen.getByRole('button', { name: /Done, continue/ }))
    expect(onSend).toHaveBeenCalledWith("I've logged in to Kite. Please continue.")
    expect(screen.getByRole('button', { name: /Sent/ })).toBeDisabled()
  })

  it('never puts a script link on a button', () => {
    const bad: UserSteps = { title: 'x', steps: [{ label: 'Open', button: { kind: 'link', label: 'Go', url: 'javascript:alert(1)' } }] }
    render(<UserStepsCard card={bad} onSend={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /Go/ })).toBeNull()
  })

  it('takes the card\'s links out of the reply text', () => {
    const reply = `If you're happy to proceed:\n\n**[Login to Kite](${KITE})**\n\nClick it, then [log in here](${KITE}) and authorize.`
    const shown = withoutLinks(reply, cardLinks([card]))
    expect(shown).not.toContain(KITE)
    expect(shown).toContain('then log in here and authorize')
    expect(shown).not.toContain('Login to Kite')
  })

  it('opens a connector\'s page to sign in again, inside the app', () => {
    const reconnect: UserSteps = {
      title: 'Sign in to Kite again',
      steps: [
        { label: 'Sign in to Kite again', button: { kind: 'page', label: 'Open Kite', path: '/connectors/mcp/m1' } },
        { label: 'Come back here and continue', button: { kind: 'reply', label: 'Done, continue', text: 'Signed in.' } },
      ],
    }
    render(
      <MemoryRouter initialEntries={['/chat']}>
        <Routes>
          <Route path="/chat" element={<UserStepsCard card={reconnect} onSend={vi.fn()} />} />
          <Route path="/connectors/mcp/:id" element={<p>Kite's page</p>} />
        </Routes>
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: /Open Kite/ }))
    expect(screen.getByText("Kite's page")).toBeInTheDocument()
  })

  it('opens only the app\'s own connector and device pages', () => {
    expect(safePage('/connectors/mcp/m1')).toBe(true)
    expect(safePage('/settings/devices?device=d1')).toBe(true)
    expect(safePage('//evil.test/connectors/')).toBe(false)
    expect(safePage('/settings/security')).toBe(false)
    expect(safePage('https://evil.test/connectors/x')).toBe(false)
    const bad: UserSteps = { title: 'x', steps: [{ label: 'Open', button: { kind: 'page', label: 'Go', path: '/account/delete' } }] }
    render(<UserStepsCard card={bad} onSend={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /Go/ })).toBeNull()
  })
})
