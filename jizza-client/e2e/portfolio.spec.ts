import { test, expect, apiCall } from './fixtures'

/**
 * Kai Portfolio, Phase 1a: Quick start steps 1 to 6 in the browser on the test account, saved as
 * each is answered, one question skipped, ending on the result and the Money Health home.
 * Steps 7 and 8 (goals and risk) come with Phase 1b. Answering again replaces the earlier answers,
 * so the test runs the same on a database that has seen it before.
 */
test('portfolio: Quick start from the first question to the result', async ({ page, request, problems }) => {
  const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
  const kai = agents.find((a: { key: string }) => a.key === 'finance')
  expect(kai, 'Kai is a built-in agent').toBeTruthy()

  await page.goto(`/agents/${kai.id}?tab=portfolio&view=quick-start&step=people`)

  // 1. Whose money
  await expect(page.getByText('Question 1 of 6')).toBeVisible()
  await page.getByLabel('Your date of birth').fill('1992-06-01')
  await page.getByRole('button', { name: 'Next' }).click()

  // 2. Income
  await expect(page.getByText('Question 2 of 6')).toBeVisible()
  await page.getByLabel('Take-home a month').first().fill('120000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 3. Spending
  await expect(page.getByText('Question 3 of 6')).toBeVisible()
  await page.getByLabel('Needs a month').fill('40000')
  await page.getByLabel('Everything else a month').fill('30000')
  await page.getByLabel('Into savings or SIPs a month').fill('24000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 4. Loans: one personal loan, rate as the user knows it
  await expect(page.getByText('Question 4 of 6')).toBeVisible()
  for (const remove of await page.getByRole('button', { name: /^Remove the/ }).all()) await remove.click()
  await page.getByRole('button', { name: 'Add a loan or card balance' }).click()
  await page.getByLabel('Still owed').fill('150000')
  await page.getByLabel('Paid a month').fill('8000')
  await page.getByLabel('Interest').fill('14% a year')
  await page.getByRole('button', { name: 'Next' }).click()

  // 5. Insurance: skipped, so Protection is "Not enough data", never red
  await expect(page.getByText('Question 5 of 6')).toBeVisible()
  await page.getByRole('button', { name: 'Skip for now' }).click()

  // 6. Savings
  await expect(page.getByText('Question 6 of 6')).toBeVisible()
  await page.getByLabel('Savings and sweep FD').fill('250000')
  await page.getByRole('button', { name: 'See my result' }).click()

  await expect(page.getByRole('heading', { name: 'Your result' })).toBeVisible()
  await expect(page.getByRole('img', { name: /Money Health score \d+ out of 100/ })).toBeVisible()
  await expect(page.getByTestId('action').first()).toBeVisible()

  await page.getByRole('button', { name: 'Open Money Health' }).click()
  const protection = page.getByTestId('check-protection')
  await expect(protection.getByText('Not enough data')).toBeVisible()
  await expect(protection.getByText('Fix first')).toHaveCount(0)

  // The answers were saved: the loan is on the Loans screen with its real rate.
  await page.getByRole('navigation', { name: 'Portfolio sections' }).getByRole('button', { name: 'Loans' }).click()
  await expect(page.getByTestId('loan-card').filter({ hasText: 'Personal loan' })).toContainText('14% a year')
  expect(problems).toEqual([])
})

test('portfolio: the sample household saves nothing', async ({ page, request, problems }) => {
  const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
  const kai = agents.find((a: { key: string }) => a.key === 'finance')
  await page.goto(`/agents/${kai.id}?tab=portfolio&sample=1`)
  await expect(page.getByText(/Sample household/)).toBeVisible()
  await expect(page.getByText('Score 38')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Done' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Leave sample' }).click()
  await expect(page.getByText(/Sample household/)).toHaveCount(0)
  expect(problems).toEqual([])
})
