// SHELL-W4 layout guard: every route the phone keeps, at the two target phone sizes, in the mock shell
// (`/__mock/shell/on`, demo data). No element wider than the viewport, primary actions inside the viewport, tap
// targets >= 44 px, nothing hidden under the bottom bar, chip row clear of card text (0.1.27 regression), and the
// long keep-going stream / waiting screen behaviour. Output: screenshots/shell/<route>-<size>.png.
// Run: `playwright test shell-mobile`.
import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';

/* eslint-disable @typescript-eslint/no-explicit-any */
const NOW = new Date('2026-11-13T09:00:00Z');
const SIZES = [
	{ name: '360x800', width: 360, height: 800 },
	{ name: '412x915', width: 412, height: 915 }
] as const;

interface Screen {
	name: string;
	path: string;
	ready: (p: Page) => Promise<void>;
	prepare?: (p: Page) => Promise<void>;
}

const SCREENS: Screen[] = [
	{ name: 'home', path: '/', ready: (p) => p.locator('header.top').waitFor() },
	{ name: 'search', path: '/search', ready: (p) => p.getByRole('searchbox').first().waitFor() },
	{ name: 'dictionary', path: '/dictionary', ready: (p) => p.getByRole('searchbox').first().waitFor() },
	{
		name: 'dictionary-entry',
		path: '/dictionary',
		ready: (p) => p.getByRole('searchbox').first().waitFor(),
		prepare: async (p) => {
			await p.getByRole('searchbox').first().fill('to');
			await p.locator('button.result', { hasText: '取る' }).click();
			await p.getByRole('heading', { name: '取る' }).waitFor();
		}
	},
	{ name: 'browse', path: '/cards', ready: (p) => p.locator('main').waitFor() },
	{ name: 'decks', path: '/decks', ready: (p) => p.getByRole('heading', { level: 1 }).first().waitFor() },
	{ name: 'struggle', path: '/struggle', ready: (p) => p.getByRole('heading', { level: 1 }).first().waitFor() },
	{ name: 'settings', path: '/settings', ready: (p) => p.getByRole('heading', { name: 'Appearance' }).waitFor() },
	{ name: 'review-front', path: '/review', ready: (p) => p.locator('.card-body').waitFor() },
	{
		name: 'review-back',
		path: '/review',
		ready: (p) => p.locator('.card-body').waitFor(),
		prepare: async (p) => {
			await p.getByRole('button', { name: 'Show answer' }).click();
			await p.locator('.grade').first().waitFor();
		}
	}
];

async function open(page: Page, context: BrowserContext, path: string): Promise<void> {
	await page.clock.setFixedTime(NOW);
	await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.emulateMedia({ reducedMotion: 'reduce' });
	await page.goto('/__mock/shell/on');
	await page.goto(path);
}

/** Nothing wider than the viewport, no sideways page scroll. */
async function assertNoOverflow(page: Page): Promise<void> {
	await page.evaluate(() => document.fonts.ready.then(() => undefined));
	const bad = await page.evaluate(() => {
		const vw = document.documentElement.clientWidth;
		const out: string[] = [];
		if (document.documentElement.scrollWidth > vw + 1) out.push(`page scrollWidth ${document.documentElement.scrollWidth} > ${vw}`);
		for (const el of document.querySelectorAll('body *')) {
			const r = el.getBoundingClientRect();
			const s = getComputedStyle(el);
			if (r.width === 0 || r.height === 0 || s.visibility === 'hidden' || s.display === 'none') continue;
			if (el.closest('[aria-hidden="true"], .kb-sr, dialog:not([open]), [inert]')) continue;
			// Boxes inside their own horizontal scroller (tables, tab strips) may extend; the scroller itself may not.
			let p: Element | null = el.parentElement;
			let scrolled = false;
			while (p && p !== document.body) {
				const o = getComputedStyle(p).overflowX;
				if (o === 'auto' || o === 'scroll' || o === 'hidden' || o === 'clip') {
					scrolled = true;
					break;
				}
				p = p.parentElement;
			}
			if (scrolled) continue;
			if (r.right > vw + 1 || r.left < -1) out.push(`${el.tagName}.${(el as HTMLElement).className} [${Math.round(r.left)}..${Math.round(r.right)}] of ${vw}`);
		}
		return out.slice(0, 8);
	});
	expect(bad, 'elements wider than / outside the viewport').toEqual([]);
}

