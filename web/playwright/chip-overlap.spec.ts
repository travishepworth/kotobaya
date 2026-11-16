// KIND-CHIP-OVERLAP guard: the review card's chip row (type chip, New tag, review-type tag) must never
// intersect the card content. Run with `make screenshots-web` (or playwright test chip-overlap).
// Output: screenshots/chip-overlap/<case>.png (gitignored).
import { expect, test, type Page } from '@playwright/test';

const NOW = new Date('2026-11-13T09:00:00Z');
const CASES = [
	{ name: 'phone-390x844', width: 390, height: 844, reveal: false, needNew: false },
	{ name: 'short-desktop-1280x600', width: 1280, height: 600, reveal: false, needNew: false },
	{ name: 'compact-revealed-390x500', width: 390, height: 500, reveal: true, needNew: false },
	{ name: 'compact-revealed-1280x600', width: 1280, height: 600, reveal: true, needNew: false },
	// Kind chip + review-type tag + New tag together, front and revealed, on a narrow phone.
	{ name: 'new-card-340x600', width: 340, height: 600, reveal: false, needNew: true },
	{ name: 'new-card-revealed-340x600', width: 340, height: 600, reveal: true, needNew: true }
] as const;

async function assertNoOverlap(page: Page): Promise<number> {
	const row = page.locator('.card .chip-row');
	await row.waitFor();
	const rects = await page.evaluate(() => {
		const r = document.querySelector('.card .chip-row');
		// The scroll container is the visible content area: scrolled text is clipped at its top edge.
		const content = r?.nextElementSibling;
		if (!r || !content) return null;
		const box = (e: Element) => e.getBoundingClientRect().toJSON() as DOMRect;
		const chips = [...r.children].filter((c) => (c.textContent ?? '').trim() !== '').map(box);
		return { chips, content: box(content) };
	});
	expect(rects, 'chip row and content exist').not.toBeNull();
	if (!rects) return 0;
	expect(rects.chips.length).toBeGreaterThan(0);
	for (const c of rects.chips) {
		expect(c.bottom, 'chip bottom <= content top').toBeLessThanOrEqual(rects.content.top + 0.5);
	}
	return rects.chips.length;
}

/** No text line may straddle the scroll container's top edge (a half-cut glyph row reads as an overlap). */
async function assertNoClippedLine(page: Page): Promise<void> {
	await page.waitForTimeout(300);
	const cut = await page.evaluate(() => {
		const body = document.querySelector('.card-body');
		if (!body) return ['no body'];
		const top = body.getBoundingClientRect().top;
		const out: string[] = [];
		const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
		for (let n = walker.nextNode(); n; n = walker.nextNode()) {
			if (!(n.textContent ?? '').trim()) continue;
			const range = document.createRange();
			range.selectNodeContents(n);
			for (const r of range.getClientRects()) {
				if (r.height > 0 && r.top < top - 0.5 && r.bottom > top + 0.5) out.push(`${(n.textContent ?? '').trim().slice(0, 20)} [${r.top}..${r.bottom}] edge ${top}`);
			}
		}
		return out;
	});
	expect(cut, 'text lines cut by the scroll container top edge').toEqual([]);
}

for (const c of CASES) {
	test(`chip row does not overlap card content: ${c.name}`, async ({ page, context }) => {
		await page.clock.setFixedTime(NOW);
		await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
		await page.emulateMedia({ reducedMotion: 'reduce' });
		await page.setViewportSize({ width: c.width, height: c.height });
		await page.goto('/review');
		await page.locator('.card-body').waitFor();
		await page.evaluate(() => document.fonts.ready.then(() => undefined));
		if (c.needNew) {
			// The demo queue has no New card (they unlock later), so fill the other chip slots of the real row
			// with the longest realistic labels: kind chip + review-type tag + New tag all at once.
			await page.evaluate(() => {
				const set = (sel: string, text: string) => {
					const e = document.querySelector(sel);
					if (e) e.textContent = text;
				};
				set('.chip-row .tag', 'Relearning');
				set('.chip-row .new-badge', 'New');
			});
		}
		if (c.reveal) {
			await page.getByRole('button', { name: 'Show answer' }).click();
			await page.locator('.grade').first().waitFor();
		}
		const n = await assertNoOverlap(page);
		if (c.reveal) await assertNoClippedLine(page);
		console.log(`${c.name}: ${n} chips, no overlap`);
		await page.screenshot({ path: `screenshots/chip-overlap/${c.name}.png` });
	});
}
