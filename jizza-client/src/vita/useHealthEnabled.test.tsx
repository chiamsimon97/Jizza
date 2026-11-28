import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { ApiError } from '@/lib/api'
import { useHealthEnabled } from './useHealthEnabled'

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

afterEach(() => vi.restoreAllMocks())

describe('useHealthEnabled', () => {
  it('is on for Vita when the server switch is on', async () => {
    vi.spyOn(vitaApi, 'status').mockResolvedValue({ enabled: true })
    const { result } = renderHook(() => useHealthEnabled({ key: 'health' }), { wrapper: wrapper() })
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('is off when /api/vita/status answers 404 because the switch is off', async () => {
    const status = vi.spyOn(vitaApi, 'status').mockRejectedValue(new ApiError(404, { type: 'about:blank', title: 'Not Found', status: 404 }))
    const { result } = renderHook(() => useHealthEnabled({ key: 'health' }), { wrapper: wrapper() })
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1))
    expect(result.current).toBe(false)
  })

  it.each(['finance', 'research', undefined])('is hidden for the agent with key %s and never asks the server', key => {
    const status = vi.spyOn(vitaApi, 'status').mockResolvedValue({ enabled: true })
    const { result } = renderHook(() => useHealthEnabled(key ? { key } : undefined), { wrapper: wrapper() })
    expect(result.current).toBe(false)
    expect(status).not.toHaveBeenCalled()
  })
})
