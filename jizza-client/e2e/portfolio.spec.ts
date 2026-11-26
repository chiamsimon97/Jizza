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

/**
 * Phase 1c: a made-up bank CSV through Connect: read, reviewed, saved, refused the second time,
 * and undone. Each run's file differs, so the test runs the same on a database that has seen it.
 */
test('portfolio: a bank statement is imported, reviewed, saved and undone', async ({ page, request, problems }) => {
  const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
  const kai = agents.find((a: { key: string }) => a.key === 'finance')
  const run = Date.now()
  const csv = [
    'Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance',
    `2 Apr 2025,2 Apr 2025,UPI/DR/${run}/HOUSE RENT,,15000.00,,20000.00`,
    `4 Apr 2025,4 Apr 2025,NEFT SALARY APRIL ${run},,,50000.00,70000.00`,
    `6 Apr 2025,6 Apr 2025,UPI-CORNER BAKERY-${run},,250.00,,69750.00`,
  ].join('\n')
  const file = { name: `statement-${run}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) }

  await page.goto(`/agents/${kai.id}?tab=portfolio&view=connect`)
  await expect(page.getByRole('heading', { name: 'Connect and imports' })).toBeVisible()
  await page.getByRole('button', { name: 'Import a file' }).first().click()
  await page.getByLabel('Account', { exact: true }).selectOption({ label: 'Add an account…' })
  await page.getByLabel('Bank', { exact: true }).fill('Example Bank')
  await page.getByLabel('File', { exact: true }).setInputFiles(file)
  await page.getByRole('button', { name: 'Read the file' }).click()

  await expect(page.getByText('3 new')).toBeVisible()
  await expect(page.getByText('1 need a look')).toBeVisible()
  await page.getByLabel(/Category for UPI-CORNER BAKERY/).selectOption({ label: 'Lifestyle and other' })
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('3 rows')).toBeVisible()

  // The same file again is refused.
  await page.getByRole('button', { name: 'Close' }).click()
  await page.getByRole('button', { name: 'Import a file' }).first().click()
  await page.getByLabel('File', { exact: true }).setInputFiles(file)
  await page.getByRole('button', { name: 'Read the file' }).click()
  await expect(page.getByRole('alert')).toContainText('You imported this file on')
  await page.getByRole('button', { name: 'Cancel' }).click()

  const history = page.getByRole('table', { name: 'Import history' })
  const row = history.getByRole('row').filter({ hasText: `statement-${run}.csv` })
  await row.getByRole('button', { name: 'Undo' }).click()
  await expect(row.getByText('Undone')).toBeVisible()
  expect(problems).toEqual([])
})

/**
 * Phase 2: one holding of 100 units bought in two lots, split between two goals in the earmark
 * editor. A split that doesn't add up keeps Save off; moving units into a goal under three years
 * away asks first and saves only on "Keep the shares". Every run uses its own account and
 * instrument, so earlier runs never change what this one sees.
 */
test('portfolio: split a holding between goals in the earmark editor', async ({ page, request, problems }) => {
  const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
  const kai = agents.find((a: { key: string }) => a.key === 'finance')
  const run = Date.now().toString(36).toUpperCase()
  const post = async (path: string, body: unknown) => {
    const r = await apiCall(request, 'POST', path, body)
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy()
    return r.json()
  }
  const account = await post('/api/finance/accounts', { kind: 'demat', provider: 'Example Broker', nickname: `Demat ${run}` })
  const isin = `INX${run.padStart(8, '0').slice(-8)}1`
  const share = await post('/api/finance/instruments', { name: `Example Share ${run}`, isin, type: 'stock', assetClass: 'equity' })
  const house = (await post('/api/finance/goals', { type: 'home', name: `House ${run}`, targetAmountToday: 2000000, targetDate: '2040-01-01' })).goal
  const soon = new Date()
  soon.setFullYear(soon.getFullYear() + 1)
  const trip = (await post('/api/finance/goals', { type: 'travel', name: `Trip ${run}`, targetAmountToday: 150000, targetDate: soon.toISOString().slice(0, 10) })).goal
  await post('/api/finance/holdings/purchases', { accountId: account.id, instrumentId: share.id, date: '2023-03-15', units: 60, pricePerUnit: 1600 })
  await post('/api/finance/holdings/purchases', { accountId: account.id, instrumentId: share.id, date: '2024-08-15', units: 40, pricePerUnit: 1550, goalId: house.id })

  await page.goto(`/agents/${kai.id}?tab=portfolio&view=holdings`)
  await expect(page.getByRole('heading', { name: 'Holdings' })).toBeVisible()
  await page.getByRole('button', { name: new RegExp(`Example Share ${run}`) }).click()

  const total = page.getByTestId('earmark-total')
  await expect(total).toHaveText('Total 100 / 100 units')
  await expect(page.getByText('A sale here uses the March 2023 lot first.')).toBeVisible()

  // 60 unassigned, 40 for the house: move 60 to the house, then the sum is wrong until Unassigned is emptied.
  await page.getByLabel(`Units for House ${run}`).fill('100')
  await expect(total).toHaveText('Total 160 / 100 units')
  await expect(page.getByRole('button', { name: 'Save split' })).toBeDisabled()
  await page.getByLabel('Units for Unassigned').fill('0')
  await expect(total).toHaveText('Total 100 / 100 units')
  await page.getByRole('button', { name: 'Save split' }).click()
  await expect(page.getByRole('button', { name: 'Save split' })).toBeDisabled()

  // 10 to the trip: shares in a goal under 3 years away need the user's choice.
  await page.getByLabel(`Units for House ${run}`).fill('90')
  await page.getByLabel(`Units for Trip ${run}`).fill('10')
  await page.getByRole('button', { name: 'Save split' }).click()
  const dialog = page.getByRole('dialog', { name: 'Shares in a short goal' })
  await expect(dialog).toContainText('under 3 years away')
  await dialog.getByRole('button', { name: 'Keep the shares' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('Kept by your choice')).toBeVisible()
  await expect(total).toHaveText('Total 100 / 100 units')

  const saved = await (await apiCall(request, 'GET', `/api/finance/holdings/${account.id}/${share.id}`)).json()
  const units = Object.fromEntries(saved.earmarks.map((e: { goalId: string; units: number }) => [e.goalId, e.units]))
  expect(units).toEqual({ [house.id]: 90, [trip.id]: 10 })
  expect(problems).toEqual([])
})
