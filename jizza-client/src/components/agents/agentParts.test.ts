import { describe, expect, it } from 'vitest'
import type { AgentDto } from '@/types'
import { CREATED_PROFILE, emptyDraft, groupByTeam, looser, stepUpReasons } from './agentParts'

const agent = (id: string, name: string, extra: Partial<AgentDto> = {}): AgentDto =>
  ({ id, key: id, name, enabled: true, orchestrator: false, status: 'active', parentAgentId: 'jizza', ...extra })

describe('agent helpers', () => {
  it('groups agents by the team they sit in, leaving out Jizza, drafts and archived ones', () => {
    const groups = groupByTeam([
      agent('jizza', 'Jizza', { orchestrator: true, parentAgentId: null }),
      agent('pm', 'Product Manager'),
      agent('eng', 'Engineering Lead', { parentAgentId: 'pm' }),
      agent('mod', 'Module Owner', { parentAgentId: 'eng' }),
      agent('draft', 'Draft', { status: 'draft' }),
      agent('old', 'Old', { status: 'archived' }),
    ])
    expect(groups.map(g => [g.label, g.agents.map(a => a.id)])).toEqual([
      ["Jizza's team", ['pm']],
      ["Product Manager's team", ['eng']],
      ['Product Manager › Engineering Lead\'s team', ['mod']],
    ])
  })

  it('asks for step-up only when the new agent gets more than the safe start', () => {
    const d = emptyDraft('jizza')
    expect(stepUpReasons(d)).toEqual([])
    expect(stepUpReasons({ ...d, modes: { ...d.modes, SEND: 'ALLOW' } })).toEqual(['a rule is looser than the safe default'])
    expect(stepUpReasons({ ...d, modes: { ...d.modes, SEND: 'DENY' } })).toEqual([])
    expect(looser('ALLOW', CREATED_PROFILE.SEND)).toBe(true)
  })
})
