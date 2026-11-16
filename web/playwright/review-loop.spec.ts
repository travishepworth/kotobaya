// LOOP-WEB / PORTRAIT-WEB layout guard (ADR-037): Home in every loop phase (with the backlog line and the
// later-today hint), the review end screens (goal reached, done for today, waiting, all done) and the review header,
// at the two phone sizes and on desktop; the This device "Lock to portrait" row in the mock shell. `loop` is served
// by intercepting GET /review/queue (the demo API predates ADR-037). No horizontal overflow, nothing under the bottom
// bar or the answer bar. Output: screenshots/shell/loop-<case>-<size>.png. Run: `playwright test review-loop`.
import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';

/* eslint-disable @typescript-eslint/no-explicit-any */
const NOW = new Date('2026-11-17T09:00:00Z');
const SIZES = [
	{ name: '360x800', width: 360, height: 800, mobile: true },
	{ name: '412x915', width: 412, height: 915, mobile: true },
	{ name: 'desktop', width: 1280, height: 800, mobile: false }
] as const;

const at = (min: number): string => new Date(NOW.getTime() + min * 60_000).toISOString();

const LOOPS: Record<string, { loop: Record<string, any>; items: boolean }> = {
	reviewing: {
		items: true,
		loop: {
			phase: 'reviewing',
			keep_going: false,
			ready: { learning: 1, review: 200, total: 201 },
			later_today: { count: 8, next_due_at: at(6) },
			backlog: { reviews: 335, beyond_limit: 135 },
			reviews: { today: 0, limit: 200 },
			new: { today: 12, goal: 40, available: true }
		}
	},
	goal_reached: {
		items: false,
		loop: {
			phase: 'goal_reached',
			keep_going: false,
			ready: { learning: 0, review: 0, total: 0 },
			later_today: { count: 3, next_due_at: at(14) },
			backlog: { reviews: 0, beyond_limit: 0 },
			reviews: { today: 212, limit: null },
			new: { today: 40, goal: 40, available: true }
		}
	},
	done_for_today: {
		items: false,
		loop: {
			phase: 'done_for_today',
			keep_going: false,
			ready: { learning: 0, review: 0, total: 0 },
			later_today: { count: 0, next_due_at: null },
			backlog: { reviews: 135, beyond_limit: 135 },
			reviews: { today: 200, limit: 200 },
			new: { today: 40, goal: 40, available: true }
		}
	},
	waiting: {
		items: false,
		loop: {
			phase: 'waiting',
			keep_going: true,
			ready: { learning: 0, review: 0, total: 0 },
			later_today: { count: 8, next_due_at: at(4.5) },
			backlog: { reviews: 0, beyond_limit: 0 },
			reviews: { today: 200, limit: 200 },
			new: { today: 52, goal: 40, available: false }
		}
	},
	empty: {
		items: false,
		loop: {
			phase: 'empty',
			keep_going: false,
			ready: { learning: 0, review: 0, total: 0 },
			later_today: { count: 0, next_due_at: null },
			backlog: { reviews: 0, beyond_limit: 0 },
			reviews: { today: 180, limit: 200 },
			new: { today: 40, goal: 40, available: false }
		}
	}
};

async function serveLoop(page: Page, phase: string): Promise<void> {
	const { loop, items } = LOOPS[phase];
	await page.route('**/api/v1/review/queue*', async (route: Route) => {
		const real = await route.fetch();
		const body = (await real.json()) as Record<string, any>;
		await route.fulfill({ json: { ...body, study_day: '2026-11-17', loop, items: items ? body.items : [] } });
	});
}

async function open(page: Page, context: BrowserContext, path: string): Promise<void> {
	await page.clock.setFixedTime(NOW);
	await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.emulateMedia({ reducedMotion: 'reduce' });
	await page.goto('/__mock/shell/off');
	await page.goto(path);
}

