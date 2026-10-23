import { defineConfig, devices } from '@playwright/test'

/**
 * Browser end-to-end suite: the real backend, the real client, and a stub in place of the model
 * provider. Needs a PostgreSQL with pgvector (JIZZA_E2E_DB_URL) and the backend built once
 * (`mvn -DskipTests install` at the repository root). Run with `npm run test:e2e`.
 *
 * Everything runs on its own ports so it never meets a development server or real account data.
 */
const API_PORT = Number(process.env.JIZZA_E2E_API_PORT ?? 8091)
const WEB_PORT = Number(process.env.JIZZA_E2E_WEB_PORT ?? 5199)
const MODEL_PORT = Number(process.env.STUB_MODEL_PORT ?? 4010)
const DB_URL = process.env.JIZZA_E2E_DB_URL ?? 'jdbc:postgresql://localhost:5432/jizza_e2e'

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'e2e-report', open: 'never' }]],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } : {},
  },
  projects: [
    { name: 'setup', testMatch: /global\.setup\.ts/ },
    {
      name: 'desktop', dependencies: ['setup'], testIgnore: /responsive\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], storageState: 'e2e/.auth/tester.json' },
    },
    {
      name: 'phone', dependencies: ['setup'], testMatch: /responsive\.spec\.ts/,
      use: { ...devices['Pixel 7'], storageState: 'e2e/.auth/tester.json' },
    },
  ],
  webServer: [
    {
      command: 'node e2e/stub-model.mjs',
      url: `http://localhost:${MODEL_PORT}/health`,
      env: { STUB_MODEL_PORT: String(MODEL_PORT) },
      reuseExistingServer: false,
    },
    {
      command: 'java -jar ../jizza-app/target/jizza-app-0.1.0-SNAPSHOT.jar',
      url: `http://localhost:${API_PORT}/api/auth/csrf`,
      timeout: 180_000,
      reuseExistingServer: false,
      env: {
        SERVER_PORT: String(API_PORT),
        SPRING_DATASOURCE_URL: DB_URL,
        SPRING_DATASOURCE_USERNAME: process.env.JIZZA_E2E_DB_USER ?? 'jizza',
        SPRING_DATASOURCE_PASSWORD: process.env.JIZZA_E2E_DB_PASSWORD ?? 'jizza',
        ANTHROPIC_BASE_URL: `http://localhost:${MODEL_PORT}`,
        ANTHROPIC_API_KEY: 'stub-key-not-a-real-one',
        JIZZA_LLM_MAX_ATTEMPTS: '1',
        JIZZA_CORS_ORIGINS: `http://localhost:${WEB_PORT}`,
        JIZZA_CLIENT_BASE_URL: `http://localhost:${WEB_PORT}`,
        JIZZA_MASTER_KEY: 'F1jkQFbz3rMzsz6qKZDe1oI08Kj0jZ7t1v8sMoyGZ8Y=',
        JIZZA_MASTER_KEY_FILE: '',
        JIZZA_COOKIE_SECURE: 'false',
        JIZZA_ALLOW_ADDITIONAL_USERS: 'true',
        JIZZA_MFA_REQUIRED: 'false',
        JIZZA_SECURITY_LOCKOUT_AUTHREQUESTSPERMINUTE: '10000',
        JIZZA_PLATFORM_MAIL_ENABLED: 'false',
        MANAGEMENT_HEALTH_MAIL_ENABLED: 'false',
        JIZZA_STORAGE_ROOT: './target/e2e-storage',
      },
    },
    {
      command: `npx vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      reuseExistingServer: false,
      env: { JIZZA_API_URL: `http://localhost:${API_PORT}` },
    },
  ],
})
