import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { agentsApi } from '@/api/agents'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import { postSignInDestination } from '@/lib/session'
import { menuFor } from '@/components/layout/navigation'
import { canOpenPage } from '@/jizza/pages'
import type { AgentDto } from '@/types'
import type {
  AccessList, CareNote, DoseSlot, EmergencyCard, HealthConsent, HomeView, SharedWithMe, SharesView, VitaPerson,
} from '@/types/vita'
import { HealthTab } from '../HealthTab'
import { DoseSlotCard } from '../components/DoseSlotCard'
import { Sharing } from './Sharing'
import { CareNotes } from './CareNotes'
import { RecordActions } from './RecordActions'
import { MyMedicinesToday } from './MyMedicinesToday'
import { ShareContext, accessWords, scopeOf, viewOpens } from '../share'

const me: VitaPerson = { id: 'p-me', displayName: 'Me', kind: 'self', type: 'individual', dob: null, status: 'active', sortOrder: 0 }
const mother: VitaPerson = { ...me, id: 'p-mum', displayName: 'Mother', kind: 'parent', sortOrder: 1 }
const vita = { id: 'a2', key: 'health', name: 'Vita' } as AgentDto

const caregiver: SharedWithMe = {
  personId: 'p-amma', name: 'Amma', personName: 'Amma', asSelf: false, managerEmail: 'raj@example.com',
  sections: { medicines: 'edit', vitals: 'edit', labs: 'view', visits: 'view', documents: 'view' },
}
const herself: SharedWithMe = {
  personId: 'p-amma', name: 'You', personName: 'Amma', asSelf: true, managerEmail: 'raj@example.com',
  sections: { profile: 'view', emergency: 'view', conditions: 'view', allergies: 'view', medicines: 'edit', vitals: 'edit', labs: 'view', visits: 'view' },
}
const sharedHome: HomeView = {
  person: { id: 'p-amma', name: 'Amma', recordOpen: true, status: 'active', shared: true, asSelf: false, sections: caregiver.sections },
  quickStart: null, latest: [], redFlags: [], conditions: [], nextVisit: null, slots: [], reminders: [],
}

const presets: SharesView['presets'] = [
  { key: 'self', sections: { profile: 'view', medicines: 'edit', vitals: 'edit' } },
  { key: 'emergency', sections: { emergency: 'view' } },
  { key: 'caregiver', sections: { medicines: 'edit', vitals: 'edit', labs: 'view', visits: 'view', documents: 'view' } },
  { key: 'everything_except_journal', sections: { profile: 'view', medicines: 'view' } },
]
const sectionKeys: SharesView['sectionKeys'] = ['profile', 'emergency', 'conditions', 'allergies', 'medicines', 'vitals', 'labs', 'documents',
  'visits', 'vaccinations', 'events', 'insurance', 'journal', 'goals']
const NOTE = 'Pages already saved or printed cannot be recalled.'
const sharesView = (over: Partial<SharesView> = {}): SharesView => ({
  links: [], members: [{ userId: 'u-bro', email: 'brother@example.com' }, { userId: 'u-amma', email: 'amma@example.com' }],
  hasFamily: true, presets, sectionKeys, note: NOTE, ...over,
})
const openConsent: HealthConsent = { id: 'c1', personId: 'p-mum', scope: 'view', channel: 'In person', grantedAt: '2026-11-28T08:00:00Z', withdrawnAt: null, open: true }
const accessList: AccessList = {
  personId: 'p-mum', personName: 'Mother', note: NOTE, entries: [
    { id: 's1', granteeUserId: 'u-bro', granteeEmail: 'brother@example.com', asSelf: false, section: 'medicines', access: 'edit', grantedAt: '2026-11-28T08:00:00Z', endedAt: null, endedReason: null, active: true },
    { id: 's0', granteeUserId: 'u-bro', granteeEmail: 'brother@example.com', asSelf: false, section: 'journal', access: 'view', grantedAt: '2026-11-20T08:00:00Z', endedAt: '2026-11-21T08:00:00Z', endedReason: 'revoked', active: false },
  ],
}
const emptyCard = { personId: 'p-amma', name: 'Amma', allergies: [], emergencyContacts: [], bloodGroup: null } as unknown as EmergencyCard