/** Visible buttons, tabs and bar links are at least 44 px in both directions. */
async function assertTapTargets(page: Page): Promise<void> {
	const small = await page.evaluate(() => {
		const out: string[] = [];
		for (const el of document.querySelectorAll('button, [role="button"], [role="tab"], nav a, header a')) {
			const r = el.getBoundingClientRect();
			const s = getComputedStyle(el);
			if (r.width === 0 || r.height === 0 || s.visibility === 'hidden' || s.display === 'none') continue;
			if (el.closest('[aria-hidden="true"], .kb-sr, dialog:not([open]), [inert]')) continue;
			if (el.matches('.kb-sr, [tabindex="-1"]')) continue;
			// The design widens dense controls with an invisible ::after hit area: measure what a finger can hit.
			const cx = r.left + r.width / 2;
			const hits = (y: number): boolean => {
				if (y < 0 || y > window.innerHeight) return true;
				const t = document.elementFromPoint(cx, y);
				return t !== null && (t === el || el.contains(t));
			};
			const tall = r.height >= 43.5 || (hits(r.top + r.height / 2 - 21.5) && hits(r.top + r.height / 2 + 21.5));
			if (!tall || r.width < 43.5) {
				out.push(`${el.tagName}.${(el as HTMLElement).className}"${(el.textContent ?? '').trim().slice(0, 16)}" ${Math.round(r.width)}x${Math.round(r.height)}`);
			}
		}
		return out.slice(0, 10);
	});
	expect(small, 'tap targets under 44 px').toEqual([]);
}

/** Last content is not hidden under the fixed bottom bar once the page is scrolled to its end. */
async function assertAboveBottomBar(page: Page): Promise<void> {
	await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
	const r = await page.evaluate(() => {
		const nav = [...document.querySelectorAll('nav')].find((n) => {
			const s = getComputedStyle(n);
			return (s.position === 'fixed' || s.position === 'sticky') && n.getBoundingClientRect().height > 0;
		});
		const main = document.querySelector('main');
		if (!nav || !main) return null;
		const navTop = nav.getBoundingClientRect().top;
		let bottom = 0;
		for (const el of main.querySelectorAll('*')) {
			const b = el.getBoundingClientRect();
			if (b.height === 0 || b.width === 0 || getComputedStyle(el).position === 'fixed') continue;
			// Content inside an inner scroller is clipped by it: the scroller's own box counts, not its rows.
			let inner = false;
			for (let p = el.parentElement; p && p !== main; p = p.parentElement) {
				const o = getComputedStyle(p).overflowY;
				if (o === 'auto' || o === 'scroll') inner = true;
			}
			if (!inner) bottom = Math.max(bottom, b.bottom);
		}
		return { navTop, bottom };
	});
	if (r) expect(r.bottom, 'last content bottom <= bottom bar top').toBeLessThanOrEqual(r.navTop + 1);
}

