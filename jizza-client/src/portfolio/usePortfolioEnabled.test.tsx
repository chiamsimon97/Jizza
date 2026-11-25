import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { financeApi } from '@/api/finance'
import { ApiError } from '@/lib/api'
import { usePortfolioEnabled } from './usePortfolioEnabled'

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('usePortfolioEnabled', () => {
  it('is on for Kai when the server switch is on', async () => {
    vi.spyOn(financeApi, 'status').mockResolvedValue({ enabled: true })
    const { result } = renderHook(() => usePortfolioEnabled({ key: 'finance' }), { wrapper: wrapper() })
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('is off when the server answers 404 because the switch is off', async () => {
    const status = vi.spyOn(financeApi, 'status').mockRejectedValue(new ApiError(404, { type: 'about:blank', title: 'Not Found', status: 404 }))
    const { result } = renderHook(() => usePortfolioEnabled({ key: 'finance' }), { wrapper: wrapper() })
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1))
    expect(result.current).toBe(false)
  })

  it('never asks the server for any agent but Kai', () => {
    const status = vi.spyOn(financeApi, 'status')
    const { result } = renderHook(() => usePortfolioEnabled({ key: 'health' }), { wrapper: wrapper() })
    expect(result.current).toBe(false)
    expect(status).not.toHaveBeenCalled()
  })
})
