import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { UserSteps } from '@/types'
import { UserStepsCard, cardLinks } from './UserStepsCard'
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
})
