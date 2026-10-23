import { test as setup, expect } from '@playwright/test'
import { apiCall, STORAGE_STATE, TEST_ACCOUNT } from './fixtures'

/**
 * Creates the dedicated test account (if this database does not have it yet), seeds the data the
 * flows expect, and signs in once through the real sign-in page so every test starts signed in.
 */
setup('test account and seed data', async ({ page, request }) => {
  await request.get('/api/auth/csrf')
  const registered = await apiCall(request, 'POST', '/api/auth/register', {
    email: TEST_ACCOUNT.email, password: TEST_ACCOUNT.password,
  })
  expect([200, 409]).toContain(registered.status())

  const login = await apiCall(request, 'POST', '/api/auth/login', TEST_ACCOUNT)
  expect(login.ok(), await login.text()).toBeTruthy()

  // Seed: one of each thing a first-week user would have, so pages show real rows, not only
  // their empty states. Idempotent: skipped when already there.
  const tasks = await (await apiCall(request, 'GET', '/api/tasks')).json()
  if (!tasks.some((t: { title: string }) => t.title === 'Seed: weekly review')) {
    const agents = await (await apiCall(request, 'GET', '/api/agents')).json()
    const cato = agents.find((a: { key: string }) => a.key === 'professional') ?? agents[0]
    const task = await apiCall(request, 'POST', '/api/tasks', {
      title: 'Seed: weekly review', agentId: cato.id, scheduleType: 'ONE_TIME',
      runAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    })
    expect(task.ok(), await task.text()).toBeTruthy()

    const memory = await apiCall(request, 'POST', '/api/memory', {
      category: 'PREFERENCE', content: 'Seed: prefers meetings after 10am',
    })
    expect(memory.ok(), await memory.text()).toBeTruthy()

    const workflow = await apiCall(request, 'POST', '/api/workflows', {
      name: 'Seed: morning digest',
      graph: { nodes: [{ id: 'start', type: 'TRIGGER' }, { id: 'end', type: 'END' }], edges: [{ from: 'start', to: 'end' }] },
    })
    expect(workflow.ok(), await workflow.text()).toBeTruthy()

    const chat = await apiCall(request, 'POST', '/api/chat', { message: 'Seed: hello Jizza' })
    expect(chat.ok(), await chat.text()).toBeTruthy()
  }

  await page.goto('/login')
  await page.getByLabel('Email').fill(TEST_ACCOUNT.email)
  await page.getByLabel('Password').fill(TEST_ACCOUNT.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.context().storageState({ path: STORAGE_STATE })
})
