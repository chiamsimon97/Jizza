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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const csrf = getCsrfToken()
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (csrf && init.method && init.method !== 'GET') {
    headers.set('X-XSRF-TOKEN', csrf)
  }

  const res = await fetch(path, { ...init, credentials: 'include', headers })

  if (res.ok) {
    if (res.status === 204 || res.headers.get('Content-Length') === '0') {
      return undefined as T
    }
    return res.json() as Promise<T>
  }

  let problem: ProblemDetail
  try {
    problem = await res.json()
  } catch {
    problem = { type: 'error', title: res.statusText, status: res.status }
  }
  throw new ApiError(res.status, problem)
}

export const api = {
  get:    <T>(path: string) => request<T>(path, { method: 'GET' }),
  post:   <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST',   body: body != null ? JSON.stringify(body) : undefined }),
  put:    <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT',    body: body != null ? JSON.stringify(body) : undefined }),
  patch:  <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH',  body: body != null ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
