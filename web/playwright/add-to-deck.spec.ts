// BUG-ADD2DECK-500 visual + regression check at phone size (360x800, mock shell, demo data): on a dictionary entry,
// "Add to deck" opens a picker listing the user's decks (nothing is added until a deck is ticked), ticking toggles
// membership, and neither that nor opening the note view hits SvelteKit's error page. Output:
// screenshots/add-to-deck/*.png. Run: `CHROMIUM_PATH=/usr/bin/chromium pnpm exec playwright test add-to-deck`.
import { expect, test, type Page } from '@playwright/test';

const OUT = 'screenshots/add-to-deck';

/**
 * Waits for the sheet's open animation (rise/fade from opacity 0; reduced motion keeps the fade) to finish, then
 * checks the sheet is really drawn: opaque, inside the viewport, and its bottom button is the topmost element
 * (not covered by the tab bar). A screenshot taken mid-fade shows the page through the sheet.
 */
async function sheetSettled(page: Page, buttonName: string | null): Promise<void> {
	await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'));
	const dialog = page.locator('dialog[open]');
	const style = await dialog.evaluate((d) => {
		const cs = getComputedStyle(d);
		const r = d.getBoundingClientRect();
		return { opacity: cs.opacity, bg: cs.backgroundColor, bottom: r.bottom, top: r.top };
	});
	expect(style.opacity).toBe('1');
	expect(style.bg).not.toBe('rgba(0, 0, 0, 0)');
	expect(style.bg).not.toMatch(/rgba\(.*, 0\)$/);
	expect(style.top).toBeGreaterThanOrEqual(0);
	expect(style.bottom).toBeLessThanOrEqual(800);
	if (buttonName !== null) {
		const btn = dialog.getByRole('button', { name: buttonName });
		const onTop = await btn.evaluate((b) => {
			const r = b.getBoundingClientRect();
			const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
			return hit !== null && b.contains(hit);
		});
		expect(onTop).toBe(true);
	}
}

async function openEntry(page: Page): Promise<string[]> {
	const errors: string[] = [];
	page.on('pageerror', (e) => errors.push(String(e)));
	await page.setViewportSize({ width: 360, height: 800 });
	await page.context().addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.emulateMedia({ reducedMotion: 'reduce' });
	await page.goto('/__mock/shell/on');
	await page.goto('/dictionary');
	await page.getByRole('searchbox').first().fill('to');
	await page.locator('button.result', { hasText: '取る' }).click();
	await page.getByRole('heading', { name: '取る' }).waitFor();
	return errors;
}

test('dictionary entry: Add to deck opens a deck picker and toggles without an effect loop', async ({ page }) => {
	const errors = await openEntry(page);
	const before = await page.locator('dialog[open]').count();
	expect(before).toBe(0);

	await page.getByRole('button', { name: 'Add to deck' }).first().click();
	const list = page.getByRole('list', { name: 'Decks' }).or(page.locator('dialog[open] ul.list'));
	await list.first().waitFor();
	const rows = page.locator('dialog[open] button[role="checkbox"]');
	expect(await rows.count()).toBeGreaterThan(0);
	// Opening the picker changes nothing: ticked rows are exactly the decks the "in decks" chips showed.
	const chips = await page.locator('ul.in li').first().locator('xpath=..').locator('li').count();
	const ticked = page.locator('dialog[open] button[role="checkbox"][aria-checked="true"]');
	expect(await ticked.count()).toBe(chips);
	await sheetSettled(page, 'Done');
	await page.screenshot({ path: `${OUT}/picker-open-360x800.png` });

	const row = page.locator('dialog[open] button[role="checkbox"][aria-checked="false"]').first();
	const name = (await row.textContent())?.trim() ?? '';
	const target = page.locator('dialog[open] button[role="checkbox"]', { hasText: name }).first();
	await target.click();
	await expect(target).toHaveAttribute('aria-checked', 'true');
	await page.screenshot({ path: `${OUT}/picker-ticked-360x800.png` });
	await target.click();
	await expect(target).toHaveAttribute('aria-checked', 'false');

	await expect(page.getByText('Something went wrong')).toHaveCount(0);
	expect(errors.filter((e) => e.includes('effect_update_depth_exceeded'))).toEqual([]);
});

test('note view (/notes?note=...) at phone width opens without an effect loop', async ({ page }) => {
	const errors = await openEntry(page);
	await page.getByRole('link', { name: 'Open note' }).first().click();
	await page.locator('dialog[open]').waitFor();
	await sheetSettled(page, null);
	await page.screenshot({ path: `${OUT}/note-view-360x800.png` });
	await expect(page.getByText('Something went wrong')).toHaveCount(0);
	expect(errors.filter((e) => e.includes('effect_update_depth_exceeded'))).toEqual([]);
});
