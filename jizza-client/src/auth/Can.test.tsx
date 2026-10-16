import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { Can, VIEW_ONLY } from './Can'

describe('<Can>', () => {
  it('hide mode renders the children when granted', () => {
    renderWithSnapshot(<Can permission="members_invite"><button>Add member</button></Can>, makeSnapshot('ORGANIZATION', 'OWNER'))
    expect(screen.getByRole('button', { name: 'Add member' })).toBeEnabled()
  })

  it('hide mode renders nothing when not granted', () => {
    renderWithSnapshot(<Can permission="members_invite"><button>Add member</button></Can>, makeSnapshot('ORGANIZATION', 'USER'))
    expect(screen.queryByRole('button', { name: 'Add member' })).not.toBeInTheDocument()
  })

  it('disable mode leaves the children working when granted', () => {
    const onClick = vi.fn()
    renderWithSnapshot(
      <Can permission="members_remove" mode="disable"><button onClick={onClick}>Remove</button></Can>,
      makeSnapshot('ORGANIZATION', 'ADMIN'),
    )
    const button = screen.getByRole('button', { name: 'Remove' })
    expect(button).toBeEnabled()
    expect(button).not.toHaveAttribute('title')
    fireEvent.click(button)
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('disable mode renders the children disabled with the view-only tooltip when not granted', () => {
    const onClick = vi.fn()
    renderWithSnapshot(
      <Can permission="members_remove" mode="disable"><button onClick={onClick}>Remove</button></Can>,
      makeSnapshot('ORGANIZATION', 'DEVELOPER'),
    )
    const button = screen.getByRole('button', { name: 'Remove' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', VIEW_ONLY)
    expect(VIEW_ONLY).toBe('You have view-only access')
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })
})
