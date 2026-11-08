import { api } from '@/lib/api'
import type { FormField, FormSettings } from '@/components/formbuilder/formModel'

export interface LinkedWorkflow { id: string; name: string; enabled: boolean }

export interface FormDto {
  id: string
  name: string
  description?: string
  fields: FormField[]
  settings: FormSettings
  status: 'draft' | 'published'
  /** The public link's token; kept when the form is unpublished, so a shared link works again. */
  publicToken?: string
  publishedAt?: string
  /** The workflow the form was started from, when it was. */
  sourceWorkflowId?: string
  submissionCount: number
  createdAt: string
  updatedAt: string
  /** The workflows a submission starts. */
  workflows: LinkedWorkflow[]
}

export interface FormDraft {
  name: string
  description?: string
  fields: FormField[]
  settings: FormSettings
  sourceWorkflowId?: string
}

export interface PublicForm { name: string; description?: string; fields: FormField[]; settings: FormSettings }

export interface FormSubmission {
  id: string
  number: number
  data: Record<string, unknown>
  runs: Array<{ workflowId: string; workflowName?: string; runId?: string; error?: string }>
  submittedAt: string
}

export const formsApi = {
  list: () => api.get<FormDto[]>('/api/forms'),
  get: (id: string) => api.get<FormDto>(`/api/forms/${id}`),
  create: (draft: FormDraft) => api.post<FormDto>('/api/forms', draft),
  update: (id: string, draft: FormDraft) => api.put<FormDto>(`/api/forms/${id}`, draft),
  remove: (id: string) => api.delete<void>(`/api/forms/${id}`),
  publish: (id: string) => api.post<FormDto>(`/api/forms/${id}/publish`),
  unpublish: (id: string) => api.post<FormDto>(`/api/forms/${id}/unpublish`),
  setWorkflows: (id: string, workflowIds: string[]) => api.put<FormDto>(`/api/forms/${id}/workflows`, { workflowIds }),
  submissions: (id: string, limit = 100) => api.get<FormSubmission[]>(`/api/forms/${id}/submissions?limit=${limit}`),
  file: (id: string, submissionId: string, field: string) =>
    api.blob(`/api/forms/${id}/submissions/${submissionId}/files/${encodeURIComponent(field)}`),

  publicForm: (token: string) => api.get<PublicForm>(`/api/public/forms/${encodeURIComponent(token)}`),
  submit: (token: string, data: Record<string, unknown>) =>
    api.post<{ number: number; message: string }>(`/api/public/forms/${encodeURIComponent(token)}/submit`, data),
}

/** A published form's public address. */
export function publicFormUrl(token: string): string {
  return `${window.location.origin}/f/${token}`
}