for (const s of SIZES) {
	test.describe(`shell mobile ${s.name}`, () => {
		// A phone: touch input (pointer: coarse) and the mobile viewport.
		test.use({ viewport: { width: s.width, height: s.height }, hasTouch: true, isMobile: true });

		for (const sc of SCREENS) {
			test(`${sc.name} fits`, async ({ page, context }) => {
				await open(page, context, sc.path);
				await sc.ready(page);
				if (sc.prepare) await sc.prepare(page);
				await page.waitForTimeout(400);
				await assertNoOverflow(page);
				await assertTapTargets(page);
				if (!sc.name.startsWith('review')) await assertAboveBottomBar(page);
				await page.evaluate(() => window.scrollTo(0, 0));
				await page.screenshot({ path: `screenshots/shell/${sc.name}-${s.name}.png` });
			});
		}

		test('review: primary actions inside the viewport, chips clear of card text', async ({ page, context }) => {
			await open(page, context, '/review');
			await page.locator('.card-body').waitFor();
			await page.evaluate(() => document.fonts.ready.then(() => undefined));
			const inView = async (loc: ReturnType<Page['locator']>, what: string): Promise<void> => {
				const b = await loc.first().boundingBox();
				expect(b, what).not.toBeNull();
				if (!b) return;
				expect(b.y, `${what} top`).toBeGreaterThanOrEqual(0);
				expect(b.y + b.height, `${what} bottom`).toBeLessThanOrEqual(s.height);
				expect(b.x + b.width, `${what} right`).toBeLessThanOrEqual(s.width + 0.5);
			};
			await inView(page.getByRole('button', { name: 'Show answer' }), 'Show answer');
			await page.getByRole('button', { name: 'Show answer' }).click();
			await page.locator('.grade').first().waitFor();
			const grades = page.locator('.grade');
			for (let i = 0; i < (await grades.count()); i++) await inView(grades.nth(i), `grade ${i}`);
			// One-thumb reach: the grade bar sits in the lower half of the screen.
			const g = await grades.first().boundingBox();
			expect(g !== null && g.y > s.height / 2, 'grade bar in the lower half').toBe(true);
			const clash = await page.evaluate(() => {
				const row = document.querySelector('.card .chip-row');
				const body = row?.nextElementSibling;
				if (!row || !body) return ['missing'];
				const bb = body.getBoundingClientRect();
				return [...row.children]
					.filter((c) => (c.textContent ?? '').trim())
					.map((c) => c.getBoundingClientRect().bottom - bb.top)
					.filter((d) => d > 0.5)
					.map(String);
			});
			expect(clash, 'chip row intrudes into card content').toEqual([]);
			// Card text stays selectable.
			const us = await page.locator('.card-body').evaluate((e) => getComputedStyle(e).userSelect);
			expect(us).not.toBe('none');
		});

		test('review: grade bar stays inside the viewport with the keyboard open', async ({ page, context }) => {
			await open(page, context, '/review');
			await page.locator('.card-body').waitFor();
			await page.getByRole('button', { name: 'Show answer' }).click();
			await page.locator('.grade').first().waitFor();
			// Android WebView resizes the layout viewport when the IME opens: emulate with a shorter viewport.
			const open_ = Math.round(s.height * 0.6);
			await page.setViewportSize({ width: s.width, height: open_ });
			await page.waitForTimeout(250);
			const b = await page.locator('.grade').first().boundingBox();
			expect(b, 'grade').not.toBeNull();
			if (b) expect(b.y + b.height, 'grade bar inside the shrunken viewport').toBeLessThanOrEqual(open_);
			await page.screenshot({ path: `screenshots/shell/review-back-keyboard-${s.name}.png` });
		});

		test('review: 60 cards with keep going never end early', async ({ page, context }) => {
			await page.clock.setFixedTime(NOW);
			let template: Record<string, any> | null = null;
			const answered = new Set<string>();
			const ids = [...Array(60).keys()].map((i) => `00000000-0000-7000-8000-${String(i).padStart(12, '0')}`);
			let keepFetches = 0;
			await page.route('**/api/v1/review/queue*', async (route: Route) => {
				const real = await route.fetch();
				const body = (await real.json()) as Record<string, any>;
				template ??= body.items[0] as Record<string, any>;
				const url = new URL(route.request().url());
				const keep = url.searchParams.get('keep_going') === 'true';
				const limit = Number(url.searchParams.get('limit') ?? 20);
				const items: unknown[] = [];
				if (keep) {
					keepFetches++;
					for (const id of ids) {
						if (items.length >= limit) break;
						if (!answered.has(id)) items.push({ ...template, card: { ...template?.card, id } });
					}
				}
				await route.fulfill({
					json: {
						...body,
						counts: { new: keep ? ids.length - answered.size : 0, learning: 0, review: 0 },
						more_available: !keep || answered.size < ids.length,
						next_learning_due_at: null,
						items
					}
				});
			});
			await page.route('**/api/v1/review/answer', async (route) => {
				const b = route.request().postDataJSON() as Record<string, any>;
				answered.add(b.card_id as string);
				await route.fulfill({
					json: {
						card: { ...template?.card, id: b.card_id },
						review: { id: b.id, card_id: b.card_id, device_id: '00000000-0000-7000-8000-000000000001', reviewed_at: NOW.toISOString(), rating: 3, undone_at: null }
					}
				});
			});
			await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
			await page.emulateMedia({ reducedMotion: 'reduce' });
			await page.goto('/__mock/shell/off'); // the fake shell answers queue fetches itself; plain mode keeps them on HTTP
			await page.goto('/review');
			// Empty normal queue with more_available: "done for now" offering Keep going; never the waiting screen.
			const keepGoing = page.getByRole('button', { name: 'Keep going' });
			await keepGoing.waitFor();
			await expect(page.getByRole('heading', { name: 'Waiting for the next card' })).toHaveCount(0);
			await keepGoing.click();
			for (let n = 0; n < ids.length; n++) {
				// A card is always on screen while items remain: no end screen, no waiting screen in between.
				await expect(page.getByRole('button', { name: 'Show answer' })).toBeEnabled({ timeout: 10_000 });
				await expect(page.getByRole('heading', { name: 'Waiting for the next card' })).toHaveCount(0);
				await page.getByRole('button', { name: 'Show answer' }).click({ timeout: 5000 });
				await page.locator('.grade').nth(2).click();
			}
			expect(answered.size).toBe(60);
			expect(keepFetches).toBeGreaterThan(2);
			// Only now, with nothing left, does an end screen replace the card.
			await expect(page.locator('.card-body')).toHaveCount(0, { timeout: 10_000 });
		});

		test('review: waiting screen only with next_learning_due_at', async ({ page, context }) => {
			await page.clock.setFixedTime(NOW);
			let withDue = true;
			await page.route('**/api/v1/review/queue*', async (route) => {
				const real = await route.fetch();
				const body = (await real.json()) as Record<string, unknown>;
				await route.fulfill({
					json: {
						...body,
						items: [],
						counts: { new: 0, learning: withDue ? 1 : 0, review: 0 },
						more_available: false,
						next_learning_due_at: withDue ? new Date(NOW.getTime() + 90_000).toISOString() : null
					}
				});
			});
			await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
			await page.emulateMedia({ reducedMotion: 'reduce' });
			await page.goto('/__mock/shell/on');
			await page.goto('/review');
			await page.getByRole('heading', { name: 'Waiting for the next card' }).waitFor();
			await assertNoOverflow(page);
			await assertTapTargets(page);
			await page.screenshot({ path: `screenshots/shell/review-waiting-${s.name}.png` });
			withDue = false;
			await page.goto('/review');
			await page.getByRole('main').getByRole('heading').first().waitFor();
			await expect(page.getByRole('heading', { name: 'Waiting for the next card' })).toHaveCount(0);
			await assertNoOverflow(page);
			await assertTapTargets(page);
			await page.screenshot({ path: `screenshots/shell/review-done-${s.name}.png` });
		});
	});
}
