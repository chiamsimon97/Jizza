import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { HealthClaim, HealthPolicy, VitaPerson } from '@/types/vita'
import { ShareContext, scopeOf } from '../share'
import { Insurance } from './Insurance'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const mother: VitaPerson = { ...me, id: 'p-mum', displayName: 'Mother', kind: 'parent', sortOrder: 1 }
const father: VitaPerson = { ...me, id: 'p-dad', displayName: 'Father', kind: 'parent', sortOrder: 2 }

// Fixture F: a family floater of ₹10,00,000 covering You, Mother and Father, renewing 2027-03-31.
const floater: HealthPolicy = {
  id: 'pol1', personId: 'p-me', kind: 'health', providerName: 'Sample Health Insurer', sumAssured: 1000000, deductible: null, premium: 24000,
  premiumFrequency: 'yearly', renewalDate: '2027-03-31', policyEndDate: null, employerProvided: false, coveredPersonIds: ['p-me', 'p-mum', 'p-dad'],
  floater: true, policyYear: { from: '2026-04-01', to: '2027-03-31' }, usedThisYear: 105000, leftThisYear: 895000, renewalReminderOn: '2027-03-01',
  detail: { policyId: 'pol1', policyNumberMasked: '•••• 3456', cardNumberMasked: null, tpaName: 'Sample TPA', cashlessCardDocumentId: 'doc9', roomRentLimit: 5000, copayPct: 10, waitingPeriods: [{ text: 'Pre-existing conditions: 3 years' }], networkHospitalsNote: null },
  claimCount: 1, shared: false,
}
const scheme: HealthPolicy = {
  ...floater, id: 'pol2', kind: 'government_scheme', providerName: 'PM-JAY', sumAssured: null, premium: null, floater: false, coveredPersonIds: ['p-dad'],
  policyYear: null, usedThisYear: null, leftThisYear: null, renewalReminderOn: null, renewalDate: null, claimCount: 0,
  detail: { ...floater.detail, policyId: 'pol2', policyNumberMasked: null, cashlessCardDocumentId: null, tpaName: null, roomRentLimit: null, copayPct: null, waitingPeriods: [] },
}
const settled: HealthClaim = {
  id: 'cl1', personId: 'p-dad', policyId: 'pol1', eventId: null, visitId: null, admittedOn: '2026-08-02', dischargedOn: '2026-08-04',
  amountClaimed: 120000, amountPaid: 105000, status: 'settled', documentIds: [], notes: null, outOfPocket: 15000, policyYearTo: '2027-03-31',
  policy: { kind: 'health', providerName: 'Sample Health Insurer' },
}

beforeEach(() => {
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me, mother, father])
  vi.spyOn(vitaApi, 'policies').mockResolvedValue([floater, scheme])
  vi.spyOn(vitaApi, 'claims').mockResolvedValue([settled])
  vi.spyOn(vitaApi, 'events').mockResolvedValue([])
  vi.spyOn(vitaApi, 'documents').mockResolvedValue([])
})
afterEach(() => vi.restoreAllMocks())

const renderView = (onOpenDocument = vi.fn()) =>
  renderWithSnapshot(<Insurance personId="p-dad" personName="Father" readOnly={false} onOpenDocument={onOpenDocument} />, makeSnapshot())

