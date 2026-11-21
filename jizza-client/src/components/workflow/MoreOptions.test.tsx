import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FoldSection, MoreOptions, countOf } from './MoreOptions'

describe('MoreOptions', () => {
  it('keeps the details folded with a summary, and opens them on demand', () => {
    render(<MoreOptions summary="Dropdown · Book Ticket"><input aria-label="Kind" /></MoreOptions>)
    const toggle = screen.getByRole('button', { name: /More options · Dropdown · Book Ticket/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('textbox', { name: 'Kind' })).not.toBeInTheDocument()
    // jsdom applies no CSS: a display class such as flex would show the folded part in a browser.
    const folded = document.getElementById(toggle.getAttribute('aria-controls')!)!
    expect(folded.className).toBe('hidden')
    fireEvent.click(toggle)
    expect(folded.className).not.toMatch(/\bhidden\b/)
    expect(screen.getByRole('textbox', { name: 'Kind' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Hide more options' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('opens by itself when something inside needs fixing, and keeps a badge if folded again', () => {
    const { rerender } = render(<MoreOptions summary="Text"><input aria-label="Field" /></MoreOptions>)
    expect(screen.queryByRole('textbox', { name: 'Field' })).not.toBeInTheDocument()
    rerender(<MoreOptions summary="Text" hasError><input aria-label="Field" /></MoreOptions>)
    expect(screen.getByRole('textbox', { name: 'Field' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Hide more options' }))
    expect(screen.getByTestId('needs-attention')).toBeInTheDocument()
  })

  it('starts open when there is already a problem', () => {
    render(<FoldSection title="Authentication" summary="bearer token" hasError><input aria-label="Token" /></FoldSection>)
    expect(screen.getByRole('textbox', { name: 'Token' })).toBeVisible()
  })

  it('counts what a folded section holds', () => {
    expect(countOf(0, 'header')).toBe('none')
    expect(countOf(1, 'header')).toBe('1 header')
    expect(countOf(3, 'header')).toBe('3 headers')
  })
})
