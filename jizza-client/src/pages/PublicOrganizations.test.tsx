import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { directoryApi, listingApi, listingReviewApi } from '@/api/listings'
import type { DirectoryProfile, Listing, ListingProfile } from '@/types'
import { PublicListingPage } from './PublicListingPage'
import { ListingReviewsPage } from './ListingReviewsPage'
import { DiscoverProfilePage } from './DiscoverPage'

const profile: ListingProfile = {
  displayName: 'Acme', description: 'We build robots', category: 'technology', logoUrl: null, website: 'https://acme.example',
}

function listing(overrides: Partial<Listing> = {}): Listing {
  return {
    workspaceId: 'ws-acme', workspaceName: 'Acme', status: 'NONE', draft: null, approved: null, visible: false,
    changesSinceApproval: false, submittedAt: null, listedAt: null, history: [], submissionsLeftToday: 3,
    ...overrides,
  }
}

function mockCategories() {
  vi.spyOn(directoryApi, 'categories').mockResolvedValue({ categories: ['technology', 'other'] })
}

describe('Public listing page', () => {
  it('saves the profile and sends it for review', async () => {
    mockCategories()
    vi.spyOn(listingApi, 'get').mockResolvedValue(listing())
    const save = vi.spyOn(listingApi, 'save').mockResolvedValue(listing({ draft: profile }))
    const submit = vi.spyOn(listingApi, 'submit').mockResolvedValue(listing({ draft: profile, status: 'PENDING' }))
    renderWithSnapshot(<PublicListingPage />, makeSnapshot('ORGANIZATION', 'OWNER'))

    expect(await screen.findByText('Not listed')).toBeInTheDocument()
    expect(screen.getByText(/Nothing. The organization is not listed/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Description', { exact: false }), { target: { value: 'We build robots' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send for review' }))
    await waitFor(() => expect(submit).toHaveBeenCalled())
    expect(save.mock.calls[0][0]).toMatchObject({ displayName: 'Acme', description: 'We build robots' })
    expect(await screen.findByText('In review')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Withdraw request/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Send for review/ })).not.toBeInTheDocument()
  })

  it('shows the reviewer\'s reason after a rejection', async () => {
    mockCategories()
    vi.spyOn(listingApi, 'get').mockResolvedValue(listing({
      status: 'REJECTED', draft: profile,
      history: [{ id: 'r1', action: 'REJECTED', actorEmail: 'admin@jizza.local', note: 'Add a logo', createdAt: '2026-10-24T10:00:00Z' }],
    }))
    renderWithSnapshot(<PublicListingPage />, makeSnapshot('ORGANIZATION', 'OWNER'))
    expect(await screen.findByText(/Reviewer's reason/)).toBeInTheDocument()
    expect(screen.getAllByText('Add a logo').length).toBeGreaterThan(0)
  })

  it('keeps showing the approved version while changes wait for review', async () => {
    mockCategories()
    vi.spyOn(listingApi, 'get').mockResolvedValue(listing({
      status: 'PENDING', draft: { ...profile, description: 'New words' }, approved: profile, visible: true, changesSinceApproval: true,
    }))
    renderWithSnapshot(<PublicListingPage />, makeSnapshot('ORGANIZATION', 'OWNER'))
    expect(await screen.findByText(/People still see the approved version/)).toBeInTheDocument()
    expect(screen.getByText('We build robots')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Unlist/ })).toBeInTheDocument()
  })
})

describe('Listing reviews page', () => {
  it('needs a reason to reject, and approves a request', async () => {
    const pending = listing({ status: 'PENDING', draft: profile, submittedAt: '2026-10-24T10:00:00Z', submissionsLeftToday: null })
    vi.spyOn(listingReviewApi, 'queue').mockResolvedValue([pending])
    vi.spyOn(listingReviewApi, 'item').mockResolvedValue(pending)
    const approve = vi.spyOn(listingReviewApi, 'approve').mockResolvedValue({ ...pending, status: 'APPROVED', approved: profile })
    renderWithSnapshot(<ListingReviewsPage />, makeSnapshot('PERSONAL', 'OWNER', { platformRole: 'SUPER_ADMIN' }))

    fireEvent.click(await screen.findByRole('button', { name: /Acme/ }))
    expect(await screen.findByRole('button', { name: /Reject/ })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Reason or note', { exact: false }), { target: { value: 'Looks good' } })
    expect(screen.getByRole('button', { name: /Reject/ })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }))
    await waitFor(() => expect(approve).toHaveBeenCalledWith('ws-acme', 'Looks good'))
  })

  it('is closed to anyone but the Super Admin', () => {
    const queue = vi.spyOn(listingReviewApi, 'queue')
    renderWithSnapshot(<ListingReviewsPage />, makeSnapshot('ORGANIZATION', 'OWNER'))
    expect(screen.getByText('Only a Super Admin can review listings.')).toBeInTheDocument()
    expect(queue).not.toHaveBeenCalled()
  })
})

describe('Discover profile page', () => {
  const page = () => renderWithSnapshot(
    <Routes><Route path="/discover/:orgId" element={<DiscoverProfilePage />} /></Routes>, makeSnapshot(), '/discover/ws-acme')

  it('asks to join with a message', async () => {
    const view: DirectoryProfile = { id: 'ws-acme', profile, listedAt: '2026-10-24T10:00:00Z', member: false, pendingRequest: null }
    vi.spyOn(directoryApi, 'profile').mockResolvedValue(view)
    const ask = vi.spyOn(directoryApi, 'askToJoin').mockResolvedValue({
      id: 'jr-1', workspaceId: 'ws-acme', workspaceName: 'Acme', email: 'me@example.com', message: 'Hi',
      status: 'PENDING', createdAt: '2026-10-24T10:00:00Z', decidedAt: null,
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: /Ask to join/ }))
    fireEvent.change(screen.getByLabelText('Message (optional)', { exact: false }), { target: { value: 'Hi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send request' }))
    await waitFor(() => expect(ask).toHaveBeenCalledWith('ws-acme', 'Hi'))
  })

  it('says when the organization is not listed', async () => {
    vi.spyOn(directoryApi, 'profile').mockRejectedValue(new Error('Not found'))
    page()
    expect(await screen.findByText('Organization not found')).toBeInTheDocument()
  })
})