function slot(over: Partial<DoseSlot> = {}): DoseSlot {
  return {
    personId: 'p-amma', personName: 'Amma', dueAt: '2026-11-28T02:30:00Z', localTime: '08:00', localDate: '2026-11-28', zone: 'Asia/Kolkata',
    status: 'due', snoozedUntil: null, markedBy: null, markedAt: null, missedAt: '2026-11-28T03:30:00Z',
    doses: [{ id: 'd1', medicineId: 'm1', medicineName: 'Metformin', strength: '500 mg', doseUnits: 1, doseUnit: 'tablet', withFood: 'after', status: 'due', snoozedUntil: null, takenAt: null, actedAt: null, markedBy: null, late: false, version: 1 }],
    ...over,
  } as DoseSlot
}

beforeEach(() => {
  vi.spyOn(vitaApi, 'people').mockResolvedValue([me, mother])
  vi.spyOn(vitaApi, 'config').mockResolvedValue([])
  vi.spyOn(vitaApi, 'emergencyCard').mockResolvedValue(emptyCard)
  vi.spyOn(vitaApi, 'careNotes').mockResolvedValue([])
  vi.spyOn(vitaApi, 'shares').mockResolvedValue(sharesView())
})
afterEach(() => vi.restoreAllMocks())

const renderTab = (search = '') => renderWithSnapshot(<HealthTab agent={vita} />, makeSnapshot('PERSONAL'), `/agents/a2?tab=health${search}`)

describe('A record shared with me', () => {
  it('lists it with a shared badge, shows the banner and only the shared sections', async () => {
    vi.spyOn(vitaApi, 'sharedWithMe').mockResolvedValue([caregiver])
    const home = vi.spyOn(vitaApi, 'home').mockResolvedValue(sharedHome)
    const card = vi.spyOn(vitaApi, 'emergencyCard')
    renderTab('&person=p-amma')
    expect(await screen.findByText(/Amma’s record is kept by raj@example.com/)).toBeInTheDocument()
    expect(screen.getByText(/You can add to Medicines and Vitals; see Lab results, Doctors and visits and Documents/)).toBeInTheDocument()
    expect(screen.getByText('Shared')).toBeInTheDocument()
    const person = screen.getByRole('combobox', { name: 'Person' })
    expect(within(within(person).getByRole('group', { name: 'Shared with you' })).getByRole('option', { name: 'Amma (shared)' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Health sections' })
    for (const shown of ['Home', 'Medicines', 'Vitals', 'Lab results']) expect(within(nav).getByRole('button', { name: shown })).toBeInTheDocument()
    for (const hidden of ['Sharing', 'Settings', 'Connect', 'Journal', 'Allergies', 'Emergency card', 'Conditions']) {
      expect(within(nav).queryByRole('button', { name: new RegExp(`^${hidden}`) })).toBeNull()
    }
    await waitFor(() => expect(home).toHaveBeenCalledWith('p-amma'))
    expect(card).not.toHaveBeenCalled()
    expect(screen.queryByText('Get started')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Medicine/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Blood pressure/ })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Allergy/ })).toBeNull()
    expect(within(menu).queryByRole('menuitem', { name: /Visit/ })).toBeNull()
  })

  it('says a section was not shared instead of loading it', async () => {
    vi.spyOn(vitaApi, 'sharedWithMe').mockResolvedValue([caregiver])
    renderTab('&person=p-amma&view=journal')
    expect(await screen.findByText(/has not shared journal with you/)).toBeInTheDocument()
  })

  it('opens on the record I am linked to as myself, as You', async () => {
    vi.spyOn(vitaApi, 'sharedWithMe').mockResolvedValue([herself])
    vi.spyOn(vitaApi, 'home').mockResolvedValue({ ...sharedHome, person: { ...sharedHome.person, asSelf: true } })
    renderTab()
    expect(await screen.findByText(/Your record is kept by raj@example.com/)).toBeInTheDocument()
    const person = screen.getByRole('combobox', { name: 'Person' }) as HTMLSelectElement
    expect(person.value).toBe('p-amma')
    expect(within(person).getByRole('option', { name: 'Your own record' })).toBeInTheDocument()
  })

  it('reads which views open from the sections', () => {
    const s = scopeOf(caregiver, 'p-amma')
    expect(viewOpens(s, 'lab')).toBe(true)
    expect(viewOpens(s, 'surgeries')).toBe(false)
    expect(viewOpens(s, 'summary')).toBe(false)
    expect(viewOpens(scopeOf(null, 'p-me'), 'sharing')).toBe(true)
    expect(accessWords({})).toBe('nothing yet')
  })
})

