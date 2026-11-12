import { describe, expect, it } from 'vitest'
import { matchWorkflows, runQuery, runTemplate } from './commands'

describe('/run', () => {
  it('reads what is typed after the command on the first line only', () => {
    expect(runQuery('/run ')).toBe('')
    expect(runQuery('/run Sim')).toBe('Sim')
    expect(runQuery('/RUN simon workflow')).toBe('simon workflow')
    expect(runQuery('/run')).toBeNull()
    expect(runQuery('/runner')).toBeNull()
    expect(runQuery('/run Booking\nmovie: ')).toBeNull()
    expect(runQuery('please /run x')).toBeNull()
  })

  it('lists workflows that start with the words first', () => {
    const all = [{ name: 'Weekly Simon report' }, { name: 'Simon workflow' }, { name: 'Ticket booking' }]
    expect(matchWorkflows(all, 'simon').map(w => w.name)).toEqual(['Simon workflow', 'Weekly Simon report'])
    expect(matchWorkflows(all, '')).toHaveLength(3)
    expect(matchWorkflows(all, 'gold')).toEqual([])
  })

  it('asks for the inputs a workflow takes before it runs', () => {
    expect(runTemplate({ name: 'Simon workflow' })).toBeNull()
    expect(runTemplate({ name: 'Ticket booking', profile: { inputs: [{ name: 'movie' }, { name: 'seats' }] } }))
      .toBe('/run Ticket booking\nmovie: \nseats: ')
  })
})
