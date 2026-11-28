import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { ActivityReading, HealthConnection } from '@/types/vita'
import { localDay } from '../medicines'
import { Activity } from './Activity'
import { Connect } from './Connect'

const pixel: HealthConnection = {
  id: 'c1', personId: 'p-dad', source: 'health_connect', dataTypes: ['steps', 'sleep'], deviceId: 'd1', deviceName: 'Pixel 8',
  grantedAt: '2026-11-20T04:00:00Z', revokedAt: null, lastSyncAt: '2026-11-28T03:00:00Z', shared: false, rowsWritten: 42, rowsByType: { steps: 30, sleep: 12 },
}
const today = localDay(0)
const reading = (id: string, kind: ActivityReading['kind'], value: number, extra: Partial<ActivityReading> = {}): ActivityReading => ({
  id, personId: 'p-dad', kind, value, unit: null, takenAt: `${today}T03:00:00Z`, source: 'health_connect', device: 'Pixel 8', ...extra,
})

afterEach(() => vi.restoreAllMocks())

describe('Connect: phones and watches', () => {
  beforeEach(() => {
    vi.spyOn(vitaApi, 'mailSources').mockResolvedValue({ sources: [], servers: [] })
    vi.spyOn(vitaApi, 'connectors').mockResolvedValue([pixel])
  })
  const renderConnect = () => renderWithSnapshot(<Connect personId="p-dad" readOnly={false} onOpen={vi.fn()} onReview={vi.fn()} />, makeSnapshot())

  it('lists the phone with what each data type added, and removes it before offering to delete', async () => {
    const revoke = vi.spyOn(vitaApi, 'revokeConnector')
      .mockResolvedValueOnce({ ...pixel, revokedAt: '2026-11-28T10:00:00Z', rowsDeleted: 0 })
      .mockResolvedValueOnce({ ...pixel, revokedAt: '2026-11-28T10:00:00Z', rowsWritten: 0, rowsByType: {}, rowsDeleted: 42 })
    renderConnect()
    const card = within(await screen.findByRole('region', { name: 'Pixel 8, Health Connect' }))
    expect(card.getByText('Connected')).toBeInTheDocument()
    expect(card.getByText('30 readings added')).toBeInTheDocument()
    expect(card.getByText('12 readings added')).toBeInTheDocument()
    fireEvent.click(card.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(revoke).toHaveBeenCalledWith('p-dad', 'c1', { types: undefined }))
    // Removing stops the phone; deleting what it added is a second, separate choice.
    expect(await card.findByText(/Delete what it added\? 42 readings from this phone stay/)).toBeInTheDocument()
    expect(revoke).toHaveBeenCalledTimes(1)
    fireEvent.click(card.getByRole('button', { name: 'Delete 42 readings' }))
    await waitFor(() => expect(revoke).toHaveBeenLastCalledWith('p-dad', 'c1', { types: undefined, deleteData: true }))
    expect(await card.findByText('Deleted 42 readings.')).toBeInTheDocument()
  })

  it('turns one data type off and offers to delete only that type', async () => {
    const revoke = vi.spyOn(vitaApi, 'revokeConnector').mockResolvedValue({ ...pixel, dataTypes: ['steps'] })
    renderConnect()
    const card = within(await screen.findByRole('region', { name: 'Pixel 8, Health Connect' }))
    fireEvent.click(card.getAllByRole('button', { name: 'Turn off' })[1])
    await waitFor(() => expect(revoke).toHaveBeenCalledWith('p-dad', 'c1', { types: ['sleep'] }))
    expect(await card.findByText('Sleep stopped.')).toBeInTheDocument()
    expect(card.getByText(/12 readings of sleep from this phone stay/)).toBeInTheDocument()
    fireEvent.click(card.getByRole('button', { name: 'Keep them' }))
    expect(card.queryByText(/stay in the record/)).not.toBeInTheDocument()
  })
})

describe('Activity and sleep', () => {
  it('shows steps, sleep, workouts and resting heart rate as sent, naming the phone', async () => {
    vi.spyOn(vitaApi, 'connectors').mockResolvedValue([pixel])
    vi.spyOn(vitaApi, 'activity').mockImplementation(async (_p, kind) => ({
      steps: [reading('s1', 'steps', 6240, { detail: { date: today } })],
      sleep: [reading('z1', 'sleep', 7.5, { startedAt: `${today}T17:00:00Z`, endedAt: `${today}T00:30:00Z` })],
      workout: [reading('w1', 'workout', 35, { detail: { workoutType: 'brisk_walking' }, startedAt: `${today}T01:00:00Z` })],
      resting_heart_rate: [reading('h1', 'resting_heart_rate', 64)],
    })[kind])
    renderWithSnapshot(<Activity personId="p-dad" personName="Father" onOpen={vi.fn()} />, makeSnapshot())
    const steps = within(await screen.findByRole('region', { name: 'Steps' }))
    expect(steps.getByText('6,240 steps')).toBeInTheDocument()
    expect(steps.getByText('From Pixel 8, Health Connect')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Sleep' })).getAllByText('7 h 30 min').length).toBeGreaterThan(0)
    expect(within(screen.getByRole('region', { name: 'Workouts' })).getByText('Brisk walking')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Resting heart rate' })).getByText('64 bpm')).toBeInTheDocument()
  })
})