describe('Sharing', () => {
  const renderSharing = (kind = 'parent') =>
    renderWithSnapshot(<Sharing personId="p-mum" personName="Mother" kind={kind} closed={false} />, makeSnapshot('PERSONAL', 'OWNER', {
      workspaces: [{ id: 'ws-personal', type: 'PERSONAL', name: 'Personal', role: 'OWNER' }, { id: 'ws-f', type: 'FAMILY', name: 'The Raos', role: 'OWNER' }],
    }))

  it('asks to create the Family first', async () => {
    vi.mocked(vitaApi.shares).mockResolvedValue(sharesView({ hasFamily: false, members: [] }))
    vi.spyOn(vitaApi, 'accessList').mockResolvedValue({ ...accessList, entries: [] })
    renderSharing()
    expect(await screen.findByRole('button', { name: 'Create your Family' })).toBeInTheDocument()
    expect(await screen.findByText('Only you can see this record.')).toBeInTheDocument()
  })

  it('heads the view with the Family and its members, and refuses a helper before the consent note', async () => {
    vi.spyOn(vitaApi, 'consents').mockResolvedValue([])
    vi.spyOn(vitaApi, 'accessList').mockResolvedValue({ ...accessList, entries: [] })
    const record = vi.spyOn(vitaApi, 'recordConsent').mockResolvedValue(openConsent)
    renderSharing()
    expect(await screen.findByText('The Raos')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Family members' })).getByText('brother@example.com')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Share with a member' }))
    expect(await screen.findByText(/Record a consent note for Mother first/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share' })).toBeDisabled()
    // "This is them" needs no note.
    fireEvent.click(screen.getByRole('radio', { name: /This is them/ }))
    expect(screen.queryByText(/Record a consent note for Mother first/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Share' })).toBeEnabled()
    fireEvent.change(screen.getByLabelText('How (optional)'), { target: { value: 'In person' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the note' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith('p-mum', { scope: 'view', channel: 'In person' }))
  })

  it('shares with a preset, and with a customised matrix', async () => {
    vi.spyOn(vitaApi, 'consents').mockResolvedValue([openConsent])
    vi.spyOn(vitaApi, 'accessList').mockResolvedValue(accessList)
    const share = vi.spyOn(vitaApi, 'share').mockResolvedValue(sharesView())
    renderSharing()
    fireEvent.click(await screen.findByRole('button', { name: 'Share with a member' }))
    expect(screen.getByText('Giving Edit asks for your password again.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Share' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Share' }))
    await waitFor(() => expect(share).toHaveBeenCalledWith({ personId: 'p-mum', granteeUserId: 'u-bro', asSelf: undefined, preset: 'caregiver' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Share with a member' }))
    fireEvent.click(screen.getByRole('radio', { name: /Emergency only/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Customise/ }))
    fireEvent.click(screen.getByRole('radio', { name: 'Allergies: View' }))
    fireEvent.click(screen.getByRole('button', { name: 'Share' }))
    await waitFor(() => expect(share).toHaveBeenLastCalledWith(expect.objectContaining({
      preset: null, sections: expect.objectContaining({ emergency: 'view', allergies: 'view', journal: 'none' }),
    })))
  })

  it('lists who sees the record, stops a share, and shows ended shares on the access list', async () => {
    vi.mocked(vitaApi.shares).mockResolvedValue(sharesView({ links: [{
      personId: 'p-mum', personName: 'Mother', granteeUserId: 'u-amma', granteeEmail: 'amma@example.com', asSelf: true,
      sections: { medicines: 'edit', profile: 'view' }, shares: [],
    }] }))
    vi.spyOn(vitaApi, 'consents').mockResolvedValue([openConsent])
    vi.spyOn(vitaApi, 'accessList').mockResolvedValue(accessList)
    const end = vi.spyOn(vitaApi, 'endShares').mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderSharing()
    expect(await screen.findByText('This is them')).toBeInTheDocument()
    expect(screen.getByText('Can add to Medicines; see Profile.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Stop sharing' }))
    await waitFor(() => expect(end).toHaveBeenCalledWith('p-mum', 'u-amma'))
    expect(await screen.findByRole('button', { name: 'Show 1 ended share' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show 1 ended share' }))
    expect(screen.getByText(/sharing stopped/)).toBeInTheDocument()
    expect(screen.getAllByText(NOTE).length).toBeGreaterThan(0)
  })

  it('withdraws a consent note after a warning', async () => {
    vi.spyOn(vitaApi, 'consents').mockResolvedValue([openConsent])
    vi.spyOn(vitaApi, 'accessList').mockResolvedValue(accessList)
    const withdraw = vi.spyOn(vitaApi, 'withdrawConsent').mockResolvedValue({ ...openConsent, open: false })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderSharing()
    fireEvent.click(await screen.findByRole('button', { name: 'Withdraw' }))
    expect(confirm.mock.calls[0][0]).toMatch(/every helper’s share of Mother’s record ends now/)
    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('p-mum', 'c1'))
  })

  it('shows the error state', async () => {
    vi.mocked(vitaApi.shares).mockRejectedValue(new Error('down'))
    renderSharing()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load sharing.')
  })
})

describe('Notes between helpers', () => {
  const note: CareNote = { id: 'n1', text: 'Ran out of strips, bought a pack', writtenBy: 'u-bro', writtenByName: 'brother@example.com', writtenAt: '2026-11-28T10:00:00Z' }

  it('stays hidden on an own record nobody shares, and shows on a shared one', async () => {
    const { unmount } = renderWithSnapshot(<CareNotes personId="p-me" readOnly={false} />, makeSnapshot())
    await waitFor(() => expect(vitaApi.careNotes).toHaveBeenCalled())
    expect(screen.queryByText('Notes')).toBeNull()
    unmount()
    vi.mocked(vitaApi.careNotes).mockResolvedValue([note])
    const add = vi.spyOn(vitaApi, 'addCareNote').mockResolvedValue(note)
    renderWithSnapshot(<ShareContext.Provider value={scopeOf(caregiver, 'p-amma')}><CareNotes personId="p-amma" readOnly={false} /></ShareContext.Provider>, makeSnapshot())
    expect(await screen.findByText(note.text)).toBeInTheDocument()
    expect(screen.getByText(/brother@example.com ·/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Add a note'), { target: { value: 'Next visit moved to Friday' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-amma', 'Next visit moved to Friday'))
  })
})

describe('Export and delete a record', () => {
  it('exports, and deletes only after the name is typed', async () => {
    const exp = vi.spyOn(vitaApi, 'exportRecord').mockResolvedValue({ person: { displayName: 'Mother' } })
    const del = vi.spyOn(vitaApi, 'deleteRecord').mockResolvedValue({ deleted: 42 })
    const createUrl = vi.fn(() => 'blob:x')
    Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: vi.fn() })
    renderWithSnapshot(<RecordActions personId="p-mum" name="Mother" self={false} />, makeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: /Export Mother’s record/ }))
    await waitFor(() => expect(exp).toHaveBeenCalledWith('p-mum'))
    expect(await screen.findByText('The file is saved to your downloads.')).toBeInTheDocument()
    expect(createUrl).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Delete Mother’s record/ }))
    const go = screen.getByRole('button', { name: 'Delete for good' })
    expect(go).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Type Mother to confirm/), { target: { value: 'mother' } })
    fireEvent.click(go)
    await waitFor(() => expect(del).toHaveBeenCalledWith('p-mum'))
    expect(await screen.findByText('Deleted 42 entries from Mother’s health record.')).toBeInTheDocument()
  })
})

describe('My medicines today', () => {
  it('says nothing is linked yet', async () => {
    vi.spyOn(vitaApi, 'sharedWithMe').mockResolvedValue([caregiver])
    renderWithSnapshot(<MyMedicinesToday />, makeSnapshot())
    expect(await screen.findByText('Nothing is linked to you yet')).toBeInTheDocument()
  })

  it('shows today’s slots in large text, who marked them, the next visit and the links', async () => {
    vi.spyOn(vitaApi, 'sharedWithMe').mockResolvedValue([herself])
    vi.spyOn(vitaApi, 'doses').mockResolvedValue({ zone: 'Asia/Kolkata', slots: [
      slot(),
      slot({ dueAt: '2026-11-28T14:30:00Z', localTime: '20:00', status: 'taken', markedBy: 'u-bro', markedByName: 'brother@example.com', markedAt: '2026-11-28T15:40:00Z' }),
    ] })
    vi.spyOn(vitaApi, 'home').mockResolvedValue({ ...sharedHome, nextVisit: { id: 'v1', personId: 'p-amma', doctorId: 'd1', scheduledAt: '2026-12-02T04:30:00Z', status: 'planned', reason: 'Sugar review', notes: null, followUpOn: null } as HomeView['nextVisit'] })
    vi.spyOn(vitaApi, 'personDoctors').mockResolvedValue([{ id: 'd1', kind: 'doctor', name: 'Dr S Iyer', phone: '+91 00000 00030' } as never])
    vi.spyOn(agentsApi, 'list').mockResolvedValue([vita])
    const act = vi.spyOn(vitaApi, 'actSlot').mockResolvedValue(slot({ status: 'taken' }))
    renderWithSnapshot(<MyMedicinesToday slot="2026-11-28T02:30:00Z" />, makeSnapshot())
    const morning = (await screen.findAllByRole('region', { name: /Medicines at 8/ }))[0]
    expect(morning.className).toMatch(/ring-2/)
    expect(screen.getByText(/Marked by brother@example.com/)).toBeInTheDocument()
    expect(await screen.findByText('+91 00000 00030')).toHaveAttribute('href', 'tel:+910000000030')
    expect(await screen.findByRole('link', { name: 'Open my full health record' })).toHaveAttribute('href', '/agents/a2?tab=health&person=p-amma')
    expect(screen.getByRole('link', { name: 'Add a reading' })).toBeInTheDocument()
    fireEvent.click(within(morning).getByRole('button', { name: 'All taken' }))
    await waitFor(() => expect(act).toHaveBeenCalledWith('p-amma', '2026-11-28T02:30:00Z', { action: 'taken' }))
  })

  it('holds a mark made with no network and sends it when the network is back', async () => {
    const act = vi.spyOn(vitaApi, 'actSlot').mockResolvedValue(slot({ status: 'taken' }))
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    renderWithSnapshot(<DoseSlotCard personId="p-amma" slot={slot()} readOnly={false} large />, makeSnapshot())
    fireEvent.click(screen.getByRole('button', { name: 'All taken' }))
    expect(screen.getByText(/Waiting to send/)).toBeInTheDocument()
    expect(act).not.toHaveBeenCalled()
    online.mockReturnValue(true)
    window.dispatchEvent(new Event('online'))
    await waitFor(() => expect(act).toHaveBeenCalledWith('p-amma', '2026-11-28T02:30:00Z', { action: 'taken' }))
  })

  it('opens after sign-in when the server says so, and sits in the menu for a linked person', () => {
    expect(postSignInDestination(makeSnapshot('PERSONAL'), undefined, 'my-medicines')).toEqual({ to: '/my-medicines' })
    expect(postSignInDestination(makeSnapshot('PERSONAL'), '/tasks', 'my-medicines')).toEqual({ to: '/tasks' })
    expect(postSignInDestination(makeSnapshot('PERSONAL'), undefined, null)).toEqual({ to: '/chat' })
    expect(postSignInDestination(makeSnapshot('FAMILY'), undefined, 'my-medicines')).toEqual({ to: '/members' })
    expect(menuFor(makeSnapshot('PERSONAL'), { myMedicines: true }).main[0].label).toBe('My medicines')
    expect(menuFor(makeSnapshot('PERSONAL')).main.map(h => h.label)).not.toContain('My medicines')
    expect(canOpenPage('my_medicines', makeSnapshot('PERSONAL'))).toBe(true)
    expect(canOpenPage('my_medicines', makeSnapshot('FAMILY'))).toBe(false)
  })
})
