// Screenshot harness (UI-FOUND-WEB): `make screenshots-web`. A design feedback loop, not a CI gate; never part
// of `make check`. Runs the SPA in mock-server mode against fixtures/demo and writes PNGs to screenshots/.
import { defineConfig } from '@playwright/test';

const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const PORT = Number(env.SCREENSHOT_PORT ?? 3917);
// A system Chromium (e.g. /usr/bin/chromium) instead of Playwright's downloaded browser, when set.
const executablePath = env.CHROMIUM_PATH || undefined;

export default defineConfig({
	testDir: './playwright',
	testMatch: '*.spec.ts',
	outputDir: './screenshots/.results',
	fullyParallel: true,
	workers: Number(env.SCREENSHOT_WORKERS ?? 4),
	retries: 0,
	timeout: Number(env.SCREENSHOT_TIMEOUT ?? 120_000),
	reporter: [['list']],
	use: {
		baseURL: `http://localhost:${PORT}`,
		browserName: 'chromium',
		launchOptions: executablePath ? { executablePath } : {},
		locale: 'en-GB',
		timezoneId: 'Asia/Tokyo'
	},
	webServer: {
		// A production build behind `vite preview`: no dev-server dependency re-optimisation reloading pages mid-capture.
		command: `vite build && vite preview --port ${PORT} --strictPort`,
		env: { KOTOBAYA_MOCK: 'default' },
		url: `http://localhost:${PORT}/api/v1/meta`,
		reuseExistingServer: false,
		timeout: 180_000
	}
});
