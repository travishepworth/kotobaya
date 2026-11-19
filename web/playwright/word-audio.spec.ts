// WEB-29 (ADR-047, REVIEW-LOOP 14): word audio on the review screen. The API is mocked here: the queue gets
// `word_audio`, /tts/resolve answers pending then ready, /blobs/* serves a tiny WAV; Audio.play is counted.
// Run: `playwright test word-audio`. Screenshots: $AUDIO_SHOTS (default screenshots/audio).
import { expect, test, type BrowserContext, type Page } from '@playwright/test';

const SHOTS = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.AUDIO_SHOTS ?? 'screenshots/audio';
const HASH = 'b5bb'.repeat(16);
const showAnswer = (page: Page) => page.getByRole('button', { name: 'Show answer' });
const WAV = Buffer.from('UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQAAAAA=', 'base64');

interface Opts {
	hash: string | null;
	autoplay?: boolean;
}

/** Mocks on top of the demo API; returns the played blob hashes and the resolve call count. */
async function setup(page: Page, context: BrowserContext, o: Opts): Promise<{ played: () => Promise<string[]>; resolves: () => number }> {
	await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.emulateMedia({ reducedMotion: 'reduce' });
	await page.addInitScript(() => {
		const w = window as unknown as { __played: string[] };
		w.__played = [];
		HTMLMediaElement.prototype.play = function (this: HTMLMediaElement) {
			w.__played.push(this.src.split('/').pop() ?? '');
			return Promise.resolve();
		};
		HTMLMediaElement.prototype.pause = () => {};
	});
	await page.route('**/api/v1/blobs/*', (r) => r.fulfill({ contentType: 'audio/wav', body: WAV }));
	await page.route('**/api/v1/review/queue*', async (route) => {
		const res = await route.fetch();
		const body = await res.json();
		for (const item of body.items ?? []) item.word_audio = { source: 'tts', blob_hash: o.hash, text: 'たべる', voice: '3' };
		await route.fulfill({ response: res, json: body });
	});
	let resolves = 0;
	await page.route('**/api/v1/tts/resolve', async (route) => {
		resolves++;
		const ready = resolves >= 2;
		await route.fulfill({
			json: {
				items: [
					{
						status: ready ? 'ready' : 'pending',
						blob_hash: ready ? HASH : null,
						duration_ms: ready ? 800 : null,
						retry_after_ms: ready ? null : 100,
						source: 'tts',
						text: 'たべる',
						voice: '3'
					}
				]
			}
		});
	});
	if (o.autoplay === false) {
		await page.route('**/api/v1/settings', async (route) => {
			const res = await route.fetch();
			const body = await res.json();
			body.items = [...(body.items ?? []), { key: 'review.autoplay_audio', value: false }];
			await route.fulfill({ response: res, json: body });
		});
	}
	await page.goto('/review');
	await page.locator('.card-body').waitFor();
	await showAnswer(page).waitFor();
	return {
		played: () => page.evaluate(() => (window as unknown as { __played: string[] }).__played),
		resolves: () => resolves
	};
}

/** The speaker sits inside the expression line, beside the word text, overlapping nothing. */
async function expectBesideWord(page: Page): Promise<void> {
	const btn = page.locator('.kb-expression .kb-audio');
	await expect(btn).toHaveCount(1);
	await expect(btn).toBeVisible();
	const r = await page.evaluate(() => {
		const b = document.querySelector('.kb-expression .kb-audio');
		const e = b?.closest('.kb-expression');
		const body = document.querySelector('.card-body');
		if (!b || !e || !body) return null;
		const box = b.getBoundingClientRect();
		const eb = e.getBoundingClientRect();
		const bb = body.getBoundingClientRect();
		const rects: DOMRect[] = [];
		const walker = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
		for (let n = walker.nextNode(); n; n = walker.nextNode()) {
			if (b.contains(n) || !(n.textContent ?? '').trim()) continue;
			const range = document.createRange();
			range.selectNodeContents(n);
			rects.push(...Array.from(range.getClientRects()));
		}
		const hit = rects.filter((t) => t.width > 0 && t.left < box.right - 0.5 && t.right > box.left + 0.5 && t.top < box.bottom - 0.5 && t.bottom > box.top + 0.5).length;
		return { box: box.toJSON(), eb: eb.toJSON(), bb: bb.toJSON(), texts: rects.length, hit };
	});
	expect(r).not.toBeNull();
	if (!r) return;
	expect(r.texts).toBeGreaterThan(0);
	expect(r.hit, 'speaker overlaps no word text').toBe(0);
	expect(r.box.left).toBeGreaterThanOrEqual(r.bb.left - 0.5);
	expect(r.box.right).toBeLessThanOrEqual(r.bb.right + 0.5);
	expect(r.box.top).toBeGreaterThanOrEqual(r.eb.top - 1);
	expect(r.box.bottom).toBeLessThanOrEqual(r.eb.bottom + 1);
}

async function settle(page: Page): Promise<void> {
	await expect.poll(() => page.locator('article.card').evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
	await page.waitForTimeout(800);
}

for (const vp of [
	{ name: 'phone-412x915', width: 412, height: 915 },
	{ name: 'desktop-1280x800', width: 1280, height: 800 }
]) {
	test(`speaker beside the word, autoplay once, P and tap replay (${vp.name})`, async ({ page, context }) => {
		await page.setViewportSize({ width: vp.width, height: vp.height });
		const a = await setup(page, context, { hash: HASH });
		// Front of a meaning card: no button, silence.
		await expect(page.locator('.kb-audio')).toHaveCount(0);
		expect(await a.played()).toEqual([]);
		await showAnswer(page).click();
		await page.locator('.grade').first().waitFor();
		await expectBesideWord(page);
		await expect.poll(a.played).toEqual([HASH]);
		await page.keyboard.press('p');
		await expect.poll(a.played).toEqual([HASH, HASH]);
		await page.locator('.kb-expression .kb-audio').click();
		await expect.poll(a.played).toEqual([HASH, HASH, HASH]);
		await settle(page);
		await page.screenshot({ path: `${SHOTS}/answer-${vp.name}.png` });
	});
}

test('autoplay off: no sound on reveal, P still plays', async ({ page, context }) => {
	const a = await setup(page, context, { hash: HASH, autoplay: false });
	await showAnswer(page).click();
	await page.locator('.grade').first().waitFor();
	await expectBesideWord(page);
	await page.waitForTimeout(500);
	expect(await a.played()).toEqual([]);
	await page.keyboard.press('p');
	await expect.poll(a.played).toEqual([HASH]);
});

test('pending audio: resolved while on the front, rendered and played once at reveal', async ({ page, context }) => {
	const a = await setup(page, context, { hash: null });
	await expect.poll(a.resolves).toBe(2);
	await showAnswer(page).click();
	await page.locator('.grade').first().waitFor();
	await expectBesideWord(page);
	await expect.poll(a.played).toEqual([HASH]);
	await page.waitForTimeout(500);
	expect(a.resolves()).toBe(2);
	expect(await a.played()).toEqual([HASH]);
});

test('pending audio: revealed first, the sound plays when the hash arrives', async ({ page, context }) => {
	const a = await setup(page, context, { hash: null });
	await showAnswer(page).click();
	await expect.poll(a.played).toEqual([HASH]);
});
