import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vitaApi } from '@/api/vita'
import { weeklyNoteLinkOf } from '@/components/notifications/inbox'
import { makeSnapshot, renderWithSnapshot } from '@/test/utils'
import type { NotificationDto } from '@/types'
import type { Insights as InsightsView, WeeklyNote as Note } from '@/types/vita'
import { localDay } from '../medicines'
import { OWN_RECORD, viewOpens } from '../share'
import { Insights } from './Insights'
import { WeeklyNote, mondayOf } from './WeeklyNote'

const DISCLAIMER = 'Prepared by Vita from your own records. Not medical advice.'
const week = mondayOf(localDay(0))
const note: Note = {
  personId: 'p-dad', personName: 'Father', weekStart: week, weekEnd: week, zone: 'Asia/Kolkata', disclaimer: DISCLAIMER, empty: false,
  sentences: [
    { kind: 'missed_doses', text: '2 doses were missed: Metformin on Tuesday evening and Thursday evening.', rows: [{ table: 'vita_dose_log', id: 'd1' }, { table: 'vita_dose_log', id: 'd2' }] },
    { kind: 'lab_report', text: 'A lab report from 24 Nov was added.', rows: [{ table: 'vita_lab_report', id: 'r1' }] },
  ],
}

afterEach(() => vi.restoreAllMocks())

describe('Weekly note', () => {
  it('shows each sentence with the entries behind it, and the disclaimer', async () => {
    const get = vi.spyOn(vitaApi, 'weeklyNote').mockResolvedValue(note)
    const open = vi.fn()
    const report = vi.fn()
    renderWithSnapshot(<WeeklyNote personId="p-dad" personName="Father" onOpen={open} onOpenReport={report} />, makeSnapshot())
    expect(await screen.findByText(/2 doses were missed/)).toBeInTheDocument()
    expect(screen.getByText(DISCLAIMER)).toBeInTheDocument()
    expect(screen.getByText(/From 2 entries in/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Medicines' }))
    expect(open).toHaveBeenCalledWith('medicines')
    fireEvent.click(screen.getByRole('button', { name: 'Lab report' }))
    expect(report).toHaveBeenCalledWith('r1')
    expect(get).toHaveBeenCalledWith('p-dad', week)
    expect(screen.getByRole('button', { name: 'Next week' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Previous week' }))
    const before = new Date(`${week}T00:00:00Z`)
    before.setUTCDate(before.getUTCDate() - 7)
    await waitFor(() => expect(get).toHaveBeenLastCalledWith('p-dad', before.toISOString().slice(0, 10)))
  })

  it('says when there is nothing to note', async () => {
    vi.spyOn(vitaApi, 'weeklyNote').mockResolvedValue({ ...note, sentences: [], empty: true, message: 'Nothing changed this week.' })
    renderWithSnapshot(<WeeklyNote personId="p-dad" personName="Father" onOpen={vi.fn()} onOpenReport={vi.fn()} />, makeSnapshot())
    expect(await screen.findByText('Nothing changed this week.')).toBeInTheDocument()
  })

  it('opens only for whoever keeps the record and the person themself', () => {
    const shared = { ...OWN_RECORD, personId: 'p', shared: true, sections: { journal: 'view' as const, medicines: 'edit' as const } }
    expect(viewOpens(OWN_RECORD, 'weekly-note')).toBe(true)
    expect(viewOpens(shared, 'weekly-note')).toBe(false)
    expect(viewOpens({ ...shared, asSelf: true }, 'weekly-note')).toBe(true)
  })

  it('links the inbox item to the week it is about, taking only ids and a date', () => {
    const n = { sourceAgentId: 'a2a2a2a2', context: { type: 'vita_weekly_note', personId: '6f1c0000-0000', weekStart: '2026-11-23' } } as unknown as NotificationDto
    expect(weeklyNoteLinkOf(n)).toBe('/agents/a2a2a2a2?tab=health&person=6f1c0000-0000&view=weekly-note&week=2026-11-23')
    expect(weeklyNoteLinkOf({ ...n, context: { ...n.context, personId: '../x' } } as unknown as NotificationDto)).toBeNull()
    expect(weeklyNoteLinkOf({ ...n, context: { type: 'other' } } as unknown as NotificationDto)).toBeNull()
  })
})

const base: InsightsView = { personId: 'p-dad', disclaimer: DISCLAIMER, minJournalDays: 21, minStrength: 0.4, journalDays: 9, from: '2026-10-30', to: '2026-11-28', silent: true, message: 'Vita looks for what goes together once the journal has 21 days.', statements: [] }

describe('Goes with', () => {
  it('stays silent, with the reason, until the journal has enough days', async () => {
    vi.spyOn(vitaApi, 'insights').mockResolvedValue(base)
    renderWithSnapshot(<Insights personId="p-dad" />, makeSnapshot())
    expect(await screen.findByText(/once the journal has 21 days/)).toBeInTheDocument()
    expect(screen.getByText(/9 of the 21 journal days needed so far/)).toBeInTheDocument()
  })

  it('says "goes with" in the server’s words and shows the days behind it', async () => {
    vi.spyOn(vitaApi, 'insights').mockResolvedValue({
      ...base, journalDays: 24, silent: false, message: undefined, note: 'Going together does not mean one causes the other.',
      statements: [{
        journalField: 'mood', factor: 'sleep', text: 'Mood goes with sleep: on days with more sleep, mood tended to be higher.', rho: 0.52, days: 22,
        split: { factorMedian: 7, daysAbove: 10, daysAtOrBelow: 12, journalAverageAbove: 3.9, journalAverageAtOrBelow: 2.8 },
        data: [{ date: '2026-11-27', mood: 4, sleep: 7.5, rowIds: ['j1', 'z1'] }, { date: '2026-11-26', mood: 2, sleep: 5.25, rowIds: ['j2', 'z2'] }],
      }],
    })
    renderWithSnapshot(<Insights personId="p-dad" />, makeSnapshot())
    await screen.findByText(/Mood goes with sleep/)
    const card = within(screen.getByRole('region', { name: 'Goes with' }))
    expect(card.getByText('Mood goes with sleep: on days with more sleep, mood tended to be higher.')).toBeInTheDocument()
    expect(card.getByText('Going together does not mean one causes the other.')).toBeInTheDocument()
    expect(card.getByText(/sleep above 7 h, mood averaged 3.9/)).toBeInTheDocument()
    expect(card.queryByText(/causes(?! the other)/)).not.toBeInTheDocument()
    fireEvent.click(card.getByRole('button', { name: 'Show the 2 days' }))
    expect(card.getByText('4, good')).toBeInTheDocument()
    expect(card.getByText('5.3 h')).toBeInTheDocument()
  })
})