/** Nothing wider than the viewport, no sideways page scroll. */
async function assertNoOverflow(page: Page): Promise<void> {
	await page.evaluate(() => document.fonts.ready.then(() => undefined));
	const bad = await page.evaluate(() => {
		const vw = document.documentElement.clientWidth;
		const out: string[] = [];
		if (document.documentElement.scrollWidth > vw + 1) out.push(`page scrollWidth ${document.documentElement.scrollWidth} > ${vw}`);
		for (const el of document.querySelectorAll('main *')) {
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

/** No two boxes among `selector` matches (leaf text blocks of the hero / end screen) overlap. */
async function assertNoOverlap(page: Page, scope: string): Promise<void> {
	const hits = await page.evaluate((sel) => {
		const root = document.querySelector(sel);
		if (!root) return ['missing ' + sel];
		const leaves = [...root.querySelectorAll('h2, p, a, button, dt, .bar, [role="progressbar"]')].filter((e) => {
			const r = e.getBoundingClientRect();
			return r.width > 0 && r.height > 0 && !e.closest('[aria-hidden="true"]');
		});
		const out: string[] = [];
		for (let i = 0; i < leaves.length; i++)
			for (let j = i + 1; j < leaves.length; j++) {
				const a = leaves[i];
				const b = leaves[j];
				if (a.contains(b) || b.contains(a)) continue;
				const ra = a.getBoundingClientRect();
				const rb = b.getBoundingClientRect();
				const w = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left);
				const h = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
				if (w > 1 && h > 1) out.push(`${a.tagName}"${(a.textContent ?? '').trim().slice(0, 20)}" x ${b.tagName}"${(b.textContent ?? '').trim().slice(0, 20)}"`);
			}
		return out.slice(0, 6);
	}, scope);
	expect(hits, `overlapping blocks in ${scope}`).toEqual([]);
}

/** The block ends above the fixed bottom bar (phones) and above the review answer bar. */
async function assertAboveBars(page: Page, scope: string): Promise<void> {
	const r = await page.evaluate((sel) => {
		const el = document.querySelector(sel);
		if (!el) return null;
		const bottom = Math.max(...[...el.querySelectorAll('*')].map((e) => e.getBoundingClientRect()).filter((b) => b.height > 0).map((b) => b.bottom));
		const bars = [...document.querySelectorAll('nav, footer.actions')]
			.filter((n) => {
				const s = getComputedStyle(n);
				const b = n.getBoundingClientRect();
				// Bottom bars only (the desktop side rail is sticky too, from the top).
				const bar = s.position === 'fixed' || s.position === 'sticky' || n.matches('footer.actions');
				return bar && b.height > 0 && b.top > window.innerHeight / 2 && n.children.length > 0;
			})
			.map((n) => n.getBoundingClientRect().top);
		return { bottom, barTop: bars.length > 0 ? Math.min(...bars) : Infinity };
	}, scope);
	expect(r).not.toBeNull();
	if (r) expect(r.bottom, `${scope} bottom <= bar top`).toBeLessThanOrEqual(r.barTop + 1);
}

for (const s of SIZES) {
	test.describe(`review loop ${s.name}`, () => {
		test.use({ viewport: { width: s.width, height: s.height }, hasTouch: s.mobile, isMobile: s.mobile });

		for (const phase of Object.keys(LOOPS)) {
			test(`home ${phase}`, async ({ page, context }) => {
				await serveLoop(page, phase);
				await open(page, context, '/');
				await page.locator(`.hero [data-phase="${phase}"]`).waitFor();
				await page.waitForTimeout(300);
				if (phase === 'reviewing') {
					await expect(page.locator('[data-testid="loop-ready"]')).toHaveText('201');
					await expect(page.locator('[data-testid="loop-backlog"]')).toHaveText('Backlog: 335 reviews due · 200/day limit');
					await expect(page.locator('[data-testid="loop-later"]')).toHaveText('8 learning later today · next in 6 min');
				}
				await assertNoOverflow(page);
				await assertNoOverlap(page, '.hero');
				await page.screenshot({ path: `screenshots/shell/loop-home-${phase}-${s.name}.png` });
			});
		}

		for (const phase of ['goal_reached', 'done_for_today', 'waiting', 'empty']) {
			test(`review ${phase}`, async ({ page, context }) => {
				await serveLoop(page, phase);
				await open(page, context, '/review');
				await page.locator(`.finished[data-phase]`).waitFor();
				await page.waitForTimeout(300);
				if (phase === 'done_for_today') {
					await expect(page.locator('[data-testid="end-quota"]')).toHaveText('200/200 reviews · 40/40 new');
					await expect(page.locator('[data-testid="end-backlog"]')).toHaveText('135 more reviews in backlog');
				}
				if (phase === 'waiting') await expect(page.locator('.finished .countdown')).toHaveText('4:30');
				await assertNoOverflow(page);
				await assertNoOverlap(page, '.finished');
				await assertAboveBars(page, '.finished');
				await page.screenshot({ path: `screenshots/shell/loop-review-${phase}-${s.name}.png` });
			});
		}

		test('review header with loop counts above the answer bar', async ({ page, context }) => {
			await serveLoop(page, 'reviewing');
			await open(page, context, '/review');
			await page.locator('.card-body').waitFor();
			await page.waitForTimeout(300);
			await assertNoOverflow(page);
			const r = await page.evaluate(() => ({
				stage: document.querySelector('.stage')?.getBoundingClientRect().bottom ?? 0,
				bar: document.querySelector('footer.actions')?.getBoundingClientRect().top ?? 0
			}));
			expect(r.stage, 'card above the answer bar').toBeLessThanOrEqual(r.bar + 1);
			await page.screenshot({ path: `screenshots/shell/loop-review-front-${s.name}.png` });
		});
	});
}

/** The mock shell with LOCAL_API_VERSION 2 and the `display` capability; `/shell/v1/display` answered here. */
async function seedDisplayShell(page: Page): Promise<void> {
	await page.addInitScript(() => {
		let value: unknown;
		const display = { lock_portrait: true };
		Object.defineProperty(window, '__kbShell', {
			configurable: true,
			get: () => value,
			set(v: { state: Record<string, any> }) {
				value = v;
				const st = v.state;
				const info = st.info;
				st.info = () => ({ ...info(), local_api_version: 2, capabilities: [...info().capabilities, 'display'] });
				const port = (window as any).KotobayaShell;
				const orig = port.postMessage;
				port.postMessage = (text: string) => {
					const m = JSON.parse(text);
					if (m.t === 'req' && m.path === '/shell/v1/display') {
						if (m.method === 'PATCH') Object.assign(display, JSON.parse(m.body));
						setTimeout(() =>
							port.onmessage?.({
								data: JSON.stringify({ v: 1, t: 'res', id: m.id, status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(display) })
							})
						);
						return;
					}
					orig(text);
				};
			}
		});
	});
}

for (const s of SIZES.filter((x) => x.mobile)) {
	test.describe(`this device ${s.name}`, () => {
		test.use({ viewport: { width: s.width, height: s.height }, hasTouch: true, isMobile: true });

		test('lock to portrait row', async ({ page, context }) => {
			await page.clock.setFixedTime(NOW);
			await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
			await page.emulateMedia({ reducedMotion: 'reduce' });
			await page.goto('/__mock/shell/on');
			await seedDisplayShell(page);
			await page.goto('/settings');
			await page.getByRole('heading', { name: 'Display' }).waitFor();
			const sw = page.getByRole('switch', { name: 'Lock to portrait' });
			await expect(sw).toBeChecked();
			await assertNoOverflow(page);
			await assertNoOverlap(page, 'section[aria-labelledby="dev-display"]');
			await page.locator('section[aria-labelledby="dev-display"]').scrollIntoViewIfNeeded();
			await page.locator('#this-device').screenshot({ path: `screenshots/shell/loop-device-display-${s.name}.png` });
		});
	});
}
