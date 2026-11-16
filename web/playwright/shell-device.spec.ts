// SHELL-W2 layout guard: the Pair screen, Home with the sync chip, device settings and the needs-connection state
// at phone widths, in the mock shell (`/__mock/shell/on`) and in the plain browser. No overlap, no horizontal overflow.
// Output: screenshots/shell/<case>.png (gitignored). Run: `make screenshots-web` or `playwright test shell-device`.
import { expect, test, type Page } from '@playwright/test';

const SIZES = [
	{ name: '360x800', width: 360, height: 800 },
	{ name: '412x915', width: 412, height: 915 }
] as const;


/** Runs when the fake shell publishes `__kbShell` (end of its inline script, before any SPA script). */
async function seedShell(page: Page, opts: { paired: boolean; offline?: boolean; stale?: boolean }): Promise<void> {
	await page.addInitScript((o) => {
		let value: unknown;
		Object.defineProperty(window, '__kbShell', {
			configurable: true,
			get: () => value,
			set(v: { state: Record<string, any> }) {
				value = v;
				const st = v.state;
				st.paired = o.paired;
				st.online = !o.offline;
				const lastSync = new Date(Date.now() - (o.stale ? 3 * 86_400_000 : 7 * 60_000)).toISOString();
				const sync = st.sync;
				st.sync = () => ({ ...sync(), last_sync_at: lastSync, pending_ops: 12, stale: !!o.stale });
				st.dict = {
					installed: { release_id: 3, scope: 'top60k', bytes: 41_943_040 },
					scope: 'top60k',
					state: 'idle',
					done_bytes: 0,
					total_bytes: 0,
					last_result: 'up_to_date',
					last_error: null,
					server_release_id: 4,
					gap_fill: { cached: 212, missing: 3 }
				};
				st.storage = {
					card_audio_bytes: 52_428_800,
					card_audio_count: 410,
					media_bytes: 130_023_424,
					cover_bytes: 8_388_608,
					dictionary_bytes: 41_943_040,
					total_bytes: 232_783_872,
					prefetch_days: 7,
					budget_mb: 500,
					allow_metered: false
				};
			}
		});
	}, opts);
}

/** No horizontal page overflow; no visible element sticks out of the viewport; returns the offenders. */
async function assertFits(page: Page): Promise<void> {
	await page.evaluate(() => document.fonts.ready.then(() => undefined));
	const bad = await page.evaluate(() => {
		const vw = document.documentElement.clientWidth;
		const out: string[] = [];
		if (document.documentElement.scrollWidth > vw + 1) out.push(`page scrollWidth ${document.documentElement.scrollWidth} > ${vw}`);
		for (const el of document.querySelectorAll('main *, header *, nav *')) {
			const r = el.getBoundingClientRect();
			const s = getComputedStyle(el);
			if (r.width === 0 || r.height === 0 || s.visibility === 'hidden' || s.display === 'none') continue;
			if (el.closest('[aria-hidden="true"], .kb-sr, dialog:not([open])')) continue;
			if (r.right > vw + 1 || r.left < -1) out.push(`${el.tagName}.${(el as HTMLElement).className} [${Math.round(r.left)}..${Math.round(r.right)}] of ${vw}`);
		}
		return out.slice(0, 8);
	});
	expect(bad, 'elements outside the viewport').toEqual([]);
}

