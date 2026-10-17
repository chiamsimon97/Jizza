import { QueryClient } from '@tanstack/react-query'

/** The one query cache. Exported so signing out can wipe every cached response. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})
