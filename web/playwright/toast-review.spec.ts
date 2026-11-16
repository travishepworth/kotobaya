// WEB-TOAST-REVIEW guard: with a toast visible on the review screen (front and back), Show answer, every grade
// button and the top-bar menu stay hit-testable. Output: screenshots/shell/review-toast-<side>-<size>.png.
import { expect, test, type Locator } from '@playwright/test';

const NOW = new Date('2026-11-13T09:00:00Z');
const SIZES = [
	{ name: '360x800', width: 360, height: 800 },
	{ name: '412x915', width: 412, height: 915 }
] as const;

async function hittable(loc: Locator, what: string): Promise<void> {
	const ok = await loc.evaluate((el) => {
		const r = el.getBoundingClientRect();
		const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
		return !!hit && (hit === el || el.contains(hit));
	});
	expect(ok, `${what} is hit-testable`).toBe(true);
}

for (const s of SIZES) {
	test.describe(s.name, () => {
		test.use({ viewport: { width: s.width, height: s.height } });
		for (const side of ['front', 'back'] as const) {
			test(`review ${side}: toast does not cover the controls`, async ({ page, context }) => {
				await page.clock.setFixedTime(NOW);
				await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
				await page.emulateMedia({ reducedMotion: 'reduce' });
				await page.goto('/__mock/shell/on');
				await page.goto('/review');
				await page.locator('.card-body').waitFor();
				if (side === 'back') {
					await page.getByRole('button', { name: 'Show answer' }).click();
					await page.locator('.grade').first().waitFor();
				}
				const toast = page.locator('.toast').first();
				// Nothing to undo: an info toast (retried, the first keypress can beat hydration).
				await expect(async () => {
					await page.keyboard.press('u');
					await expect(toast).toBeVisible({ timeout: 500 });
				}).toPass({ timeout: 20_000 });
				await toast.waitFor();
				await page.waitForTimeout(200);
				if (side === 'front') await hittable(page.getByRole('button', { name: 'Show answer' }), 'Show answer');
				else {
					const grades = page.locator('.grade');
					for (let i = 0; i < (await grades.count()); i++) await hittable(grades.nth(i), `grade ${i}`);
				}
				await hittable(page.locator('header.bar button').last(), 'menu');
				await hittable(page.locator('header.bar a, header.bar button').first(), 'close');
				await hittable(toast, 'toast');
				const t = await toast.boundingBox();
				const a = await page.locator('footer.actions .slot').boundingBox();
				expect(t && a && t.y + t.height <= a.y + 0.5, 'toast sits above the action bar').toBe(true);
				await page.screenshot({ path: `screenshots/shell/review-toast-${side}-${s.name}.png` });
			});
		}
	});
}
