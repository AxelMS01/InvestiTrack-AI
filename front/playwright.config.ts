import { defineConfig } from '@playwright/test';
import { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'node:url';

loadEnvFile(fileURLToPath(new URL('../.env', import.meta.url)));

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 180_000,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:8080',
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
  },
});
