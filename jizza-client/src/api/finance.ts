import { api } from '@/lib/api'
import type { FinConfigItem, FinConsent, FinPerson, FinPersonInput, FinProfileView, FinSettings, JsonValue } from '@/types/finance'

/** Kai's Portfolio (spec 5.15). Every path answers 404 while the server's finance switch is off. */
export const financeApi = {
  status: () => api.get<{ enabled: boolean }>('/api/finance/status'),

  profile: () => api.get<FinProfileView>('/api/finance/profile'),
  updateSettings: (patch: Partial<FinSettings>) => api.patch<FinSettings>('/api/finance/profile/settings', patch),

  people: () => api.get<FinPerson[]>('/api/finance/people'),
  addPerson: (body: FinPersonInput) => api.post<FinPerson>('/api/finance/people', body),
  updatePerson: (id: string, body: FinPersonInput) => api.patch<FinPerson>(`/api/finance/people/${id}`, body),
  /** Archives: the records stay and the person leaves the switchers. */
  archivePerson: (id: string) => api.delete<FinPerson>(`/api/finance/people/${id}`),

  consents: (personId: string) => api.get<FinConsent[]>(`/api/finance/people/${personId}/consents`),
  recordConsent: (personId: string, body: { scope: 'view' | 'manage'; channel?: string; evidenceFileId?: string }) =>
    api.post<FinConsent>(`/api/finance/people/${personId}/consents`, body),
  /** Needs a fresh password; the app asks for it and retries. */
  revokeConsent: (id: string) => api.post<FinConsent>(`/api/finance/consents/${id}/revoke`),

  config: () => api.get<FinConfigItem[]>('/api/finance/config'),
  setConfig: (key: string, value: JsonValue) => api.put<FinConfigItem>(`/api/finance/config/${encodeURIComponent(key)}`, { value }),
  resetConfig: (key: string) => api.delete<FinConfigItem>(`/api/finance/config/${encodeURIComponent(key)}`),
}
