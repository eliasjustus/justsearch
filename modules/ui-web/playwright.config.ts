import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright Configuration for JustSearch UI
 * 
 * Optimized for AI agent testing workflow:
 * - JSON reporter for structured output
 * - Automatic dev server startup
 * - Multiple viewport sizes for responsive testing
 * - Trace capture on failure for debugging
 * 
 * CPU Optimization Notes:
 * - workers: 2 limits parallel browser instances (reduce if CPU still spikes)
 * - video: 'off' saves significant CPU (enable with PWVIDEO=1 env var)
 * - Use --project=Desktop for quick single-viewport testing
 */
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

export default defineConfig({
  // Test directory
  testDir: './e2e',
  
  // Run tests in parallel (within worker limit)
  fullyParallel: true,
  
  // Fail the build on CI if you accidentally left test.only in the source code
  forbidOnly: !!process.env.CI,
  
  // Retry on CI only
  retries: process.env.CI ? 2 : 0,
  
  // Limit parallel workers to balance speed vs CPU
  // CI: 1 worker, Local: 3 workers (adjust up/down as needed)
  workers: process.env.CI ? 1 : 3,
  
  // Reporter configuration - JSON for AI parsing
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  
  // Shared settings for all projects
  use: {
    // Base URL for the dev server
    baseURL,
    
    // Capture trace on first retry (helps debug failures)
    trace: 'on-first-retry',
    
    // Screenshot on failure only
    screenshot: 'only-on-failure',
    
    // Video DISABLED by default (high CPU cost)
    // Enable with: PWVIDEO=1 npx playwright test
    video: process.env.PWVIDEO ? 'retain-on-failure' : 'off',
    
    // Browser
    browserName: 'chromium',
  },

  // Configure projects for different viewports
  projects: [
    {
      name: 'Desktop',
      use: { 
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 800 },
      },
    },
    {
      name: 'Tablet',
      use: { 
        ...devices['iPad Pro'],
        viewport: { width: 1024, height: 768 },
      },
    },
    {
      name: 'Mobile',
      use: { 
        ...devices['iPhone 14'],
        viewport: { width: 390, height: 844 },
      },
    },
    // Compact mode testing (under 600px triggers compact mode)
    {
      name: 'Compact',
      use: {
        viewport: { width: 580, height: 800 },
      },
    },
  ],

  // Run the dev server before starting tests
  webServer: {
    command: 'npm run dev',
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
    // Pass environment variables to the dev server
    env: {
      ...process.env,
      VITE_JUSTSEARCH_API_PORT: process.env.VITE_JUSTSEARCH_API_PORT || '',
    },
  },
  
  // Test timeout
  timeout: 30000,
  
  // Expect timeout for assertions
  expect: {
    timeout: 5000,
  },
  
  // Output directory for test artifacts
  outputDir: 'test-results',
});

