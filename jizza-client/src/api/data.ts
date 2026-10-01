import { api } from '@/lib/api'

export const dataApi = {
  export: () =>
    api.get<unknown>('/api/data/export'),

  hardDelete: (confirmation: string) =>
    api.post<void>('/api/data/delete', { confirmation }),
}
