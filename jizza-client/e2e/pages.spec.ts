import { test, expect } from './fixtures'
import { PAGES } from './routes'

// Each page, opened directly, renders its content without a script error, a console error, an
// error boundary or a failed server call.
for (const p of PAGES) {
  test(`${p.name} page opens cleanly`, async ({ page, problems }) => {
    await page.goto(p.path)
    await expect(page).toHaveURL(new RegExp(p.path.replace(/\//g, '\\/')))
    await expect(page.locator('main')).toContainText(p.expect)
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('alert').filter({ hasText: 'This page hit a problem' })).toHaveCount(0)
    expect(problems).toEqual([])
  })
}

test('an unknown address goes to chat', async ({ page }) => {
  await page.goto('/there-is-no-such-page')
  await expect(page).toHaveURL(/\/chat/)
})

test('the sidebar reaches every section', async ({ page, problems }) => {
  await page.goto('/chat')
  for (const name of ['Agents', 'Tasks', 'Workflows', 'Activity']) {
    await page.getByRole('navigation').getByRole('link', { name, exact: false }).first().click()
    await expect(page.locator('main')).toContainText(new RegExp(name, 'i'))
  }
  expect(problems).toEqual([])
})
