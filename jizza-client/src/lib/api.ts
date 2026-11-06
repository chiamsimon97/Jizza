import type { ProblemDetail } from '@/types'
import { useAuthStore } from '@/store/authStore'
import { endLocalSession } from '@/lib/session'
import { queryClient } from '@/lib/queryClient'

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

/**
 * A 401 on one call is not proof the session is gone. Switching accounts replaces the session
 * cookie, so a call already in flight with the old cookie is refused even though the person is
 * still signed in. Before signing out, ask the server once whether this browser's session is
 * really over; several refused calls at once share that one check.
 */
let sessionCheck: Promise<void> | null = null
function signOutIfSessionGone(): Promise<void> {
  sessionCheck ??= (async () => {
    try {
      const res = await fetch('/api/auth/me', { credentials: 'same-origin' })
      if (res.status === 401 && useAuthStore.getState().isAuthenticated) endLocalSession()
    } catch {
      // The server could not be reached (a restart, a lost connection): that is not a sign-out.
    } finally {
      sessionCheck = null
    }
  })()
  return sessionCheck
}

async function request<T>(path: string, init: RequestInit = {}, as: 'json' | 'blob' = 'json', retried = false): Promise<T> {
  // Only ever this app's own API: the session cookie and CSRF token must never ride along to
  // another origin, whatever string ends up in `path`.
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new Error(`Refusing to call a non-local URL: ${path}`)
  }
  const mutating = init.method != null && init.method !== 'GET'
  // A fresh browser has no XSRF-TOKEN cookie until the server sets one, so fetch it before the
  // first mutating call (e.g. register or login) rather than letting that call be rejected.
  if (mutating && !getCsrfToken()) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' })
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

  const res = await fetch(path, { ...init, credentials: 'same-origin', headers })

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
  // The session expired or was revoked. Forget the stale login so the app goes back to sign-in,
  // instead of every page showing its own "couldn't load" error. Auth calls report their own 401s.
  // Only a 401 about the session itself counts: a connected app whose sign-in expired (an OAuth
  // token, an MCP server, a device token) also answers 401, with its own problem type, and must not
  // sign the person out of Jizza.
  if (res.status === 401 && !path.startsWith('/api/auth/') && useAuthStore.getState().isAuthenticated
      && /\/authentication$/.test(String(problem.type ?? ''))) {
    void signOutIfSessionGone()
  }
  // Two-step sign-in is required and this session has not set it up: the app routes to setup.
  if (res.status === 403 && String(problem.type ?? '').endsWith('mfa-enrollment-required')) {
    useAuthStore.getState().setEnrollmentRequired(true)
  }
  // The server refused an action this role or account can't take. What the app believes the user may
  // do is out of date (a role change, a removal), so fetch it again; the caller still sees the error.
  if (res.status === 403 && String(problem.type ?? '').endsWith('permission-denied')) {
    void queryClient.invalidateQueries({ queryKey: ['me', 'context'] })
  }
  if (res.status === 403 && !retried && stepUpHandler && String(problem.type ?? '').endsWith('step-up-required')) {
    if (await stepUpHandler()) return request<T>(path, init, as, true)
  }
  throw new ApiError(res.status, problem)
}

export const api = {
  get:    <T>(path: string, headers?: HeadersInit) => request<T>(path, { method: 'GET', headers }),
  post:   <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST',   body: body != null ? JSON.stringify(body) : undefined }),
  put:    <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT',    body: body != null ? JSON.stringify(body) : undefined }),
  patch:  <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH',  body: body != null ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string, headers?: HeadersInit) => request<T>(path, { method: 'DELETE', headers }),
  upload: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
  /** Raw bytes, for files shown back to the user. */
  blob:   (path: string) => request<Blob>(path, { method: 'GET' }, 'blob'),
}
