import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { HelpPage } from './HelpPage'
import { HelpMarkdown } from '@/help/HelpMarkdown'

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path="/help/:slug?" element={<HelpPage />} /></Routes>
    </MemoryRouter>,
  )
}

describe('HelpPage', () => {
  it('lists the guides by section and opens one', () => {
    renderAt('/help')
    const section = screen.getByRole('region', { name: 'Web automation' })
    fireEvent.click(within(section).getByRole('link', { name: /Browser recording/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Browser recording' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Record a task' })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'On this page' })).getByRole('link', { name: 'Safety and privacy' })).toBeInTheDocument()
  })

  it('filters the list by a search word', () => {
    renderAt('/help')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search the guides' }), { target: { value: 'nothing-like-this' } })
    expect(screen.getByText('No guide matches')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search the guides' }), { target: { value: 'CAPTCHA' } })
    expect(screen.getByRole('link', { name: /Browser recording/ })).toBeInTheDocument()
  })

  it('goes back to the list for an unknown guide', () => {
    renderAt('/help/no-such-guide')
    expect(screen.getByRole('heading', { level: 1, name: 'Help' })).toBeInTheDocument()
  })
})

describe('HelpMarkdown', () => {
  function renderText(text: string) {
    render(<MemoryRouter><HelpMarkdown text={text} /></MemoryRouter>)
  }

  it('renders tables, nested lists and links', () => {
    renderText('| A | B |\n| --- | --- |\n| **one** | two |\n\n1. First\n   - inner\n2. Second\n\n[Recordings](/recordings) and [site](https://example.com)')
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'one' })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem').map(li => li.textContent)).toEqual(['Firstinner', 'inner', 'Second'])
    expect(screen.getByRole('link', { name: 'Recordings' })).toHaveAttribute('href', '/recordings')
    expect(screen.getByRole('link', { name: 'site' })).toHaveAttribute('target', '_blank')
  })

  it('keeps the numbers of a list that continues an earlier one', () => {
    renderText('1. One\n\n### Next\n\n4. Four\n5. Five')
    const lists = screen.getAllByRole('list')
    expect(lists[0]).not.toHaveAttribute('start')
    expect(lists[1]).toHaveAttribute('start', '4')
  })

  it('shows HTML and script links as text, never as markup', () => {
    renderText('<img src=x onerror=alert(1)> [bad](javascript:alert(1))')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText(/<img src=x/)).toBeInTheDocument()
  })
})
