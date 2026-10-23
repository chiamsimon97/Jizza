import { test, expect, TEST_ACCOUNT } from './fixtures'

/** The user flows a first client goes through, top to bottom, on the dedicated test account. */

test('chat: a message gets a reply that stays in the conversation', async ({ page, problems }) => {
  await page.goto('/chat')
  const box = page.getByPlaceholder(/Ask Jizza/)
  await box.fill('What can you do for me?')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText('What can you do for me?').first()).toBeVisible()
  await expect(page.getByText(/Test model reply to: .*What can you do for me/).first()).toBeVisible({ timeout: 30_000 })

  // The conversation is still there after a reload.
  await page.reload()
  await expect(page.getByText('What can you do for me?').first()).toBeVisible()
  expect(problems).toEqual([])
})

test('chat: an empty message cannot be sent', async ({ page }) => {
  await page.goto('/chat')
  await page.getByPlaceholder(/Ask Jizza/).fill('   ')
  await expect(page.getByRole('button', { name: 'Send' })).toBeDisabled()
})

test('tasks: the seeded task is listed and a new one-off task can be created', async ({ page, problems }) => {
  await page.goto('/tasks')
  await expect(page.getByText('Seed: weekly review')).toBeVisible()

  await page.getByRole('button', { name: 'New task' }).click()
  await page.getByRole('button', { name: 'Create task' }).click()
  await expect(page.getByText('Say what the task is.')).toBeVisible()

  const title = `Check invoices ${Date.now()}`
  await page.getByLabel('What should happen').fill(title)
  await page.getByLabel('Who does it').selectOption({ index: 1 })
  const tomorrow = new Date(Date.now() + 86_400_000)
  const local = new Date(tomorrow.getTime() - tomorrow.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  await page.getByLabel('When').fill(local)
  await page.getByRole('button', { name: 'Create task' }).click()
  await expect(page.getByText(title)).toBeVisible()
  expect(problems).toEqual([])
})

test('tasks: a one-off task in the past is refused with a message', async ({ page }) => {
  await page.goto('/tasks')
  await page.getByRole('button', { name: 'New task' }).click()
  await page.getByLabel('What should happen').fill('Yesterday’s task')
  await page.getByLabel('Who does it').selectOption({ index: 1 })
  await page.getByLabel('When').fill('2020-01-01T09:00')
  await page.getByRole('button', { name: 'Create task' }).click()
  await expect(page.getByText(/past|future|already/i).first()).toBeVisible()
})

test('workflows: the seeded workflow is listed and opens', async ({ page, problems }) => {
  await page.goto('/workflows')
  await expect(page.getByText('Seed: morning digest').first()).toBeVisible()
  await page.getByText('Seed: morning digest').first().click()
  await expect(page.getByLabel('Name')).toHaveValue('Seed: morning digest')
  expect(problems).toEqual([])
})

test('connectors: a service is created, then refused without a usable address', async ({ page, problems }) => {
  await page.goto('/connectors')
  await page.getByRole('button', { name: 'New service' }).click()
  await page.getByLabel('Name').fill('Not a service')
  await page.getByLabel('Base URL').fill('not a url')
  await page.getByRole('button', { name: 'Create service' }).click()
  await expect(page.getByText(/must be a web address/)).toBeVisible()

  const name = `Test CRM ${Date.now()}`
  await page.getByLabel('Name').fill(name)
  await page.getByLabel('Base URL').fill('https://api.example.com')
  await page.getByRole('button', { name: 'Create service' }).click()
  await expect(page.getByText(name).first()).toBeVisible()
  expect(problems).toEqual([])
})

test('security: mismatched new passwords are caught before anything is sent', async ({ page }) => {
  await page.goto('/settings/security')
  await page.getByLabel('Current password').fill('whatever-it-is')
  await page.getByLabel('New password', { exact: true }).fill('Another-Password-2026')
  await page.getByLabel('Repeat new password').fill('Different-Password-2026')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page.getByText('The new passwords do not match')).toBeVisible()
})

test('security: a wrong current password is reported, not a crash', async ({ page, problems }) => {
  await page.goto('/settings/security')
  await page.getByLabel('Current password').fill('definitely-not-it-2026')
  await page.getByLabel('New password', { exact: true }).fill('Another-Password-2026!')
  await page.getByLabel('Repeat new password').fill('Another-Password-2026!')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page.locator('form').getByText(/password/i).last()).toBeVisible()
  expect(problems).toEqual([])
})

test('your data: an export downloads, and deleting needs the typed phrase', async ({ page, problems }) => {
  await page.goto('/settings/security')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download my data' }).click()
  // Taking everything out is a sensitive action: the password is asked for again first.
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Password').fill(TEST_ACCOUNT.password)
  await dialog.getByRole('button', { name: 'Confirm' }).click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(/^jizza-export-.*\.json$/)

  await page.getByRole('button', { name: 'Delete everything' }).click()
  const confirm = page.getByRole('button', { name: 'Delete my account' })
  await expect(confirm).toBeDisabled()
  await page.getByLabel('Confirmation').fill('delete everything')
  await expect(confirm).toBeDisabled()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await expect(confirm).toHaveCount(0)
  expect(problems).toEqual([])
})

test('agents: the team is listed and an agent opens', async ({ page, problems }) => {
  await page.goto('/agents')
  await expect(page.locator('main')).toContainText(/Kai|Vita|Cato/)
  await page.locator('main').getByText(/Cato/).first().click()
  await expect(page).toHaveURL(/\/agents\/[0-9a-f-]{36}/)
  expect(problems).toEqual([])
})

test('notifications: the inbox shows its tabs', async ({ page, problems }) => {
  await page.goto('/notifications')
  for (const tab of ['Needs you', 'Updates', 'Snoozed', 'History']) {
    await expect(page.getByText(tab, { exact: false }).first()).toBeVisible()
  }
  expect(problems).toEqual([])
})
