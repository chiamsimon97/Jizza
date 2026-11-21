import { test, expect } from './fixtures'
import { PAGES } from './routes'

// At phone width every page fits the screen (no sideways scrolling) and the menu opens.
for (const p of PAGES) {
  test(`${p.name} fits a phone screen`, async ({ page, problems }) => {
    await page.goto(p.path)
    await expect(page.locator('main')).toContainText(p.expect)
    await page.waitForLoadState('networkidle')
    const overflow = await page.evaluate(() => {
      const width = document.documentElement.clientWidth
      const wide = [...document.querySelectorAll<HTMLElement>('main *')]
        .filter(el => {
          const r = el.getBoundingClientRect()
          return r.width > 0 && r.right > width + 1 && getComputedStyle(el).position !== 'fixed'
            && !el.closest('[data-allow-overflow], .overflow-x-auto, .overflow-auto, .overflow-x-scroll')
        })
        .slice(0, 3)
        .map(el => `${el.tagName.toLowerCase()}.${String(el.className).split(' ').slice(0, 3).join('.')} right=${Math.round(el.getBoundingClientRect().right)}`)
      return { scroll: document.documentElement.scrollWidth - width, wide, width }
    })
    expect(overflow.scroll, `page scrolls sideways at ${overflow.width}px`).toBeLessThanOrEqual(1)
    expect(overflow.wide, 'elements spill off the right edge').toEqual([])
    expect(problems).toEqual([])
  })
}

test('the menu opens and navigates on a phone', async ({ page }) => {
  await page.goto('/chat')
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation').getByRole('link', { name: /Tasks/ }).first().click()
  await expect(page).toHaveURL(/\/tasks/)
  await expect(page.getByRole('button', { name: 'Open menu' })).toBeVisible()
})
