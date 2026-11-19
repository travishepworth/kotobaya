// WEB-REVIEW-BACK (ADR-048): history back during review undoes the last answer and shows that card revealed;
// undo (button, U key, back) is one path; a dictionary round trip restores the exact review state.
// Run: `playwright test review-back`. Screenshots: $BACK_SHOTS (default screenshots/shell).
import { expect, test, type BrowserContext, type Page } from '@playwright/test';

const SHOTS = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.BACK_SHOTS ?? 'screenshots/shell';
const grade = (page: Page) => page.locator('.grade').nth(2);
const showAnswer = (page: Page) => page.getByRole('button', { name: 'Show answer' });
const headword = (page: Page) => page.locator('.card-body').innerText();

async function open(page: Page, context: BrowserContext): Promise<{ undos: () => number }> {
	await context.addCookies([{ name: 'kb-mock-scenario', value: 'default', url: test.info().project.use.baseURL ?? 'http://localhost' }]);
	await page.emulateMedia({ reducedMotion: 'reduce' });
	let undos = 0;
	page.on('request', (r) => {
		if (r.method() === 'POST' && r.url().includes('/review/undo')) undos++;
	});
	await page.goto('/');
	await page.locator('a[href="/review"]').first().click();
	await page.locator('.card-body').waitFor();
	await showAnswer(page).waitFor();
	return { undos: () => undos };
}

/** The revealed card is settled and usable: card opacity 1 (animations done), grade buttons enabled. */
async function expectUsable(page: Page): Promise<void> {
	await expect.poll(() => page.locator('article.card').evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
	await expect.poll(() => page.locator('footer.actions').evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
	const grades = page.locator('.grade');
	for (let i = 0; i < (await grades.count()); i++) await expect(grades.nth(i)).toBeEnabled();
	await expect(page.locator('article.card.held')).toHaveCount(0);
	await page.waitForTimeout(800);
}

/** Grading the restored card sends exactly one answer request. */
async function gradeOnce(page: Page): Promise<void> {
	let n = 0;
	const on = (r: import('@playwright/test').Request) => {
		if (r.method() === 'POST' && r.url().includes('/review/answer')) n++;
	};
	page.on('request', on);
	await grade(page).click();
	await showAnswer(page).waitFor();
	await page.waitForTimeout(300);
	page.off('request', on);
	expect(n).toBe(1);
}

/** Reveal, remember the revealed text, grade. */
async function answer(page: Page): Promise<string> {
	await showAnswer(page).click();
	await grade(page).waitFor();
	const text = await headword(page);
	const req = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/review/answer'));
	await grade(page).click();
	const card = (await req).postDataJSON().card_id as string;
	await showAnswer(page).waitFor();
	return `${card}|${text}`;
}

test('history back steps through answers, card revealed; nothing left leaves', async ({ page, context }) => {
	const c = await open(page, context);
	const one = await answer(page);
	const two = await answer(page);
	expect(one).not.toBe(two);

	await page.goBack();
	await grade(page).waitFor();
	await expect(page).toHaveURL(/\/review/);
	expect(`${two.split('|')[0]}|${await headword(page)}`).toBe(two);
	expect(c.undos()).toBe(1);
	await expectUsable(page);
	await page.screenshot({ path: `${SHOTS}/revealed-after-back.png` });

	await page.goBack();
	await expect.poll(() => c.undos()).toBe(2);
	await expect(page).toHaveURL(/\/review/);
	await grade(page).waitFor();
	expect(`${one.split('|')[0]}|${await headword(page)}`).toBe(one);

	await page.goBack();
	await expect(page).not.toHaveURL(/\/review/);
	expect(c.undos()).toBe(2);
});

test('grading the card restored by back sends one answer', async ({ page, context }) => {
	await open(page, context);
	await answer(page);
	await page.goBack();
	await grade(page).waitFor();
	await expectUsable(page);
	await gradeOnce(page);
});

test('undo button and U key also show the card revealed', async ({ page, context }) => {
	const c = await open(page, context);
	const one = await answer(page);
	await page.keyboard.press('u');
	await grade(page).waitFor();
	expect(one.endsWith(`|${await headword(page)}`)).toBe(true);
	expect(c.undos()).toBe(1);

	await grade(page).click();
	await showAnswer(page).waitFor();
	await page.locator('header.bar button').last().click();
	await page.getByRole('menuitem', { name: /undo/i }).click();
	await grade(page).waitFor();
	expect(one.endsWith(`|${await headword(page)}`)).toBe(true);
	expect(c.undos()).toBe(2);
});

test('dictionary link from the revealed card: back restores it revealed', async ({ page, context }) => {
	await open(page, context);
	await answer(page);
	await showAnswer(page).click();
	await grade(page).waitFor();
	const before = await headword(page);
	await page.locator('a.dl').first().click();
	await expect(page).toHaveURL(/\/dictionary/);
	await page.goBack();
	await expect(page).toHaveURL(/\/review/);
	await grade(page).waitFor();
	expect(await headword(page)).toBe(before);
	await expectUsable(page);
	await page.screenshot({ path: `${SHOTS}/review-after-dictionary.png` });
	await gradeOnce(page);
	await page.goBack();
	await grade(page).waitFor();
	// the session history survived too: back now undoes the earlier answer
	await page.goBack();
	await grade(page).waitFor();
	await expect(page).toHaveURL(/\/review/);
});

test('link out from the unrevealed card: back restores the front', async ({ page, context }) => {
	await open(page, context);
	await answer(page);
	const before = await headword(page);
	await page.evaluate(() => {
		const a = document.createElement('a');
		a.href = '/dictionary';
		a.id = 'out';
		a.textContent = 'out';
		document.body.append(a);
	});
	await page.locator('#out').click();
	await expect(page).toHaveURL(/\/dictionary/);
	await page.goBack();
	await expect(page).toHaveURL(/\/review/);
	await showAnswer(page).waitFor();
	expect(await headword(page)).toBe(before);
	await page.screenshot({ path: `${SHOTS}/review-front-after-dictionary.png` });
});
