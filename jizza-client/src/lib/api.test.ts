import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setStepUpHandler } from '@/lib/api'
import { useAuthStore } from '@/store/authStore'

function reply(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=token-1; path=/'
    useAuthStore.setState({ isAuthenticated: true, userId: 'user-1' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setStepUpHandler(null)
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('refuses to send the session to another origin', async () => {
    await expect(api.get('https://evil.example/api')).rejects.toThrow(/non-local/)
    await expect(api.get('//evil.example/api')).rejects.toThrow(/non-local/)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('echoes the CSRF token on a write and not on a read', async () => {
    fetchMock.mockImplementation(async () => reply(200, { ok: true }))
    await api.post('/api/things', { a: 1 })
    await api.get('/api/things')
    const [, write] = fetchMock.mock.calls[0]
    const [, read] = fetchMock.mock.calls[1]
    expect(new Headers(write.headers).get('X-XSRF-TOKEN')).toBe('token-1')
    expect(new Headers(write.headers).get('Content-Type')).toBe('application/json')
    expect(new Headers(read.headers).get('X-XSRF-TOKEN')).toBeNull()
  })

  it('reports a problem with its status and detail', async () => {
    fetchMock.mockResolvedValue(reply(400, { type: 'x/validation', title: 'Invalid', status: 400, detail: 'name is required' }))
    const error = (await api.post('/api/things', {}).catch(e => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.message).toBe('name is required')
  })

  it('turns a non-JSON failure into a readable error', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }))
    const error = (await api.get('/api/things').catch(e => e)) as ApiError
    expect(error.status).toBe(502)
    expect(error.message).toBe('Bad Gateway')
  })

  it('a lost connection rejects instead of hanging', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(api.get('/api/things')).rejects.toThrow('Failed to fetch')
  })

  it('an expired session signs the app out', async () => {
    fetchMock.mockResolvedValue(reply(401, { type: 'x/authentication', title: 'Authentication failed', status: 401 }))
    await expect(api.get('/api/agents')).rejects.toBeInstanceOf(ApiError)
    await vi.waitFor(() => expect(useAuthStore.getState().isAuthenticated).toBe(false))
    expect(fetchMock).toHaveBeenLastCalledWith('/api/auth/me', expect.anything())
  })

  it('a refused call does not sign out while the session is still good', async () => {
    fetchMock
      .mockResolvedValueOnce(reply(401, { type: 'x/authentication', title: 'Authentication failed', status: 401 }))
      .mockResolvedValueOnce(reply(200, { userId: 'user-1' }))
    await expect(api.get('/api/agents')).rejects.toBeInstanceOf(ApiError)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await new Promise(r => setTimeout(r, 0))
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('an unreachable server does not sign anyone out', async () => {
    fetchMock
      .mockResolvedValueOnce(reply(401, { type: 'x/authentication', title: 'Authentication failed', status: 401 }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(api.get('/api/agents')).rejects.toBeInstanceOf(ApiError)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await new Promise(r => setTimeout(r, 0))
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('a connected app whose sign-in expired does not sign the person out', async () => {
    fetchMock.mockResolvedValue(reply(401, { type: 'x/connection-expired', title: 'Authentication failed', status: 401 }))
    await expect(api.get('/api/connections/1/test')).rejects.toBeInstanceOf(ApiError)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('a wrong password on sign-in does not sign anyone out', async () => {
    fetchMock.mockResolvedValue(reply(401, { type: 'x/authentication', title: 'Invalid credentials', status: 401 }))
    await expect(api.post('/api/auth/login', {})).rejects.toBeInstanceOf(ApiError)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('asks for the password again on step-up and retries once', async () => {
    fetchMock
      .mockResolvedValueOnce(reply(403, { type: 'x/step-up-required', title: 'Step up', status: 403 }))
      .mockResolvedValueOnce(reply(200, { done: true }))
    const handler = vi.fn().mockResolvedValue(true)
    setStepUpHandler(handler)
    await expect(api.delete('/api/data?confirm=x')).resolves.toEqual({ done: true })
    expect(handler).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('gives up when the person cancels the step-up', async () => {
    fetchMock.mockResolvedValue(reply(403, { type: 'x/step-up-required', title: 'Step up', status: 403 }))
    setStepUpHandler(vi.fn().mockResolvedValue(false))
    await expect(api.delete('/api/data?confirm=x')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('treats an empty success as no body', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await expect(api.delete('/api/things/1')).resolves.toBeUndefined()
  })
})
