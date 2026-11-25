import { test, expect, apiCall } from './fixtures'

/**
 * Kai Portfolio, Phases 1a and 1b: all eight Quick start steps in the browser on the test account,
 * saved as each is answered, one question skipped, ending on the result and the Money Health home;
 * then the goals it made, the plan builder and the Tax screen. Answering again replaces the earlier
 * answers, so the test runs the same on a database that has seen it before.
 */
test('portfolio: Quick start from the first question to the result', async ({ page, request, problems }) => {
  const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
  const kai = agents.find((a: { key: string }) => a.key === 'finance')
  expect(kai, 'Kai is a built-in agent').toBeTruthy()

  await page.goto(`/agents/${kai.id}?tab=portfolio&view=quick-start&step=people`)

  // 1. Whose money
  await expect(page.getByText('Question 1 of 8')).toBeVisible()
  await page.getByLabel('Your date of birth').fill('1992-06-01')
  await page.getByRole('button', { name: 'Next' }).click()

  // 2. Income
  await expect(page.getByText('Question 2 of 8')).toBeVisible()
  await page.getByLabel('Take-home a month').first().fill('120000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 3. Spending
  await expect(page.getByText('Question 3 of 8')).toBeVisible()
  await page.getByLabel('Needs a month').fill('40000')
  await page.getByLabel('Everything else a month').fill('30000')
  await page.getByLabel('Into savings or SIPs a month').fill('24000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 4. Loans: one personal loan, rate as the user knows it
  await expect(page.getByText('Question 4 of 8')).toBeVisible()
  for (const remove of await page.getByRole('button', { name: /^Remove the/ }).all()) await remove.click()
  await page.getByRole('button', { name: 'Add a loan or card balance' }).click()
  await page.getByLabel('Still owed').fill('150000')
  await page.getByLabel('Paid a month').fill('8000')
  await page.getByLabel('Interest').fill('14% a year')
  await page.getByRole('button', { name: 'Next' }).click()

  // 5. Insurance: skipped, so Protection is "Not enough data", never red
  await expect(page.getByText('Question 5 of 8')).toBeVisible()
  await page.getByRole('button', { name: 'Skip for now' }).click()

  // 6. Savings
  await expect(page.getByText('Question 6 of 8')).toBeVisible()
  await page.getByLabel('Savings and sweep FD').fill('250000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 7. Goals: retirement as Kai fills it in, travel with an amount
  await expect(page.getByText('Question 7 of 8')).toBeVisible()
  const picked = page.getByRole('group', { name: 'Goals' }).locator('button[aria-pressed="true"]')
  while (await picked.count()) await picked.first().click()
  await page.getByRole('button', { name: 'Retirement', exact: true }).click()
  await page.getByRole('button', { name: 'Travel', exact: true }).click()
  await page.getByLabel('What it costs in today’s rupees').nth(1).fill('150000')
  await page.getByRole('button', { name: 'Next' }).click()

  // 8. Risk: six questions, the middle answer to each
  await expect(page.getByText('Question 8 of 8')).toBeVisible()
  for (let i = 0; i < 6; i++) await page.locator('fieldset').nth(i).getByRole('button').nth(1).click()
  await page.getByRole('button', { name: 'See my plan' }).click()

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

  // Quick start's goals are on Goals with a funded % each.
  const nav = page.getByRole('navigation', { name: 'Portfolio sections' })
  await nav.getByRole('button', { name: 'Goals' }).click()
  await expect(page.getByRole('heading', { name: /Retirement/ })).toBeVisible()
  await expect(page.getByText(/% funded/).first()).toBeVisible()

  // The plan builder splits ₹50,000 and saves; the costly loan comes off the top first.
  await nav.getByRole('button', { name: 'Plan builder' }).click()
  await page.getByLabel('Each month').fill('50000')
  await expect(page.getByText(/Off the top first/)).toBeVisible()
  await expect(page.getByText('Long (7 years and more)')).toBeVisible()
  await page.getByRole('button', { name: 'Save plan' }).click()
  await expect(page.getByText(/^Saved /)).toBeVisible()

  // Tax says its figures are unverified.
  await nav.getByRole('button', { name: 'Tax' }).click()
  await expect(page.getByText('These tax figures have not been verified.')).toBeVisible()
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
