// Browser end-to-end tests for Study Buddy Matcher. See README.md for why this folder exists and how to run it.
const path = require('path');
const { defineConfig, devices } = require('@playwright/test');

const PORT = 8089; // never 8080, so a developer's running app is left alone
const appDir = path.join(__dirname, '..', 'studybuddy');
const mvnw = `"${path.join(appDir, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')}"`;

module.exports = defineConfig({
  testDir: './tests',
  outputDir: './test-results/artifacts',
  fullyParallel: true,
  retries: 0, // a flaky test should be seen, not hidden
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    // Read by docs/test-cases/build_workbook.py for the workbook's e2e rows
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    baseURL: `http://localhost:${PORT}`,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  // A fresh app (and so a fresh in-memory database) for every run, on its own port
  webServer: {
    command: `${mvnw} -q spring-boot:run "-Dspring-boot.run.arguments=--server.port=${PORT} --spring.jpa.show-sql=false"`,
    cwd: appDir,
    url: `http://localhost:${PORT}/login`,
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
      testIgnore: ['**/settings.spec.js', '**/layout.spec.js'],
    },
    {
      name: 'phone',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 375, height: 667 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
      },
      testMatch: ['**/layout.spec.js', '**/a11y.spec.js', '**/confirmations.spec.js', '**/auth.spec.js'],
    },
    {
      // The matching settings are shared by every user, so this spec runs on its own after the others
      name: 'settings',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
      testMatch: ['**/settings.spec.js'],
      dependencies: ['desktop', 'phone'],
    },
  ],
});
