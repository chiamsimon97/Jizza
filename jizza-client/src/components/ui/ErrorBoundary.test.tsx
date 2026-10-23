import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ErrorBoundary } from './ErrorBoundary'
import { LoadError } from './EmptyState'

function Broken({ broken }: { broken: boolean }): JSX.Element {
  if (broken) throw new Error('boom')
  return <p>Working page</p>
}

describe('ErrorBoundary', () => {
  it('shows a message instead of a blank window when a page throws', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary><Broken broken /></ErrorBoundary>)
    expect(screen.getByRole('alert')).toHaveTextContent('This page hit a problem')
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
  })

  it('recovers when the user moves to another page', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { rerender } = render(<ErrorBoundary resetKey="/a"><Broken broken /></ErrorBoundary>)
    rerender(<ErrorBoundary resetKey="/b"><Broken broken={false} /></ErrorBoundary>)
    expect(screen.getByText('Working page')).toBeInTheDocument()
  })

  it('renders its children when nothing goes wrong', () => {
    render(<ErrorBoundary><Broken broken={false} /></ErrorBoundary>)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('LoadError', () => {
  it('says what failed and offers to try again', () => {
    const retry = vi.fn()
    render(<LoadError what="your tasks" onRetry={retry} />)
    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load your tasks")
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(retry).toHaveBeenCalledOnce()
  })
})
