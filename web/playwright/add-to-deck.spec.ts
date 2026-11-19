// BUG-ADD2DECK-500 visual + regression check at phone size (360x800, mock shell, demo data): on a dictionary entry,
// "Add to deck" opens a picker listing the user's decks (nothing is added until a deck is ticked), ticking toggles
// membership, and neither that nor opening the note view hits SvelteKit's error page. Output:
// screenshots/add-to-deck/*.png. Run: `CHROMIUM_PATH=/usr/bin/chromium pnpm exec playwright test add-to-deck`.
import { expect, test, type Page } from '@playwright/test';

const OUT = 'screenshots/add-to-deck';

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
	const openNote = page.getByRole('link', { name: 'Open note' });
	if ((await openNote.count()) > 0) await openNote.first().click();
	else await page.goto('/notes?note=00000000-0000-7000-8000-000000000001');
	await page.locator('main').waitFor();
	await page.waitForTimeout(500);
	await page.screenshot({ path: `${OUT}/note-view-360x800.png` });
	await expect(page.getByText('Something went wrong')).toHaveCount(0);
	expect(errors.filter((e) => e.includes('effect_update_depth_exceeded'))).toEqual([]);
});
