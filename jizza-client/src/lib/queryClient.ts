import { QueryClient } from '@tanstack/react-query'

/** A refusal (4xx) will be refused again; only a dropped connection or a server failure is retried. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const status = (error as { status?: unknown } | null)?.status
  if (typeof status === 'number' && status >= 400 && status < 500) return false
  return failureCount < 1
}

/** The one query cache. Exported so signing out can wipe every cached response. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: shouldRetry,
    },
  },
})