describe('Insurance', () => {
  it('shows Fixture F: one floater for three people, used and left this policy year, and the claim', async () => {
    const open = vi.fn()
    renderView(open)
    const card = await screen.findByRole('region', { name: 'Sample Health Insurer' })
    const c = within(card)
    expect(c.getByText('Floater')).toBeInTheDocument()
    expect(c.getByText('•••• 3456')).toBeInTheDocument()
    expect(c.getByText('₹10,00,000')).toBeInTheDocument()
    expect(c.getByText('You, Mother, Father')).toBeInTheDocument()
    expect(c.getByText('Used this policy year').nextSibling).toHaveTextContent('₹1,05,000')
    expect(c.getByText('Left this policy year').nextSibling).toHaveTextContent('₹8,95,000')
    expect(c.getByText(/Reminder on 1 Mar 2027/)).toBeInTheDocument()
    expect(c.getByText('Pre-existing conditions: 3 years')).toBeInTheDocument()
    fireEvent.click(c.getByRole('button', { name: 'Cashless card' }))
    expect(open).toHaveBeenCalledWith('doc9')
    // Government cover sits beside it, with no sum it has to show.
    expect(within(screen.getByRole('region', { name: 'PM-JAY' })).getByText('As the scheme sets')).toBeInTheDocument()
    // The claim: amounts, out of pocket, the status in words.
    expect(await screen.findByText('₹15,000')).toBeInTheDocument()
    expect(screen.getByText('Settled')).toBeInTheDocument()
    expect(screen.getByText(/Counts in the policy year ending 31 Mar 2027/)).toBeInTheDocument()
    expect(screen.getByText(/Kai’s Protection view reads these policies/)).toBeInTheDocument()
  })

  it('adds a floater policy covering the people ticked, with the holder among them', async () => {
    const add = vi.spyOn(vitaApi, 'addPolicy').mockResolvedValue(floater)
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Add a policy' }))
    const submit = screen.getByRole('button', { name: 'Add policy' })
    expect(submit).toBeDisabled() // no sum insured yet
    fireEvent.change(screen.getByLabelText('Insurer'), { target: { value: 'Sample Health Insurer' } })
    fireEvent.change(screen.getByLabelText('Sum insured'), { target: { value: '1000000' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Mother' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Father' })) // untick the person the form started with
    fireEvent.click(submit)
    await waitFor(() => expect(add).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'health', providerName: 'Sample Health Insurer', sumAssured: 1000000, personId: 'p-mum', coveredPersonIds: ['p-mum'],
    })))
  })

  it('lets a government scheme go without a sum insured', async () => {
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Add a policy' }))
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'government_scheme' } })
    expect(screen.getByLabelText('Sum insured (optional)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add policy' })).toBeEnabled()
  })

  it('sends a policy number only when typed, and checks how it looks', async () => {
    const put = vi.spyOn(vitaApi, 'putPolicyDetail').mockResolvedValue(floater.detail)
    renderView()
    fireEvent.click(within(await screen.findByRole('region', { name: 'Sample Health Insurer' })).getByRole('button', { name: 'Card and policy details' }))
    fireEvent.change(screen.getByLabelText('Card number'), { target: { value: 'AB<12' } })
    expect(screen.getByRole('button', { name: 'Save details' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Card number'), { target: { value: 'CARD 7788' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }))
    await waitFor(() => expect(put).toHaveBeenCalled())
    const body = put.mock.calls[0][1]
    expect(body.cardNumber).toBe('CARD 7788')
    expect('policyNumber' in body).toBe(false)
    expect(body.waitingPeriods).toEqual([{ text: 'Pre-existing conditions: 3 years' }])
  })

  it('needs the amount paid before a claim is settled', async () => {
    const add = vi.spyOn(vitaApi, 'addClaim').mockResolvedValue(settled)
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Add a claim' }))
    fireEvent.change(screen.getByLabelText('Admitted on'), { target: { value: '2026-08-02' } })
    fireEvent.change(screen.getByLabelText('Discharged on (optional)'), { target: { value: '2026-08-01' } })
    expect(screen.getByText('Discharge cannot be before admission')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Discharged on (optional)'), { target: { value: '2026-08-04' } })
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'settled' } })
    expect(screen.getByRole('button', { name: 'Add claim' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Amount claimed'), { target: { value: '120000' } })
    fireEvent.change(screen.getByLabelText('Amount paid'), { target: { value: '105000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add claim' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-dad', expect.objectContaining({
      policyId: 'pol1', admittedOn: '2026-08-02', dischargedOn: '2026-08-04', amountClaimed: 120000, amountPaid: 105000, status: 'settled',
    })))
  })

  it('shows a shared record read-only for policies, with no used or left figures', async () => {
    vi.mocked(vitaApi.policies).mockResolvedValue([{ ...floater, shared: true, usedThisYear: null, leftThisYear: null, claimCount: undefined }])
    renderWithSnapshot(
      <ShareContext.Provider value={scopeOf({ personId: 'p-dad', name: 'Father', personName: 'Father', asSelf: false, managerEmail: 'raj@example.com', sections: { insurance: 'view' } }, 'p-dad')}>
        <Insurance personId="p-dad" personName="Father" readOnly={false} onOpenDocument={vi.fn()} />
      </ShareContext.Provider>, makeSnapshot())
    const card = await screen.findByRole('region', { name: 'Sample Health Insurer' })
    expect(within(card).getByText('Shared with you')).toBeInTheDocument()
    expect(within(card).queryByText('Used this policy year')).toBeNull()
    expect(within(card).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add a policy' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add a claim' })).toBeNull()
  })

  it('has empty and error states', async () => {
    vi.mocked(vitaApi.policies).mockResolvedValue([])
    vi.mocked(vitaApi.claims).mockResolvedValue([])
    const { unmount } = renderView()
    expect(await screen.findByText('No cover recorded')).toBeInTheDocument()
    expect(await screen.findByText('Add a policy first, then its claims.')).toBeInTheDocument()
    unmount()
    vi.mocked(vitaApi.policies).mockRejectedValue(new Error('down'))
    renderView()
    expect(await screen.findByText('Could not load the policies.')).toBeInTheDocument()
  })
})
