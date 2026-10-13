import type { ProblemDetail } from '@/types'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ProblemDetail,
  ) {
    super(problem.detail ?? problem.title)
  }
}

function getCsrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : undefined
}

/**
 * Sensitive routes answer 403 "step-up-required" until the user re-enters their password. The app
 * shell registers a handler that asks for it; the request is then retried once.
 */
let stepUpHandler: (() => Promise<boolean>) | null = null
export function setStepUpHandler(handler: (() => Promise<boolean>) | null) {
  stepUpHandler = handler
}

async function request<T>(path: string, init: RequestInit = {}, as: 'json' | 'blob' = 'json', retried = false): Promise<T> {
  const mutating = init.method != null && init.method !== 'GET'
  // A fresh browser has no XSRF-TOKEN cookie until the server sets one, so fetch it before the
  // first mutating call (e.g. register or login) rather than letting that call be rejected.
  if (mutating && !getCsrfToken()) {
    await fetch('/api/auth/csrf', { credentials: 'include' })
  }
  const csrf = getCsrfToken()
  const headers = new Headers(init.headers)
  // A FormData body sets its own multipart Content-Type, boundary included.
  if (!headers.has('Content-Type') && init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (csrf && mutating) {
    headers.set('X-XSRF-TOKEN', csrf)
  }

  const res = await fetch(path, { ...init, credentials: 'include', headers })

  if (res.ok) {
    if (res.status === 204 || res.headers.get('Content-Length') === '0') {
      return undefined as T
    }
    if (as === 'blob') return res.blob() as Promise<T>
    return res.json() as Promise<T>
  }

  let problem: ProblemDetail
  try {
    problem = await res.json()
  } catch {
    problem = { type: 'error', title: res.statusText, status: res.status }
  }
  if (res.status === 403 && !retried && stepUpHandler && String(problem.type ?? '').endsWith('step-up-required')) {
    if (await stepUpHandler()) return request<T>(path, init, as, true)
  }
  throw new ApiError(res.status, problem)
}

export const api = {
  get:    <T>(path: string) => request<T>(path, { method: 'GET' }),
  post:   <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST',   body: body != null ? JSON.stringify(body) : undefined }),
  put:    <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT',    body: body != null ? JSON.stringify(body) : undefined }),
  patch:  <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH',  body: body != null ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  upload: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
  /** Raw bytes, for files shown back to the user. */
  blob:   (path: string) => request<Blob>(path, { method: 'GET' }, 'blob'),
}
