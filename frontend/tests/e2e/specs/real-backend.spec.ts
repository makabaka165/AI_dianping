import { test, expect } from '@playwright/test'

const realBackendEnabled = process.env.PLAYWRIGHT_REAL_BACKEND === 'true'
const fixturePhone = process.env.PLAYWRIGHT_E2E_PHONE || '13686869696'

test.describe('real backend agent flow', () => {
  test.skip(!realBackendEnabled, 'Set PLAYWRIGHT_REAL_BACKEND=true to run the local integration flow.')

  test('logs in, lists an agent, creates a run, and receives SSE events', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', 'The real backend flow runs once on desktop.')

    await page.goto('/login')
    await page.getByLabel('手机号', { exact: true }).fill(fixturePhone)
    await page.getByRole('button', { name: '发送验证码', exact: true }).click()

    const debugCodeText = await page.getByTestId('debug-verification-code').textContent()
    const debugCode = debugCodeText?.match(/\d{4,8}/)?.[0]
    expect(debugCode).toBeTruthy()

    await page.getByLabel('验证码', { exact: false }).fill(debugCode!)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('heading', { name: '店铺发现', exact: true })).toBeVisible()

    await page.goto('/studio/agents')
    await expect(page.getByRole('heading', { name: '可运行 Agent', exact: true })).toBeVisible()
    await expect(page.getByText('shop-consultant', { exact: true })).toBeVisible()

    await page.getByRole('link', { name: '创建 Run', exact: true }).click()
    await page.getByLabel('输入', { exact: true }).fill('请总结一下这家店')
    await page.getByRole('button', { name: '创建并观察', exact: true }).click()

    await expect(page).toHaveURL(/\/studio\/runs\/[a-f0-9]+$/)
    await expect(page.getByText('WAITING_FOR_USER', { exact: true })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(/feedback\.required/)).toBeVisible()

    const runId = page.url().split('/').pop()
    expect(runId).toBeTruthy()
    await page.goto('/studio/runs')
    await expect(page.getByRole('link', { name: runId!, exact: true })).toBeVisible()
  })
})
