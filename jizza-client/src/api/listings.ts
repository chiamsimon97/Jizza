import { api } from '@/lib/api'
import type { DirectoryPage, DirectoryProfile, JoinRequest, Listing, ListingProfile, ListingStatus, AccountRegistry, WorkspaceRole } from '@/types'

const seg = encodeURIComponent

/** The active organization's public listing, and the requests to join it (Owner and Admin). */
export const listingApi = {
  get: () => api.get<Listing>('/api/organization/listing'),
  save: (profile: ListingProfile) => api.put<Listing>('/api/organization/listing', profile),
  submit: () => api.post<Listing>('/api/organization/listing/submit'),
  withdraw: () => api.post<Listing>('/api/organization/listing/withdraw'),
  unlist: () => api.post<Listing>('/api/organization/listing/unlist'),

  joinRequests: () => api.get<JoinRequest[]>('/api/organization/join-requests'),
  accept: (id: string) => api.post<JoinRequest>(`/api/organization/join-requests/${seg(id)}/accept`),
  decline: (id: string) => api.post<JoinRequest>(`/api/organization/join-requests/${seg(id)}/decline`),
}

/** The Super Admin's Listing reviews queue. */
export const listingReviewApi = {
  queue: (status: ListingStatus) => api.get<Listing[]>(`/api/admin/listings?status=${seg(status)}`),
  item: (id: string) => api.get<Listing>(`/api/admin/listings/${seg(id)}`),
  approve: (id: string, note?: string) => api.post<Listing>(`/api/admin/listings/${seg(id)}/approve`, { note }),
  reject: (id: string, note: string) => api.post<Listing>(`/api/admin/listings/${seg(id)}/reject`, { note }),
  suspend: (id: string, note: string) => api.post<Listing>(`/api/admin/listings/${seg(id)}/suspend`, { note }),
  reinstate: (id: string, note?: string) => api.post<Listing>(`/api/admin/listings/${seg(id)}/reinstate`, { note }),
}

/** The Super Admin's Registered accounts: every account in Jizza Public, and people's roles there. */
export const accountRegistryApi = {
  list: (q = '') => api.get<AccountRegistry>(`/api/admin/accounts?q=${seg(q)}`),
  setRole: (userId: string, role: WorkspaceRole) =>
    api.put<{ userId: string; role: WorkspaceRole }>(`/api/admin/accounts/people/${seg(userId)}/public-role`, { role }),
}

/** Discover: organizations a Super Admin approved, for everyone signed in. */
export const directoryApi = {
  search: (q: string, category: string, page = 0) => {
    const params = new URLSearchParams()
    if (q.trim()) params.set('q', q.trim())
    if (category) params.set('category', category)
    if (page > 0) params.set('page', String(page))
    const qs = params.toString()
    return api.get<DirectoryPage>(`/api/directory${qs ? `?${qs}` : ''}`)
  },
  categories: () => api.get<{ categories: string[] }>('/api/directory/categories'),
  profile: (id: string) => api.get<DirectoryProfile>(`/api/directory/${seg(id)}`),
  askToJoin: (id: string, message: string) =>
    api.post<JoinRequest>(`/api/directory/${seg(id)}/join-request`, { message: message.trim() || null }),
  cancel: (id: string) => api.delete<{ message: string }>(`/api/directory/${seg(id)}/join-request`),
}

const CATEGORY_LABELS: Record<string, string> = {
  agency: 'Agency', consulting: 'Consulting', education: 'Education', finance: 'Finance',
  healthcare: 'Healthcare', marketing: 'Marketing', nonprofit: 'Nonprofit', operations: 'Operations',
  retail: 'Retail', technology: 'Technology', other: 'Other',
}

export function categoryLabel(category: string) {
  return CATEGORY_LABELS[category] ?? category
}
