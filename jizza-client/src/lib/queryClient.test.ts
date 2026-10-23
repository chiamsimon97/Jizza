import { describe, expect, it } from 'vitest'
import { shouldRetry } from '@/lib/queryClient'
import { ApiError } from '@/lib/api'

describe('shouldRetry', () => {
  const problem = (status: number) => new ApiError(status, { type: 'x', title: 'x', status })

  it('does not retry a refusal', () => {
    for (const status of [400, 401, 403, 404, 409]) expect(shouldRetry(0, problem(status))).toBe(false)
  })

  it('retries a server failure or a lost connection once', () => {
    expect(shouldRetry(0, problem(503))).toBe(true)
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetry(1, new TypeError('Failed to fetch'))).toBe(false)
  })
})
