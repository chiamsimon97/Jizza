import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * The one account the browser suite uses. It exists only in the suite's own database; it is never
 * a real person's or a client's account.
 */
export const TEST_ACCOUNT = {
  email: process.env.JIZZA_E2E_EMAIL ?? 'early-access-tester@jizza.test',
  password: process.env.JIZZA_E2E_PASSWORD ?? 'EarlyAccess-Tester-2026',
}

export const STORAGE_STATE = 'e2e/.auth/tester.json'

/** Calls the API the way the client does: same origin, cookies kept, CSRF echoed. */
export async function apiCall(request: APIRequestContext, method: string, path: string, body?: unknown) {
  const state = await request.storageState()
  let csrf = state.cookies.find(c => c.name === 'XSRF-TOKEN')?.value
  if (!csrf && method !== 'GET') {
    await request.get('/api/auth/csrf')
    csrf = (await request.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN')?.value
  }
  return request.fetch(path, {
    method,
    data: body,
    headers: csrf && method !== 'GET' ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
  })
}

/**
 * Collects what would make a page "broken" for a user: an uncaught script error, a console error,
 * or an API call the server failed (5xx). Each test asserts it stayed empty.
 */
export function watchForBreakage(page: Page) {
  const problems: string[] = []
  page.on('pageerror', e => problems.push(`script error: ${e.message}`))
  page.on('console', msg => {
    if (msg.type() !== 'error') return
    const text = msg.text()
    // A refused API call is logged by the browser as a failed resource load; those are judged by
    // status below, where a 4xx the page handled is fine.
    if (/Failed to load resource/.test(text)) return
    // WebSocket reconnects in the dev server are noise, not a defect.
    if (/WebSocket|sockjs|ws:\/\//i.test(text)) return
    problems.push(`console error: ${text}`)
  })
  page.on('response', res => {
    if (res.url().includes('/api/') && res.status() >= 500) {
      problems.push(`server error: ${res.request().method()} ${new URL(res.url()).pathname} -> ${res.status()}`)
    }
  })
  return problems
}

export const test = base.extend<{ problems: string[] }>({
  problems: async ({ page }, use) => {
    const problems = watchForBreakage(page)
    await use(problems)
    expect(problems, 'the page broke while the test ran').toEqual([])
  },
})

export { expect }
