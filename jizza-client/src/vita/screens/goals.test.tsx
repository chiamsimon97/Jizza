import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { GoalCheckin, HealthGoal } from '@/types/vita'
import { ShareContext, scopeOf } from '../share'
import { Goals } from './Goals'

const week = (weekStart: string, value: number | null, value2: number | null, meets: boolean | null, source: GoalCheckin['source'] = 'measurements'): GoalCheckin =>
  ({ weekStart, source, value, value2, meets })

const bp: HealthGoal = {
  id: 'g1', personId: 'p-dad', kind: 'bp_range', title: null, context: null,
  target: { text: 'below 130/80 mmHg', source: 'doctor', label: 'From Dr Rao', doctorName: 'Dr Rao', setOn: '2026-09-10', needsVerification: false },
  startValue: null, unit: null, byDate: null, weeklyPlan: [{ text: 'Walk 30 minutes after dinner' }], status: 'active', metOn: null, medical: true,
  checkins: [week('2026-11-09', null, null, null, 'none'), week('2026-11-16', 128, 78, true), week('2026-11-23', 126, 79, true, 'manual')],
  weeksMetInARow: 2, metAfterWeeks: 4, planNote: 'Vita does not suggest plan figures. Type the steps you and your doctor agreed.',
}
const sugar: HealthGoal = {
  ...bp, id: 'g2', kind: 'glucose_range', context: 'before_meal', weeklyPlan: [],
  target: { text: '80-130 mg/dL', source: 'vita_default', label: 'Vita default', needsVerification: true, configKey: 'clinical.glucose', note: 'Vita default, needs verification. Confirm with your doctor.' },
  checkins: [week('2026-11-23', 142, null, false)], weeksMetInARow: 0,
}
const walked: HealthGoal = {
  ...bp, id: 'g3', kind: 'steps', medical: false, status: 'met', metOn: '2026-11-20', unit: 'steps a day',
  target: { text: '6000 steps a day', source: 'you', label: 'Your goal' }, checkins: [], weeklyPlan: [],
}

beforeEach(() => { vi.spyOn(vitaApi, 'goals').mockResolvedValue([bp, sugar, walked]) })
afterEach(() => vi.restoreAllMocks())

const renderView = () => renderWithSnapshot(<Goals personId="p-dad" personName="Father" readOnly={false} />, makeSnapshot())

describe('Goals', () => {
  it('shows each target with where it comes from, the weeks in words and the status', async () => {
    renderView()
    const card = within(await screen.findByRole('region', { name: 'Blood pressure in range' }))
    expect(card.getByText('below 130/80 mmHg')).toBeInTheDocument()
    expect(card.getByText(/from Dr Rao, set 10 Sep 2026/)).toBeInTheDocument()
    expect(card.getByText('Active')).toBeInTheDocument()
    expect(card.getByLabelText(/Week of 16 Nov 2026: 128\/78 mmHg, meets the goal, from readings/)).toBeInTheDocument()
    expect(card.getByLabelText('Week of 9 Nov 2026: no value')).toBeInTheDocument()
    expect(card.getByText(/2 of 4 weeks in a row met/)).toBeInTheDocument()
    expect(card.getByText('Walk 30 minutes after dinner')).toBeInTheDocument()
    // A Vita default carries the badge and the doctor note, in the server's words.
    const s = within(screen.getByRole('region', { name: 'Sugar in range before a meal' }))
    expect(s.getByText('Needs verification')).toBeInTheDocument()
    expect(s.getByText('Vita default, needs verification. Confirm with your doctor.')).toBeInTheDocument()
    expect(s.getByLabelText(/142 mg\/dL, does not meet it yet/)).toBeInTheDocument()
    // Met goals sit apart.
    expect(screen.getByText('Met and stopped (1)')).toBeInTheDocument()
    expect(screen.getByText('Met on 20 Nov 2026')).toBeInTheDocument()
  })

  it('will not add a medical goal with no target from a doctor or config, and asks for no figure', async () => {
    const preview = vi.spyOn(vitaApi, 'goalPreview').mockResolvedValue({
      personId: 'p-dad', kind: 'hba1c', canCreate: false, target: null, planNote: bp.planNote, metAfterWeeks: 4,
      message: 'No target is set for this yet. Ask the doctor for one and add it under Ranges; Vita does not make one up.',
    })
    const add = vi.spyOn(vitaApi, 'addGoal')
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Add a goal' }))
    fireEvent.change(screen.getByLabelText('Kind of goal'), { target: { value: 'hba1c' } })
    expect(await screen.findByText(/Vita does not make one up/)).toBeInTheDocument()
    expect(preview).toHaveBeenLastCalledWith('p-dad', 'hba1c', null)
    expect(screen.queryByLabelText('Aiming for')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save goal' })).toBeDisabled()
    expect(add).not.toHaveBeenCalled()
  })

  it('adds a weight goal with the number typed and the weekly plan', async () => {
    vi.spyOn(vitaApi, 'goalPreview').mockResolvedValue({ personId: 'p-dad', kind: 'weight', canCreate: true, target: null, message: 'Type the number you are aiming for.', planNote: bp.planNote, metAfterWeeks: 4 })
    const add = vi.spyOn(vitaApi, 'addGoal').mockResolvedValue(walked)
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Add a goal' }))
    expect((await screen.findAllByText('Type the number you are aiming for.')).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Save goal' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Aiming for'), { target: { value: '72' } })
    fireEvent.change(screen.getByLabelText(/Weekly plan/), { target: { value: 'No sugar in tea\n\nWalk daily' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save goal' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith('p-dad', {
      kind: 'weight', context: undefined, title: null, byDate: null, weeklyPlan: ['No sugar in tea', 'Walk daily'], target: 72, unit: 'kg',
    }))
  })

  it('checks in a blood pressure week with both numbers', async () => {
    const check = vi.spyOn(vitaApi, 'checkIn').mockResolvedValue(bp)
    renderView()
    const card = within(await screen.findByRole('region', { name: 'Blood pressure in range' }))
    fireEvent.click(card.getByRole('button', { name: 'Check in' }))
    fireEvent.change(card.getByLabelText('Upper (systolic)'), { target: { value: '124' } })
    expect(card.getByRole('button', { name: 'Save check-in' })).toBeDisabled()
    fireEvent.change(card.getByLabelText('Lower (diastolic)'), { target: { value: '78' } })
    fireEvent.click(card.getByRole('button', { name: 'Save check-in' }))
    await waitFor(() => expect(check).toHaveBeenCalled())
    const [, id, body] = check.mock.calls[0]
    expect(id).toBe('g1')
    expect(body).toMatchObject({ value: 124, value2: 78, note: null })
    expect(new Date(`${body.weekStart}T00:00:00Z`).getUTCDay()).toBe(1)
  })

  it('shows a shared record’s goals with nothing to change', async () => {
    const scope = scopeOf({ personId: 'p-dad', displayName: 'Father', asSelf: false, managerEmail: 'a@b.c', sections: { goals: 'view' } } as never, 'p-dad')
    renderWithSnapshot(<ShareContext.Provider value={scope}><Goals personId="p-dad" personName="Father" readOnly /></ShareContext.Provider>, makeSnapshot())
    expect(await screen.findByRole('region', { name: 'Blood pressure in range' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add a goal' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check in' })).not.toBeInTheDocument()
  })
})
