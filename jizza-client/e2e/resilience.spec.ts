import { test, expect } from './fixtures'
import { TEST_ACCOUNT } from './fixtures'

/** What a user sees when things go wrong: a lost network, a server failure, an expired session. */

test('a page whose data cannot load says so and offers to try again', async ({ page }) => {
  await page.route('**/api/tasks', route => route.abort('internetdisconnected'))
  await page.goto('/tasks')
  await expect(page.locator('main')).toContainText(/couldn.t|could not|failed|try again/i)
  await page.unroute('**/api/tasks')
})

test('a server failure on a page is shown as an error state, not a blank page', async ({ page }) => {
  await page.route('**/api/workflows', route => route.fulfill({
    status: 500, contentType: 'application/problem+json',
    body: JSON.stringify({ type: 'https://jizza.local/problems/internal', title: 'Internal error', status: 500, detail: 'Something went wrong.' }),
  }))
  await page.goto('/workflows')
  await expect(page.locator('main')).toContainText(/couldn.t|could not|went wrong|failed|try again/i)
})

test('an expired session sends the user back to sign in', async ({ page, context }) => {
  await page.goto('/agents')
  await expect(page.locator('main')).toContainText(/agents?/i)
  await context.clearCookies()
  await page.goto('/tasks')
  await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
})

test('signing in with the wrong password is refused with a message', async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await page.goto('/login')
  await page.getByLabel('Email').fill(TEST_ACCOUNT.email)
  await page.getByLabel('Password').fill('not-the-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.locator('form')).toContainText(/invalid|incorrect|wrong|failed/i)
  await expect(page).toHaveURL(/\/login/)
  await context.close()
})

test('registration validates email and password before creating anything', async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await page.goto('/register')
  await page.getByLabel('Email').fill('someone@jizza.test')
  await page.getByLabel('Password').fill('short')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page).toHaveURL(/\/register/)
  await context.close()
})

test('signing out ends the session', async ({ browser }) => {
  const context = await browser.newContext({ storageState: 'e2e/.auth/tester.json' })
  const page = await context.newPage()
  await page.goto('/settings/security')
  // A separate sign-in, so signing out here does not end the shared test session.
  await context.clearCookies()
  await page.goto('/login')
  await page.getByLabel('Email').fill(TEST_ACCOUNT.email)
  await page.getByLabel('Password').fill(TEST_ACCOUNT.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.getByRole('button', { name: /sign out|log out/i }).first().click()
  await expect(page).toHaveURL(/\/login/)
  await page.goto('/agents')
  await expect(page).toHaveURL(/\/login/)
  await context.close()
})
