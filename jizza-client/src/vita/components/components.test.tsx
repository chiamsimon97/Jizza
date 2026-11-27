import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { EmergencyCard, RedFlag } from '@/types/vita'
import { FigureLabel } from './FigureLabel'
import { RedFlagBanner } from './RedFlagBanner'
import { cardLines, EmergencyCardView } from './EmergencyCardView'

describe('FigureLabel: the three labels', () => {
  it('shows a Vita default with the needs-verification badge', () => {
    render(<FigureLabel state="needs_verification" label="Vita default · needs verification" />)
    expect(screen.getByText('Vita default')).toBeInTheDocument()
    expect(screen.getByText('Needs verification')).toBeInTheDocument()
  })

  it('shows a doctor’s target with who set it and when', () => {
    render(<FigureLabel state="doctor" doctor="Dr S Iyer" setOn="2026-10-12" />)
    expect(screen.getByText(/from Dr S Iyer, set 12 Oct 2026/)).toBeInTheDocument()
    expect(screen.queryByText('Needs verification')).toBeNull()
  })

  it('shows "Not set: ask your doctor" with no number', () => {
    render(<FigureLabel state="not_set" label="Not set: ask your doctor" />)
    expect(screen.getByText('Not set: ask your doctor')).toBeInTheDocument()
  })
})

const callNow: RedFlag = {
  level: 'emergency', personName: 'Father', reading: 'blood pressure 182/112',
  wording: 'Call now. This is at a level where doctors advise emergency care.', advice: 'Call the emergency number now.',
  emergencyNumbers: { emergency: '112' }, emergencyNumbersNeedVerification: true,
  preferredHospital: 'Sample City Hospital', preferredHospitalPhone: '+91 00000 00020',
}
const actToday: RedFlag = {
  level: 'urgent', personName: 'Father', reading: 'blood pressure 182/112',
  wording: 'Act today. This is at a level where doctors advise urgent care.',
  advice: 'Wait one minute and measure again.', question: 'Any of these now?', signs: ['chest pain', 'numbness'],
  rule: { key: 'clinical.redflags.bp', state: 'needs_verification', label: 'Vita default · needs verification' },
}

describe('RedFlagBanner: two levels', () => {
  it('Call now shows the number, the hospital and the server’s words', () => {
    const open = vi.fn()
    render(<RedFlagBanner flag={callNow} onOpenCard={open} />)
    const banner = screen.getByRole('alert', { name: 'Call now' })
    expect(banner).toHaveTextContent('Call now · 112')
    expect(banner).toHaveTextContent('Sample City Hospital')
    expect(banner).toHaveTextContent('doctors advise emergency care')
    expect(screen.getByRole('link', { name: /Call 112/ })).toHaveAttribute('href', 'tel:112')
    fireEvent.click(screen.getByRole('button', { name: /emergency card/ }))
    expect(open).toHaveBeenCalled()
    expect(screen.getByText('Needs verification')).toBeInTheDocument()
  })

  it('Act today shows the advice, the question and the signs, and raises only after a sign is picked', () => {
    const raise = vi.fn()
    render(<RedFlagBanner flag={actToday} onRaise={raise} onAcknowledge={vi.fn()} />)
    const banner = screen.getByRole('alert', { name: 'Act today' })
    expect(banner).toHaveTextContent('Wait one minute and measure again.')
    expect(banner).not.toHaveTextContent('Call now')
    const yes = screen.getByRole('button', { name: 'Yes, one of these now' })
    expect(yes).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'chest pain' }))
    expect(yes).toBeEnabled()
    fireEvent.click(yes)
    expect(raise).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Acknowledge' })).toBeInTheDocument()
  })

  it('never names a diagnosis of its own', () => {
    const { container } = render(<RedFlagBanner flag={{ ...actToday, advice: undefined, wording: undefined }} />)
    expect(container.textContent).not.toMatch(/hypertens|diabet|stroke|heart attack/i)
  })
})

const card: EmergencyCard = {
  personId: 'p1', name: 'Mother', age: 62, bloodGroup: 'o_pos', organDonor: true, preferredHospital: 'Sample City Hospital',
  preferredHospitalPhone: '+91 00000 00020', allergiesNoneKnown: false,
  allergies: [{ substance: 'Sulfa drugs', severity: 'severe', reaction: 'Skin rash' }], conditionsNoneKnown: false,
  conditions: [{ name: 'Type 2 diabetes', since: '2016-01-01' }], medicines: [], medicinesAvailable: false,
  emergencyContacts: [{ name: 'Ravi', relation: 'Son', phone: '+91 00000 00010' }],
  emergencyNumbers: { emergency: '112', ambulanceWhereTheyLive: '' }, emergencyNumbersNeedVerification: true, updatedOn: '2026-11-18', closed: false,
}

describe('EmergencyCardView', () => {
  it('lays out the card with blood group, the severe allergy first and the update date', () => {
    render(<EmergencyCardView card={card} />)
    expect(screen.getByRole('article', { name: 'Emergency card for Mother' })).toBeInTheDocument()
    expect(screen.getByText('O+')).toBeInTheDocument()
    expect(screen.getByText(/Sulfa drugs \(severe\): Skin rash/)).toBeInTheDocument()
    expect(screen.getByText('Updated on 18 Nov 2026')).toBeInTheDocument()
  })

  it('says "None known" only when it was recorded', () => {
    const none = Object.fromEntries(cardLines({ ...card, allergies: [], allergiesNoneKnown: true, conditions: [], conditionsNoneKnown: false }))
    expect(none.Allergies).toBe('None known')
    expect(none.Conditions).toBe('Not recorded')
  })
})
