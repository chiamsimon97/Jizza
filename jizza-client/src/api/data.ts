import { api } from '@/lib/api'

/** The phrase the server wants typed before it deletes an account and everything in it. */
export const DELETE_CONFIRMATION = 'DELETE EVERYTHING'

export const dataApi = {
  export: () =>
    api.get<unknown>('/api/data/export'),

  hardDelete: (confirmation: string) =>
    api.delete<{ message: string }>(`/api/data?confirm=${encodeURIComponent(confirmation)}`),
}
