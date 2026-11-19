// Dictionary page screenshots + layout bounds checks. Output dir: DICT_SHOTS (default screenshots/dictionary).
import { expect, test, type Page } from '@playwright/test';

const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const OUT = env.DICT_SHOTS ?? 'screenshots/dictionary';
const SIZES = [
	{ name: '1280x800', width: 1280, height: 800 },
	{ name: '1920x1080', width: 1920, height: 1080 },
	{ name: '412x915', width: 412, height: 915 }
] as const;

async function boundsOk(page: Page): Promise<void> {
	const bad = await page.evaluate(() => {
		const out: string[] = [];
		const vw = document.documentElement.clientWidth;
		if (document.documentElement.scrollWidth > vw + 1) out.push(`horizontal overflow ${document.documentElement.scrollWidth} > ${vw}`);
		for (const e of document.querySelectorAll('.result, .entry .head, .entry .hw, .tile, .kanji .literal')) {
			const r = e.getBoundingClientRect();
			if (r.width === 0 || r.height === 0) continue;
			if (r.right > vw + 1 || r.left < -1) out.push(`${e.className} outside viewport [${r.left}..${r.right}]`);
		}
		// Result rows must not overlap each other.
		const rows = [...document.querySelectorAll('button.result')].map((e) => e.getBoundingClientRect());
		for (let i = 1; i < rows.length; i++) if (rows[i].top < rows[i - 1].bottom - 0.5) out.push(`result ${i} overlaps previous`);
		// List and detail panes must not overlap.
		const l = document.querySelector('.list')?.getBoundingClientRect();
		const d = document.querySelector('.detail')?.getBoundingClientRect();
		if (l && d && l.width > 0 && d.width > 0 && l.left < d.left && l.right > d.left + 0.5) out.push('list overlaps detail');
		return out;
	});
	expect(bad).toEqual([]);
}

for (const s of SIZES) {
	test(`dictionary ${s.name}`, async ({ page, context }) => {
		await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
		await page.emulateMedia({ reducedMotion: 'reduce' });
		await page.setViewportSize({ width: s.width, height: s.height });
		await page.goto('/dictionary');
		await page.getByRole('searchbox').first().waitFor();
		await page.screenshot({ path: `${OUT}/${s.name}-0-start.png` });
		await page.getByRole('searchbox').first().fill('to');
		await page.locator('button.result').first().waitFor();
		await page.waitForTimeout(400);
		await boundsOk(page);
		await page.screenshot({ path: `${OUT}/${s.name}-1-results.png` });
		await page.locator('button.result', { hasText: '取る' }).first().click();
		await page.getByRole('heading', { name: '取る' }).waitFor();
		await page.waitForTimeout(400);
		await boundsOk(page);
		await page.screenshot({ path: `${OUT}/${s.name}-2-entry.png` });
		await page.getByRole('tab', { name: /kanji/i }).click();
		await page.locator('.kanji .literal').waitFor();
		await page.waitForTimeout(500);
		await boundsOk(page);
		await page.screenshot({ path: `${OUT}/${s.name}-3-kanji.png` });
	});
}

test('dictionary desktop: arrows + Enter open entries, URL keeps query and entry, back/forward follow', async ({ page, context }) => {
	await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.setViewportSize({ width: 1280, height: 800 });
	await page.goto('/dictionary');
	const box = page.getByRole('searchbox').first();
	await box.fill('to');
	await page.locator('button.result').first().waitFor();
	await expect(page).toHaveURL(/q=to/);
	await box.press('ArrowDown');
	await expect(page.locator('button.result').first()).toBeFocused();
	await page.keyboard.press('ArrowDown');
	await expect(page.locator('button.result').nth(1)).toBeFocused();
	await page.keyboard.press('Enter');
	await expect(page).toHaveURL(/seq=\d+/);
	const first = new URL(page.url()).searchParams.get('seq');
	await page.locator('button.result').nth(3).click();
	await expect(page).not.toHaveURL(new RegExp(`seq=${first}(&|$)`));
	await page.goBack();
	await expect(page).toHaveURL(new RegExp(`seq=${first}(&|$)`));
	await expect(page.locator('button.result[aria-current="true"]')).toHaveCount(1);
	await page.goBack();
	await expect(page).not.toHaveURL(/seq=/);
});
