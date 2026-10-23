import { describe, expect, it } from 'vitest'
import type { ProcedureSummary, TaskDto } from '@/types'
import { describeSchedule, linkOf, toEngineCron } from './taskModel'

const task = (over: Partial<TaskDto> = {}): TaskDto => ({
  id: 't1', title: 'Weekly check-in', agentId: 'a1', agentName: 'Kai', scheduleType: 'RECURRING',
  cronOrRrule: '0 0 9 * * MON', status: 'PENDING', repetitive: false, ...over,
})

const procedure = (status: string, over: Partial<ProcedureSummary> = {}): ProcedureSummary => ({
  workflow: { id: 'w1', name: 'Check-in steps', graph: {}, origin: 'AGENT_AUTHORED', status, version: 1, createdAt: '', sourceTaskId: 't1' },
  disabled: false, sourceTaskTitle: 'Weekly check-in', ...over,
})

describe('schedules', () => {
  it('turns a five-field cron into the six fields the engine reads', () => {
    expect(toEngineCron('0 8 * * 1-5')).toBe('0 0 8 * * 1-5')
    expect(toEngineCron('0 0 8 * * *')).toBe('0 0 8 * * *')
  })
  it('reads common schedules in words', () => {
    expect(describeSchedule(task())).toBe('Every Monday at 9am')
    expect(describeSchedule(task({ cronOrRrule: '30 17 * * 1-5' }))).toBe('Every weekday at 5:30pm')
    expect(describeSchedule(task({ cronOrRrule: '0 0 8 * * *' }))).toBe('Every day at 8am')
    expect(describeSchedule(task({ cronOrRrule: 'FREQ=WEEKLY' }))).toBe('Repeats (FREQ=WEEKLY)')
  })
})

describe('linkOf', () => {
  it('says the agent decides when there is no procedure', () => {
    expect(linkOf(task(), []).kind).toBe('agent')
  })
  it('says it is learning when learning is on and nothing is written yet', () => {
    const link = linkOf(task({ repetitive: true }), [])
    expect(link.kind).toBe('learning')
    expect(link.text).toContain('After its next run, Kai')
  })
  it('points to a procedure waiting for review', () => {
    const link = linkOf(task({ repetitive: true }), [procedure('PENDING_REVIEW')])
    expect(link.kind).toBe('review')
    expect(link.procedure?.workflow.id).toBe('w1')
  })
  it('says it follows the approved procedure it is bound to', () => {
    const link = linkOf(task({ repetitive: true, boundWorkflowId: 'w1', boundVersion: 2 }), [procedure('APPROVED', { boundVersion: 2 })])
    expect(link.kind).toBe('follows')
    expect(link.text).toContain('"Check-in steps" (version 2)')
  })
})
