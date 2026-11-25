import { api } from '@/lib/api'

/** Kai's Portfolio. Every path answers 404 while the server's finance switch is off. */
export const financeApi = {
  status: () => api.get<{ enabled: boolean }>('/api/finance/status'),
}