/** The sync chip must not intersect any other control or the brand in the top bar. */
async function assertChipClear(page: Page): Promise<void> {
	const chip = page.locator('header.top button.sync-chip');
	await chip.waitFor();
	const boxes = await page.evaluate(() => {
		const top = document.querySelector('header.top');
		const chip = top?.querySelector('button.sync-chip');
		if (!top || !chip) return null;
		const c = chip.getBoundingClientRect();
		const others = [...top.querySelectorAll('a, button')].filter((e) => !chip.contains(e) && e !== chip).map((e) => e.getBoundingClientRect());
		return { c: c.toJSON(), others: others.map((o) => o.toJSON()) };
	});
	expect(boxes).not.toBeNull();
	if (!boxes) return;
	for (const o of boxes.others) {
		const overlap = Math.min(boxes.c.right, o.right) - Math.max(boxes.c.left, o.left);
		expect(overlap, 'chip overlaps a neighbour in the top bar').toBeLessThanOrEqual(0.5);
	}
	// The chip's text never wraps or spills past its own box.
	const spill = await chip.evaluate((el) => {
		const text = el.querySelector('.text');
		return text ? text.scrollWidth - (text as HTMLElement).clientWidth : 0;
	});
	expect(spill, 'chip text clipped').toBeLessThanOrEqual(40); // ellipsis is acceptable; hard overflow is not
}

for (const s of SIZES) {
	test.describe(`shell mock ${s.name}`, () => {
		test.use({ viewport: { width: s.width, height: s.height } });

		test.beforeEach(async ({ page }) => {
			await page.emulateMedia({ reducedMotion: 'reduce' });
			await page.goto('/__mock/shell/on');
		});

		test('pair screen', async ({ page }) => {
			await seedShell(page, { paired: false });
			await page.goto('/login?pair=' + encodeURIComponent('kotobaya://pair?server=https%3A%2F%2Fk.example%2Fapi%2Fv1&code=K7Q2M9XD'));
			await page.getByRole('button', { name: 'Pair', exact: true }).waitFor();
			await expect(page.getByLabel('Or paste a pairing link')).toHaveValue(/^kotobaya:\/\/pair\?/);
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/pair-${s.name}.png`, fullPage: true });
		});

		test('home with the sync chip', async ({ page }) => {
			await seedShell(page, { paired: true });
			await page.goto('/');
			await assertChipClear(page);
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/home-chip-${s.name}.png` });
		});

		test('home with a stale sync chip', async ({ page }) => {
			await seedShell(page, { paired: true, stale: true });
			await page.goto('/');
			await assertChipClear(page);
			await expect(page.locator('header.top button.sync-chip')).toContainText('Not synced for 3 d');
			await page.screenshot({ path: `screenshots/shell/home-chip-stale-${s.name}.png` });
		});

		test('device settings', async ({ page }) => {
			await seedShell(page, { paired: true });
			await page.goto('/settings');
			await page.getByRole('heading', { name: 'This device', level: 2 }).waitFor();
			await page.getByRole('heading', { name: 'Offline storage' }).waitFor();
			await page.getByRole('heading', { name: 'Offline dictionary' }).waitFor();
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/device-settings-${s.name}.png`, fullPage: true });
			await page.locator('#this-device').screenshot({ path: `screenshots/shell/device-section-${s.name}.png` });
		});

		test('needs connection when offline', async ({ page }) => {
			await seedShell(page, { paired: true, offline: true });
			await page.goto('/stats');
			await page.getByRole('heading', { name: 'Needs a connection' }).waitFor();
			await assertChipClear(page);
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/needs-connection-${s.name}.png` });
		});
	});

	test.describe(`plain browser ${s.name}`, () => {
		test.use({ viewport: { width: s.width, height: s.height } });

		test.beforeEach(async ({ page }) => {
			await page.emulateMedia({ reducedMotion: 'reduce' });
			await page.goto('/__mock/shell/off');
		});

		test('home has no sync chip and settings no device section', async ({ page }) => {
			await page.goto('/');
			await page.locator('header.top').waitFor();
			await expect(page.locator('button.sync-chip')).toHaveCount(0);
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/web-home-${s.name}.png` });
			await page.goto('/settings');
			await page.getByRole('heading', { name: 'Appearance' }).waitFor();
			await expect(page.getByRole('heading', { name: 'This device', level: 2 })).toHaveCount(0);
			await assertFits(page);
			await page.screenshot({ path: `screenshots/shell/web-settings-${s.name}.png`, fullPage: true });
		});
	});
}

