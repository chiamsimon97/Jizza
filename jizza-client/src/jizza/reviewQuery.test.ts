import { describe, expect, it } from 'vitest'
import { reviewQuery } from './assistantStore'

describe('reviewQuery', () => {
  const id = '0b7c5f1e-3a2d-4c8e-9f10-1a2b3c4d5e6f'

  it('links the Recordings page to one recording', () => {
    expect(reviewQuery({ page: 'recordings', review: id })).toBe(`?review=${id}`)
  })

  it('ignores anything that is not a recording id, or another page', () => {
    expect(reviewQuery({ page: 'recordings' })).toBe('')
    expect(reviewQuery({ page: 'recordings', review: '../settings' })).toBe('')
    expect(reviewQuery({ page: 'workflows', review: id })).toBe('')
  })
})
