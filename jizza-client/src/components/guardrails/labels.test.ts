import { describe, expect, it } from 'vitest'
import { describeLimits, describeScope, tokenFor } from './labels'

describe('guardrail labels', () => {
  it('describes limits in plain words', () => {
    expect(describeLimits({ maxValue: 500, field: 'amount', budget: { max: 2000, per: 'P7D' } }))
      .toEqual(['only up to 500 amount per call', 'at most 2000 in total a week'])
    expect(describeLimits(null)).toEqual([])
  })

  it('names who and what a rule covers', () => {
    const name = () => 'Kai'
    expect(describeScope({ agentId: 'k', toolRef: tokenFor('MONEY'), rule: 'ASK', precedence: 10 }, name))
      .toBe('Kai: move money')
    expect(describeScope({ toolRef: '*:side-effecting', rule: 'ASK', precedence: 0 }, name))
      .toBe('Every agent: anything that changes something outside Jizza')
    expect(describeScope({ agentId: 'k', toolRef: 'broker_place_order', rule: 'ALLOW', precedence: 0, workflowId: 'w' }, name))
      .toBe('Kai: broker_place_order in one workflow')
  })
})
